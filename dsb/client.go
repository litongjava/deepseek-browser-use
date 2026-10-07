package main

// HTTP 客户端与记录层:请求、响应信封、每次调用落盘。
//
// 与 Python 版行为对齐的几件事:
//   - 服务端可能 gzip/deflate 压缩响应,三种情况都要兜住(Go 的 Transport 会自动解 gzip,
//     所以这里只需处理显式 Content-Encoding: deflate,以及用户手工设的 gzip);
//   - 4xx/5xx **不**当传输错:服务端自己回了失败信封,照常解析(HTTPError 分支);
//   - 落盘失败不该让调用失败;
//   - 记录编号接着上一轮往下排。

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// 默认值。
const (
	DefaultHost    = "localhost"
	DefaultPort    = 10049
	DefaultTaskID  = 1001
	DefaultTimeout = 300.0
	DefaultSession = "dsb"
)

// 命令接口与运维接口的路径(服务端注册见 PlaywrightAppConfig)。
const (
	pathCommand = "/playwright/command"
	pathUpload  = "/playwright/upload"
	pathHealth  = "/playwright/health"
	pathTasks   = "/playwright/tasks"
	pathMethods = "/playwright/methods"
	pathConfig  = "/playwright/config"
)

// Response 是一次调用的结果:服务端信封 + 本地观测信息。
type Response struct {
	URL       string
	Status    int
	Envelope  Value
	Raw       string
	ElapsedMs int
}

func (r *Response) Ok() bool {
	value, ok := objGet(r.Envelope, "ok").(bool)
	return ok && value
}

func (r *Response) Code() int {
	switch typed := objGet(r.Envelope, "code").(type) {
	case string:
		parsed, err := strconv.Atoi(typed)
		if err != nil {
			return 0
		}
		return parsed
	case interface{ String() string }:
		parsed, err := strconv.Atoi(typed.String())
		if err != nil {
			return 0
		}
		return parsed
	}
	return 0
}

func (r *Response) Msg() string {
	text, _ := objGet(r.Envelope, "msg").(string)
	return text
}

func (r *Response) Data() Value { return objGet(r.Envelope, "data") }

// Client 是 deepseek-browser-use 的 HTTP 客户端。
type Client struct {
	BaseURL      string
	TaskID       int
	Timeout      float64
	Session      string
	Record       bool
	Verbose      bool
	ResponseMode string
	Diagnostics  bool
	RecordDir    string
	Counter      int
	LastResponse *Response
	httpClient   *http.Client
	// AutoStart:连不上服务时是否按配置文件把后端拉起来(见 backend.go)。
	AutoStart bool
	// LocalTarget 说明连的是不是本机服务。远端目标不允许自动拉起(见 endpoint.go 文件头)。
	LocalTarget bool
	// autoStarted 保证每次调用只自动拉起并重试一次,不会「起不来 → 无限重试」。
	autoStarted bool
}

// NewClient 按 Python 版 Client.__init__ 的语义构造。
// taskID 必须是数字(或数字字符串),否则返回用法错 —— 与服务端无关的问题提前拦住。
func NewClient(options ClientOptions) (*Client, error) {
	baseURL := options.BaseURL
	if baseURL == "" {
		baseURL = fmt.Sprintf("http://%s:%d", options.Host, options.Port)
	}
	baseURL = strings.TrimRight(baseURL, "/")
	taskID, err := asTaskID(options.TaskID)
	if err != nil {
		return nil, err
	}
	session := options.Session
	recordDir := options.RecordDir
	if recordDir == "" {
		recordDir = defaultRecordDir(session)
	}
	return &Client{
		BaseURL:      baseURL,
		TaskID:       taskID,
		Timeout:      options.Timeout,
		Session:      session,
		Record:       options.Record && session != "",
		Verbose:      options.Verbose,
		ResponseMode: options.ResponseMode,
		Diagnostics:  options.Diagnostics,
		RecordDir:    recordDir,
		Counter:      nextIndex(recordDir),
		AutoStart:    options.AutoStart,
		LocalTarget:  options.LocalTarget,
		httpClient:   newHTTPClient(),
	}, nil
}

// ClientOptions 是构造客户端所需的全部参数。
type ClientOptions struct {
	BaseURL      string
	Host         string
	Port         int
	TaskID       any
	Timeout      float64
	Session      string
	Record       bool
	Verbose      bool
	RecordDir    string
	ResponseMode string
	Diagnostics  bool
	AutoStart    bool
	// LocalTarget 为真才允许自动拉起本机后端。零值 false 表示「远端/未知」——
	// 这样谁忘了设置,得到的是「不自动拉起」而不是「在别人机器上乱起服务」。
	LocalTarget bool
}

// newHTTPClient 造一个与 Python urllib 行为接近的客户端:
// 不自动跟随重定向(urllib 默认跟随,但浏览器自动化接口不需要,这里保持简单且可预测)。
func newHTTPClient() *http.Client {
	return &http.Client{
		// 逐请求设超时,所以这里不设总超时
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DisableCompression:    false,
			MaxIdleConns:          16,
			IdleConnTimeout:       60 * time.Second,
			ExpectContinueTimeout: time.Second,
		},
	}
}

// Request 发一次 HTTP 请求,把响应当成信封解析。拿不到合法 JSON 就是传输错。
//
// 开了 --auto-start(默认)时,如果这次失败是「连不上服务」,会先按配置文件把后端拉起来,
// 再重试一次。业务失败与服务超时都不触发 —— 超时说明服务在跑,只是慢。
func (c *Client) Request(path string, method string, body []byte, contentType string,
	query *Obj, timeout float64) (*Response, error) {
	response, err := c.requestOnce(path, method, body, contentType, query, timeout)
	if err == nil {
		return response, nil
	}
	if c.shouldAutoStart(err) {
		if startErr := c.autoStartBackend(); startErr != nil {
			// 自动拉起失败:把原始错误一起报出来,免得调用方以为请求本身有问题
			return nil, transportErrorf("%s(自动拉起后端也没成功:%v)", err.Error(), startErr)
		}
		return c.requestOnce(path, method, body, contentType, query, timeout)
	}
	return nil, err
}

// shouldAutoStart 判断这次失败该不该触发自动拉起:只在「连不上」这一类,且只触发一次。
func (c *Client) shouldAutoStart(err error) bool {
	if !c.AutoStart || c.autoStarted {
		return false
	}
	// 远端目标不自动拉起:服务在别人机器上,客户端没有理由去启动别人的进程
	// (那样只会把「地址写错了」变成「在本机起了个没用的服务,然后照样失败」)。
	if !c.LocalTarget {
		return false
	}
	transport, ok := asTransport(err)
	if !ok {
		return false
	}
	// 超时不算「没起」:服务在跑,只是慢;这时再拉一个只会更糟
	if strings.Contains(transport.Message, "请求超时") {
		return false
	}
	return strings.Contains(transport.Message, "连不上")
}

// requestOnce 是一次真正的 HTTP 往返,不含自动拉起。
func (c *Client) requestOnce(path string, method string, body []byte, contentType string,
	query *Obj, timeout float64) (*Response, error) {
	target := c.BaseURL + path
	if query != nil && query.Len() > 0 {
		parts := make([]string, 0, query.Len())
		for _, key := range query.Keys() {
			item, _ := query.Get(key)
			if item == nil {
				continue // 值为 None 的查询参数不拼进去
			}
			parts = append(parts, url.QueryEscape(key)+"="+url.QueryEscape(pyStr(item)))
		}
		if len(parts) > 0 {
			target += "?" + strings.Join(parts, "&")
		}
	}
	if timeout <= 0 {
		timeout = c.Timeout
	}

	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	request, err := http.NewRequest(method, target, reader)
	if err != nil {
		return nil, usageErrorf("请求地址不合法:%s(%v)", target, err)
	}
	// 逐请求超时:Python 版把 timeout 交给 urllib,这里交给 context
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout*float64(time.Second)))
	defer cancel()
	request = request.WithContext(ctx)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "dsb/"+version)
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	} else if body != nil {
		request.Header.Set("Content-Type", "application/octet-stream")
	}
	// 自己处理压缩:服务端可能回 Content-Encoding,而我们要按原字节解码
	request.Header.Set("Accept-Encoding", "gzip, deflate")

	started := time.Now()
	httpResponse, err := c.httpClient.Do(request)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || os.IsTimeout(err) {
			return nil, transportErrorf("请求超时 %s(超过 %.0f 秒)", target, timeout)
		}
		return nil, transportErrorf("连不上 %s:%v(服务起了吗?地址对吗?)", target, unwrapURLError(err))
	}
	defer httpResponse.Body.Close()

	raw, readErr := io.ReadAll(httpResponse.Body)
	if readErr != nil {
		return nil, transportErrorf("读响应失败(%s):%v", target, readErr)
	}
	elapsedMs := int(time.Since(started).Milliseconds())

	text, decodeErr := decodeBody(raw, httpResponse.Header.Get("Content-Encoding"))
	if decodeErr != nil {
		return nil, transportErrorf("解压响应失败(%s):%v", target, decodeErr)
	}
	envelope, parseErr := DecodeJSON(text)
	if parseErr != nil {
		excerpt := text
		if len(excerpt) > 300 {
			excerpt = excerpt[:300]
		}
		return nil, transportErrorf("响应不是合法 JSON(%s,HTTP %d,耗时 %dms):%s",
			target, httpResponse.StatusCode, elapsedMs, excerpt)
	}
	if asObj(envelope) == nil {
		excerpt := text
		if len(excerpt) > 200 {
			excerpt = excerpt[:200]
		}
		return nil, transportErrorf("响应不是 JSON 对象(%s):%s", target, excerpt)
	}
	return &Response{URL: target, Status: httpResponse.StatusCode, Envelope: envelope,
		Raw: text, ElapsedMs: elapsedMs}, nil
}

// unwrapURLError 把 net 的错误压成人能读的一句话(与 Python 的 error.reason 位置相同)。
func unwrapURLError(err error) error {
	if urlErr, ok := err.(*url.Error); ok {
		if urlErr.Err != nil {
			return urlErr.Err
		}
	}
	return err
}

// decodeBody 处理 gzip / deflate / 明文三种情况。
func decodeBody(raw []byte, encoding string) (string, error) {
	lowered := strings.ToLower(encoding)
	if strings.Contains(lowered, "gzip") {
		reader, err := gzip.NewReader(bytes.NewReader(raw))
		if err != nil {
			return "", err
		}
		defer reader.Close()
		raw, err = io.ReadAll(reader)
		if err != nil {
			return "", err
		}
	} else if strings.Contains(lowered, "deflate") {
		// zlib 包装与裸 deflate 服务端都可能回,两种都试
		if reader, err := zlib.NewReader(bytes.NewReader(raw)); err == nil {
			decompressed, err := io.ReadAll(reader)
			reader.Close()
			if err == nil {
				raw = decompressed
			} else {
				raw = flateDecompress(raw)
			}
		} else {
			raw = flateDecompress(raw)
		}
	}
	// utf-8-sig:容忍 BOM(Windows 上的工具链很爱写它)
	text := strings.TrimPrefix(string(raw), "\ufeff")
	return text, nil
}

func flateDecompress(raw []byte) []byte {
	reader := flate.NewReader(bytes.NewReader(raw))
	defer reader.Close()
	decompressed, err := io.ReadAll(reader)
	if err != nil {
		return raw
	}
	return decompressed
}

// ------------------------------------------------------------------ 记录

// recordCall 把这一次调用落盘:NNN.req.json / NNN.res.json + 一行 steps.log。
func (c *Client) recordCall(label string, payload Value, response *Response, sendError string) {
	if !c.Record {
		return
	}
	index := c.Counter
	c.Counter++
	if err := os.MkdirAll(c.RecordDir, 0o755); err != nil {
		if c.Verbose {
			fmt.Fprintf(os.Stderr, "[warn] 记录失败:%v\n", err)
		}
		return
	}
	writeErr := func() error {
		requestText := EncodeJSON(payload, encIndent2)
		// 用 writeTextFile 而不是 os.WriteFile:它与旧 Python 版一样按文本模式把 \n 翻成 \r\n,
		// 换掉客户端之后留档文件的字节不变(实测旧记录就是 CRLF)。
		if err := writeTextFile(filepath.Join(c.RecordDir, fmt.Sprintf("%03d.req.json", index)),
			requestText); err != nil {
			return err
		}
		var responseText string
		if response != nil {
			responseText = EncodeJSON(response.Envelope, encIndent2)
		} else {
			responseText = EncodeJSON(ObjOf("sendFailed", true, "error", sendError), encIndent2)
		}
		if err := writeTextFile(filepath.Join(c.RecordDir, fmt.Sprintf("%03d.res.json", index)),
			responseText); err != nil {
			return err
		}
		stamp := time.Now().Format("2006-01-02 15:04:05")
		var line string
		if response == nil {
			line = fmt.Sprintf("%s #%03d id=%d %s SEND-FAILED %s", stamp, index, c.TaskID, label, sendError)
		} else {
			verdict := "FAIL"
			if response.Ok() {
				verdict = "OK"
			}
			line = fmt.Sprintf("%s #%03d id=%d %s %s %dms%s", stamp, index, c.TaskID, label,
				verdict, response.ElapsedMs, summarize(response.Data()))
			if !response.Ok() {
				line += " msg=" + response.Msg()
			}
		}
		handle, err := os.OpenFile(filepath.Join(c.RecordDir, "steps.log"),
			os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer handle.Close()
		_, err = handle.WriteString(line + "\n")
		return err
	}()
	if writeErr != nil && c.Verbose {
		fmt.Fprintf(os.Stderr, "[warn] 记录失败:%v\n", writeErr)
	}
}

// mask 已移除:这份客户端不做脱敏,留档与输出都是原样。

// ------------------------------------------------------------------ 命令接口

// buildCommandEnvelope 拼出命令请求的信封(id / method / params / responseMode / diagnostics)。
//
// 单独抽出来是因为「responseMode 与 diagnostics 属于信封层而不是 params」这条约定
// 踩过坑(塞错了服务端不报错、只是不生效),所以要能单独测。
func (c *Client) buildCommandEnvelope(method string, params Value, taskID *int) *Obj {
	envelope := NewObj()
	effectiveTaskID := c.TaskID
	if taskID != nil {
		effectiveTaskID = *taskID
	}
	envelope.Set("id", taskIDValue(effectiveTaskID))
	envelope.Set("method", method)
	if params == nil {
		params = NewObj()
	}
	envelope.Set("params", params)
	// responseMode / diagnostics 是**信封级**字段(与 method/params 平级),不是 params 里的东西。
	// 塞错了服务端不会报错,只是不生效 —— 实测踩过。
	if c.ResponseMode != "" {
		envelope.Set("responseMode", c.ResponseMode)
	}
	if c.Diagnostics {
		envelope.Set("diagnostics", true)
	}
	return envelope
}

// Command 发一条命令。业务失败不抛异常,由调用方看 Ok();传输失败返回传输错。
func (c *Client) Command(method string, params Value, taskID *int, timeout float64, label string) (*Response, error) {
	envelope := c.buildCommandEnvelope(method, params, taskID)
	payload := []byte(EncodeJSON(envelope, encDefault))
	if label == "" {
		label = method
	}

	response, err := c.Request(pathCommand, http.MethodPost, payload, "application/json", nil, timeout)
	if err != nil {
		c.recordCall(label, envelope, nil, err.Error())
		return nil, err
	}
	c.LastResponse = response
	c.recordCall(label, envelope, response, "")
	return response, nil
}

// Batch 跑一批命令。asynchronous 为真时立刻返回 jobId(不占用 HTTP 超时)。
func (c *Client) Batch(commands []Value, stopOnError, stopOnExpectFailure, asynchronous bool,
	maxDurationMs int, timeout float64, taskID *int) (*Response, error) {
	params := NewObj()
	params.Set("commands", commands)
	params.Set("stopOnError", stopOnError)
	if stopOnExpectFailure {
		params.Set("stopOnExpectFailure", true)
	}
	if asynchronous {
		params.Set("async", true)
	}
	if maxDurationMs > 0 {
		params.Set("maxDurationMs", maxDurationMs)
	}
	return c.Command("commands", params, taskID, timeout, "commands")
}

// ------------------------------------------------------------------ 便捷方法

func (c *Client) Health() (*Response, error) {
	return c.Request(pathHealth, http.MethodGet, nil, "", nil, 0)
}
func (c *Client) Methods() (*Response, error) {
	return c.Request(pathMethods, http.MethodGet, nil, "", nil, 0)
}
func (c *Client) Config() (*Response, error) {
	return c.Request(pathConfig, http.MethodGet, nil, "", nil, 0)
}
func (c *Client) Tasks() (*Response, error) {
	return c.Request(pathTasks, http.MethodGet, nil, "", nil, 0)
}

// Start 起一个任务。
func (c *Client) Start(browser string, headless bool, taskID *int) (*Response, error) {
	params := NewObj()
	params.Set("headless", headless)
	if browser != "" {
		params.Set("browser", browser)
	}
	return c.Command("start", params, taskID, 0, "start")
}

// Close 关掉一个任务。
func (c *Client) Close(taskID *int) (*Response, error) {
	return c.Command("close", NewObj(), taskID, 0, "close")
}

// Shutdown 关掉所有任务与共享浏览器。
func (c *Client) Shutdown() (*Response, error) {
	return c.Command("shutdown", NewObj(), nil, 0, "shutdown")
}

// Upload 把本地文件送到服务端暂存区(裸字节 + ?filename=,服务端三种写法都认)。
func (c *Client) Upload(path string, filename string) (*Response, error) {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return nil, usageErrorf("找不到要上传的文件:%s", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, usageErrorf("读不了要上传的文件 %s:%v", path, err)
	}
	name := filename
	if name == "" {
		name = filepath.Base(path)
	}
	digest := sha256.Sum256(data)
	payload := ObjOf("file", path, "filename", name, "size", json.Number(itoa(len(data))),
		"sha256", hex.EncodeToString(digest[:]))
	query := ObjOf("filename", name)

	response, err := c.Request(pathUpload, http.MethodPost, data, "application/octet-stream", query, 0)
	if err != nil {
		c.recordCall("upload", payload, nil, err.Error())
		return nil, err
	}
	c.LastResponse = response
	c.recordCall("upload", payload, response, "")
	return response, nil
}

// Uploads 列出暂存文件。
func (c *Client) Uploads() (*Response, error) {
	return c.Request(pathUpload, http.MethodGet, nil, "", nil, 0)
}

// DeleteUpload 删一个暂存文件。
func (c *Client) DeleteUpload(name string) (*Response, error) {
	return c.Request(pathUpload, http.MethodDelete, nil, "", ObjOf("name", name), 0)
}

// Job 查一个异步任务。
func (c *Client) Job(jobID string, includeResult bool) (*Response, error) {
	params := ObjOf("jobId", jobID, "includeResult", includeResult)
	return c.Command("get_job", params, nil, 0, "get_job")
}

// WaitJob 轮询异步任务直到结束(running 之外的状态都算结束)。
func (c *Client) WaitJob(jobID string, poll, timeout float64, onTick func(*Response)) (*Response, error) {
	deadline := time.Now().Add(time.Duration(timeout * float64(time.Second)))
	var last *Response
	for {
		var err error
		last, err = c.Job(jobID, true)
		if err != nil {
			return nil, err
		}
		status, _ := objGet(last.Data(), "status").(string)
		if onTick != nil {
			onTick(last)
		}
		if status != "" && status != "running" {
			return last, nil
		}
		if !time.Now().Before(deadline) {
			return nil, transportErrorf("等任务 %s 超时(超过 %.0f 秒,最后状态 %s)", jobID, timeout, status)
		}
		time.Sleep(time.Duration(poll * float64(time.Second)))
	}
}

// ------------------------------------------------------------------ 工具

// asTaskID 提前拦住非数字的任务 id(否则只能等服务端报错)。
func asTaskID(value any) (int, error) {
	switch typed := value.(type) {
	case nil:
		return DefaultTaskID, nil
	case bool:
		return 0, usageErrorf("任务 id 必须是数字,不能是布尔值")
	case int:
		return typed, nil
	case int64:
		return int(typed), nil
	case float64:
		if typed != float64(int(typed)) {
			return 0, usageErrorf("任务 id 必须是数字(例如 1001 或雪花 ID),收到的是:%v", typed)
		}
		return int(typed), nil
	case string:
		text := strings.TrimSpace(typed)
		parsed, err := strconv.Atoi(text)
		if err != nil {
			return 0, usageErrorf("任务 id 必须是数字(例如 1001 或雪花 ID),收到的是:%s", pyRepr(typed))
		}
		return parsed, nil
	}
	return 0, usageErrorf("任务 id 必须是数字(例如 1001 或雪花 ID),收到的是:%v", value)
}

// defaultRecordDir 记录目录:与 PowerShell 客户端保持一致,落在仓库的 logs/agent/<会话>/ 下。
func defaultRecordDir(session string) string {
	if session == "" {
		return filepath.Join("logs", "agent", "dsb")
	}
	if base := os.Getenv("DSB_RECORD_DIR"); base != "" {
		return filepath.Join(base, session)
	}
	return filepath.Join("logs", "agent", session)
}

// taskIDValue 把任务 id 变成请求体里的 JSON 数字。
func taskIDValue(taskID int) json.Number { return json.Number(itoa(taskID)) }

// nextIndex 接着已有的编号往下记,不覆盖上一轮的记录。
func nextIndex(directory string) int {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return 1
	}
	highest := 0
	pattern := regexp.MustCompile(`^(\d+)\.(?:req|res)\.json$`)
	for _, entry := range entries {
		if match := pattern.FindStringSubmatch(entry.Name()); match != nil {
			if value, err := strconv.Atoi(match[1]); err == nil && value > highest {
				highest = value
			}
		}
	}
	return highest + 1
}

// summarize 把回执里的关键字段压成一行,便于 steps.log 里一眼看出发生了什么。
//
// 只挑「一眼能判断这一步成不成」的标量字段;挑不出任何字段时返回空串,由调用方决定退化成什么。
func summarize(data Value) string {
	object := asObj(data)
	if object == nil {
		return ""
	}
	order := []string{"count", "countBlocking", "countStrict", "succeeded", "failed", "expectFailed",
		"seq", "status", "mode", "matched", "stable", "closed", "elementCount", "jobId", "step",
		"title", "url", "value", "visible", "enabled", "checked", "result", "actionStatus",
		"retrySafe", "observationComplete", "snapshotConsistent", "indicesUsable"}
	parts := make([]string, 0, 8)
	for _, key := range order {
		item, exists := object.Get(key)
		if !exists || isEmptyJSON(item) {
			continue
		}
		// 只收标量:字符串、布尔、数字。对象/数组不是「一眼能判断成不成」的字段。
		switch typed := item.(type) {
		case string:
			runes := []rune(typed)
			if len(runes) > 40 {
				typed = string(runes[:40]) + "…"
			}
			parts = append(parts, key+"="+typed)
		case bool:
			parts = append(parts, key+"="+pyStr(typed))
		case json.Number:
			parts = append(parts, key+"="+string(typed))
		}
	}
	if shot, ok := object.Get("screenshot"); ok {
		parts = append(parts, "shot="+pyStr(shot))
	}
	if results := asArray(objGet(data, "results")); results != nil {
		marks := make([]string, 0, 12)
		for position, step := range results {
			if position >= 12 {
				break
			}
			stepObj := asObj(step)
			if stepObj == nil {
				continue
			}
			okValue, _ := stepObj.Get("ok")
			mark := "fail"
			if flag, isBool := okValue.(bool); isBool && flag {
				mark = "ok"
			}
			// 与 Python 一致:expectResult 是个对象、且里面的 passed 不是真值时,标成 expect-fail
			// (passed 缺失也算没过 —— Python 的 `not None` 就是 True)
			if expectResult := asObj(objGet(step, "expectResult")); expectResult != nil {
				passedValue, exists := expectResult.Get("passed")
				passed, isBool := passedValue.(bool)
				if !exists || !isBool || !passed {
					mark = "expect-fail"
				}
			}
			index, indexExists := stepObj.Get("index")
			command, commandExists := stepObj.Get("command")
			if !indexExists {
				index = nil
			}
			if !commandExists {
				command = nil
			}
			marks = append(marks, pyStr(index)+":"+pyStr(command)+"="+mark)
		}
		parts = append(parts, "| "+strings.Join(marks, " "))
	}
	if len(parts) == 0 {
		return ""
	}
	return " " + strings.Join(parts, " ")
}
