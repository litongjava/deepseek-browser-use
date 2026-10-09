package main

// 各子命令的实现。退出码:0 成功 / 1 传输或协议错 / 2 业务失败 / 3 用法错。

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// cmdHealth 健康检查。
func cmdHealth(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Health()
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if handled {
		return verdict(response), nil
	}
	out.JSON(value)
	return verdict(response), nil
}

// cmdMethods 命令清单。
func cmdMethods(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Methods()
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if handled {
		return verdict(response), nil
	}
	if out.Select != nil {
		out.JSON(value)
		return verdict(response), nil
	}
	if !response.Ok() {
		out.JSON(response.Envelope)
		return ExitBusiness, nil
	}
	names := []string{}
	for _, item := range asArray(objGet(response.Data(), "methods")) {
		if text, ok := item.(string); ok {
			names = append(names, text)
		}
	}
	if args.Filter != nil && *args.Filter != "" {
		lowered := strings.ToLower(*args.Filter)
		filtered := make([]string, 0, len(names))
		for _, name := range names {
			if strings.Contains(strings.ToLower(name), lowered) {
				filtered = append(filtered, name)
			}
		}
		names = filtered
	}
	if args.JSON {
		out.JSON(ObjOf("count", numberFromInt(len(names)), "methods", stringsToValues(names)))
	} else if args.Compact {
		fmt.Fprintln(out.Out, strings.Join(names, " "))
	} else {
		filter := "无"
		if args.Filter != nil && *args.Filter != "" {
			filter = *args.Filter
		}
		out.Line(fmt.Sprintf("共 %d 个方法(过滤条件:%s)", len(names), filter))
		for _, name := range names {
			fmt.Fprintln(out.Out, "  "+name)
		}
	}
	return ExitOK, nil
}

// cmdConfig 服务端生效配置。
func cmdConfig(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Config()
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.JSON(value)
	}
	return verdict(response), nil
}

// cmdTasks 当前活着的任务。
func cmdTasks(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Tasks()
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.JSON(value)
	}
	return verdict(response), nil
}

// cmdRecipes 列配方 / 跑配方(显式点名才跑)。
func cmdRecipes(client CommandAPI, args *Args, out *Printer) (int, error) {
	if args.Run != nil {
		params := NewObj()
		params.Set("name", *args.Run)
		variables, err := parseKV(args.Var)
		if err != nil {
			return 0, err
		}
		if variables.Len() > 0 {
			params.Set("vars", variables)
		}
		response, err := client.Command("run_recipe", params, nil, 0, "run_recipe("+*args.Run+")")
		if err != nil {
			return 0, err
		}
		out.Response(response, "run_recipe("+*args.Run+")", false, 0, false, nil)
		return verdict(response), nil
	}
	response, err := client.Command("list_recipes", NewObj(), nil, 0, "list_recipes")
	if err != nil {
		return 0, err
	}
	value, err := pickIndex(response.Envelope, indexValue(args), args.Index != nil)
	if err != nil {
		return 0, err
	}
	if args.JSON || !response.Ok() {
		out.JSON(value)
		return verdict(response), nil
	}
	data := asObj(response.Data())
	directory, count := "", Value(nil)
	if data != nil {
		directory = objStr(data, "dir")
		count = mustGet(data, "count")
	}
	out.Line(fmt.Sprintf("配方目录:%s(共 %s 个)", directory, pyStr(count)))
	for _, item := range asArray(objGet(data, "recipes")) {
		out.Line(fmt.Sprintf("  %s  步数=%s  %s", objStr(item, "name"), pyStr(objGet(item, "stepCount")),
			objStr(item, "description")))
	}
	return ExitOK, nil
}

// cmdStart 起一个任务。
func cmdStart(client CommandAPI, args *Args, out *Printer) (int, error) {
	browser := derefOr(args.Browser, "")
	response, err := client.Start(browser, !args.Headful, nil)
	if err != nil {
		return 0, err
	}
	if response.Ok() {
		if honored, ok := objBool(response.Data(), "engineHonored"); ok && !honored {
			out.Warn("注意:engineHonored=false —— 这个服务实例没有按 browser 参数切浏览器(常见于旧发布包)")
		}
	}
	// --index 投影出来的 value 在这里用不上(打印的是整封回执),所以第一个返回值丢掉
	_, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if handled {
		return verdict(response), nil
	}
	out.Response(response, "start", false, 0, false, nil)
	if response.Ok() && out.Select == nil && !args.JSON {
		browserInfo := asObj(objGet(response.Data(), "browser"))
		if browserInfo != nil && browserInfo.Len() > 0 {
			out.Line(fmt.Sprintf("  引擎=%s 类型=%s profile=%s", objStr(browserInfo, "engine"),
				objStr(browserInfo, "type"), objStr(browserInfo, "profileDir")))
		}
	}
	return verdict(response), nil
}

// cmdClose 关掉这个任务。
func cmdClose(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Close(nil)
	if err != nil {
		return 0, err
	}
	// 同 cmdStart:这里打印的是整封回执,投影出来的 value 用不上
	_, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.Response(response, "close", false, 0, false, nil)
	}
	return verdict(response), nil
}

// cmdShutdown 关掉所有任务与共享浏览器。
func cmdShutdown(client CommandAPI, args *Args, out *Printer) (int, error) {
	response, err := client.Shutdown()
	if err != nil {
		return 0, err
	}
	out.Response(response, "shutdown", false, 0, false, nil)
	return verdict(response), nil
}

// cmdRun 执行一条命令。
func cmdRun(client CommandAPI, args *Args, out *Printer) (int, error) {
	params, err := loadPayload(args.Params, args.Param)
	if err != nil {
		return 0, err
	}
	// 动作类命令服务端默认一次都不重发(可能已经生效),但伪故障里有很大一支是「命令还没发出去就撞上了
	// 噪声」——那种情况重发是无害的,而这一次到底是不是写操作只有调用方知道。所以给 run 也开一个
	// --retry-on-spurious。
	if args.RetrySpur {
		params.Set("retryOnSpurious", true)
	}
	method := "run"
	if args.Method != nil {
		method = *args.Method
	}
	// 常见别名容错: press_key -> send_keys(key 与 keys 二选一,统一成 keys 后再发,
	// 并把 key 从参数里摘掉,避免服务端回 unknownParams:["key"] 的噪音)
	if method == "press_key" {
		method = "send_keys"
		if val, ok := params.Get("key"); ok {
			params.Set("keys", val)
			params.Delete("key")
		}
	}
	response, err := client.Command(method, params, nil, 0, "")
	if err != nil {
		return 0, err
	}
	hasPayload := false
	var payload Value
	if args.Index != nil {
		picked, pickErr := pickIndex(response.Envelope, *args.Index, true)
		if pickErr != nil {
			return 0, pickErr
		}
		payload = picked
		hasPayload = true
	}
	value := response.Envelope
	if hasPayload {
		value = payload
	}
	handled, err := emitSideOutput(out, args, value)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.Response(response, method, false, 0, hasPayload, payload)
	}
	return verdict(response), nil
}

// cmdBatch 批量执行命令(数组 JSON,默认读标准输入)。
func cmdBatch(client CommandAPI, args *Args, out *Printer) (int, error) {
	text, err := readBatchSource(args.File)
	if err != nil {
		return 0, err
	}
	parsed, err := DecodeJSON(text)
	if err != nil {
		return 0, usageErrorf("批量命令不是合法 JSON:%v", err)
	}
	commands := asArray(unwrapCommands(parsed))
	if commands == nil {
		return 0, usageErrorf("批量命令必须是数组,例如 [{\"get_title\":{}},{\"get_url\":{}}]" +
			"(也接受 {\"commands\":[…]},或整个请求体 {\"method\":\"commands\",\"params\":{…}})")
	}
	maxDuration := 0
	if args.MaxDurationMs != nil {
		maxDuration = *args.MaxDurationMs
	}
	response, err := client.Batch(commands, !args.KeepGoing, args.StopExpect, args.AsyncMode,
		maxDuration, 0, nil)
	if err != nil {
		return 0, err
	}
	if args.AsyncMode {
		out.Response(response, "commands(async)", false, 0, false, nil)
		if !response.Ok() || !args.Wait {
			return verdict(response), nil
		}
		jobID := objStr(response.Data(), "jobId")
		if jobID == "" {
			out.Warn("服务端没有返回 jobId,无法等待")
			return ExitBusiness, nil
		}
		return waitAndReport(client, out, jobID, pollValue(args), waitTimeoutValue(args))
	}
	hasPayload := false
	var payload Value
	if args.Index != nil {
		picked, pickErr := pickIndex(response.Envelope, *args.Index, true)
		if pickErr != nil {
			return 0, pickErr
		}
		payload = picked
		hasPayload = true
	}
	out.Response(response, "commands", false, 0, hasPayload, payload)
	return verdict(response), nil
}

// waitAndReport 轮询异步任务并汇报进度(每步一行,方便盯长批次)。
func waitAndReport(client CommandAPI, out *Printer, jobID string, poll, timeout float64) (int, error) {
	seen := 0
	tick := func(response *Response) {
		data := asObj(response.Data())
		if data == nil {
			return
		}
		steps := 0
		if number, ok := data.Get("steps"); ok {
			if parsed, err := strconv.Atoi(pyStr(number)); err == nil {
				steps = parsed
			}
		}
		if steps > seen {
			seen = steps
			out.Line(fmt.Sprintf("  任务 %s:已跑 %d 步(状态 %s)", jobID, steps, pyStr(mustGet(data, "status"))))
		}
	}
	final, err := client.WaitJob(jobID, poll, timeout, tick)
	if err != nil {
		if transport, ok := asTransport(err); ok {
			out.Warn(transport.Message)
			return ExitTransport, nil
		}
		return 0, err
	}
	data := asObj(final.Data())
	status := ""
	if data != nil {
		status = objStr(data, "status")
	}
	result := asObj(objGet(data, "data"))
	out.Line(fmt.Sprintf("任务 %s 结束:状态=%s 成功=%s 失败=%s 断言未过=%s 耗时=%sms", jobID, status,
		pyStr(objGet(result, "succeeded")), pyStr(objGet(result, "failed")),
		pyStr(objGet(result, "expectFailed")), pyStr(objGet(data, "durationMs"))))
	if final.Msg() != "" {
		out.Line("  原因:" + final.Msg())
	}
	if data != nil {
		if errorValue, ok := data.Get("error"); ok && !isEmptyJSON(errorValue) {
			out.Line("  错误:" + pyStr(errorValue))
		}
	}
	if out.Mode != "compact" {
		out.JSON(data)
	}
	if status == "done" {
		return ExitOK, nil
	}
	return ExitBusiness, nil
}

// cmdJob 查/等异步任务。
func cmdJob(client CommandAPI, args *Args, out *Printer) (int, error) {
	jobID := derefOr(args.JobID, "")
	if args.Wait {
		return waitAndReport(client, out, jobID, pollValue(args), waitTimeoutValue(args))
	}
	response, err := client.Job(jobID, true)
	if err != nil {
		return 0, err
	}
	value, err := pickIndex(response.Envelope, indexValue(args), args.Index != nil)
	if err != nil {
		return 0, err
	}
	out.JSON(value)
	return verdict(response), nil
}

// cmdUpload 把文件送到服务端暂存区。
func cmdUpload(client CommandAPI, args *Args, out *Printer) (int, error) {
	path := derefOr(args.File, "")
	filename := derefOr(args.Filename, "")
	response, err := client.Upload(path, filename)
	if err != nil {
		return 0, err
	}
	label := filename
	if label == "" {
		label = filepath.Base(path)
	}
	out.Response(response, "upload("+label+")", false, 0, false, nil)
	if !(args.JSON || args.Compact) && response.Ok() {
		out.Line("  服务端路径:" + objStr(response.Data(), "path"))
		out.Line("  可直接喂给 upload_file 的 path:" + objStr(response.Data(), "relativePath"))
	}
	return verdict(response), nil
}

// cmdUploads 列出暂存文件;--delete 删一个。
func cmdUploads(client CommandAPI, args *Args, out *Printer) (int, error) {
	if args.Delete != nil {
		response, err := client.DeleteUpload(*args.Delete)
		if err != nil {
			return 0, err
		}
		out.Response(response, "delete_upload("+*args.Delete+")", false, 0, false, nil)
		return verdict(response), nil
	}
	response, err := client.Uploads()
	if err != nil {
		return 0, err
	}
	value, err := pickIndex(response.Envelope, indexValue(args), args.Index != nil)
	if err != nil {
		return 0, err
	}
	out.JSON(value)
	return verdict(response), nil
}

// warnObservation 把取证告警留在 stderr(即使 stdout 只要文本或投影后的 JSON)。
func warnObservation(out *Printer, data Value) {
	object := asObj(data)
	if object == nil {
		return
	}
	degraded, _ := objGet(data, "capture_degraded").(bool)
	_, hasShotError := object.Get("screenshot_error")
	if degraded || hasShotError {
		note, _ := object.Get("capture_note")
		if note == nil {
			note, _ = object.Get("screenshot_error")
		}
		out.Warn("截图取证不完整:" + pyStr(note))
	}
	if flag, ok := object.Get("snapshotConsistent"); ok {
		if consistent, isBool := flag.(bool); isBool && !consistent {
			issues, exists := object.Get("snapshotIssues")
			if !exists {
				issues, _ = object.Get("snapshotHint")
			}
			out.Warn("快照不可靠:" + pyStr(issues))
		}
	}
	for _, field := range []string{"observationComplete", "probeTrustworthy", "indicesUsable"} {
		if flag, ok := object.Get(field); ok {
			if value, isBool := flag.(bool); isBool && !value {
				out.Warn(field + "=false，不能据此确认操作结果或继续使用旧索引")
			}
		}
	}
	if status, ok := object.Get("actionStatus"); ok && status == "unknown" {
		out.Warn("动作结果未知，请先读取业务结果，勿自动重试")
	}
}

// cmdState 页面状态摘要:标题、URL、元素数,以及可选的元素清单/正文。
func cmdState(client CommandAPI, args *Args, out *Printer) (int, error) {
	params := NewObj()
	params.Set("includeElements", !args.TextOnly)
	if args.ViewportExpansion != nil {
		params.Set("viewportExpansion", numberFromInt(*args.ViewportExpansion))
	}
	if args.IncludeFrames {
		params.Set("includeFrames", true)
	}
	if args.MaxElements != nil {
		params.Set("maxElements", numberFromInt(*args.MaxElements))
	}
	response, err := client.Command("get_browser_state", params, nil, 0, "get_browser_state")
	if err != nil {
		return 0, err
	}
	warnObservation(out, response.Data())
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if handled {
		return verdict(response), nil
	}
	if args.JSON || out.Select != nil || !response.Ok() {
		out.JSON(value)
		return verdict(response), nil
	}
	data := asObj(response.Data())
	if args.TextOnly {
		out.Line(objStr(data, "text"))
		return ExitOK, nil
	}
	elements := asArray(objGet(data, "elements"))
	truncated := ""
	if flag, ok := objGet(data, "elementsTruncated").(bool); ok && flag {
		truncated = ",已截断"
	}
	out.Line("标题:" + objStr(data, "title"))
	out.Line("URL:" + objStr(data, "url"))
	out.Line(fmt.Sprintf("元素:%s 个(内联 %d%s)  截图:%s", pyStr(mustGet(data, "elementCount")),
		len(elements), truncated, objStr(data, "screenshot")))
	tabs := asArray(objGet(data, "tabs"))
	if len(tabs) > 1 {
		parts := make([]string, 0, len(tabs))
		for _, tab := range tabs {
			parts = append(parts, fmt.Sprintf("[%s]%s", pyStr(objGet(tab, "index")), objStr(tab, "title")))
		}
		out.Line(fmt.Sprintf("页签 %d 个:%s", len(tabs), strings.Join(parts, " / ")))
	}
	if args.Full {
		for _, element := range elements {
			value := objStr(element, "value")
			extra := ""
			if value != "" {
				extra = " value=" + value
			}
			out.Line(fmt.Sprintf("  [%s] <%s> %s%s", pyStr(objGet(element, "index")),
				objStr(element, "tag"), objStr(element, "text"), extra))
		}
		if text := objStr(data, "text"); text != "" {
			out.Line("---- 可交互结构化文本 ----")
			out.Line(text)
		}
	}
	return ExitOK, nil
}

// cmdJS 执行 JavaScript。
func cmdJS(client CommandAPI, args *Args, out *Printer) (int, error) {
	body := ""
	if args.Script != nil {
		read, err := readTextSource(*args.Script)
		if err != nil {
			return 0, err
		}
		body = read
	}
	params := NewObj()
	params.Set("body", body)
	variables, err := parseKV(args.Var)
	if err != nil {
		return 0, err
	}
	if variables.Len() > 0 {
		params.Set("vars", variables)
	}
	// 只读脚本在「Object doesn't exist」这类 Playwright 事件分发伪故障下重发是无害的,而 execute_js
	// 恰恰是最常用的只读命令。不给这个开关时,调用方只能自己手拼 {"body":…,"retryOnSpurious":true}
	// 的 JSON 走 --params,把「用 dsb 少踩坑」这件事又还回去了。
	if args.RetrySpur {
		params.Set("retryOnSpurious", true)
	}
	response, err := client.Command("execute_js", params, nil, 0, "execute_js")
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if handled {
		return verdict(response), nil
	}
	if out.Mode == "json" || out.Select != nil {
		out.JSON(value)
	} else {
		// JS 的「值」才是重点:默认只打摘要 + 返回值,信封里的截图/序号是噪音
		if response.Ok() {
			out.Response(response, "execute_js", false, 0, true, objGet(response.Data(), "result"))
		} else {
			out.Response(response, "execute_js", false, 0, false, nil)
		}
	}
	return verdict(response), nil
}

// recordIndexPattern 从记录文件名(000001.res.json 这种)里抠出序号。
var recordIndexPattern = regexp.MustCompile("^([0-9]+)")

// cmdLast 重放本会话最近一次的响应(与 PowerShell 客户端的 -Last 一致)。
func cmdLast(client *Client, args *Args, out *Printer) (int, error) {
	entries, err := os.ReadDir(client.RecordDir)
	if err != nil {
		return 0, usageErrorf("还没有任何记录:%s", client.RecordDir)
	}
	files := []string{}
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".res.json") {
			files = append(files, entry.Name())
		}
	}
	if len(files) == 0 {
		return 0, usageErrorf("还没有任何记录:%s", client.RecordDir)
	}
	// 记录文件名是 %03d.res.json,而 %03d 只是「最小宽度」:第 1000 条叫 1000.res.json,
	// 字典序排在 999.res.json **前面**。所以不能 sort.Strings 之后取最后一个 ——
	// 一个会话里记录超过 999 条以后,dsb last 会一直重放第 999 条,而且退出码是 0,
	// 从外面完全看不出来。这里按数字前缀取最大。
	latest, index := "", -1
	for _, name := range files {
		current := -1
		if match := recordIndexPattern.FindStringSubmatch(name); match != nil {
			current, _ = strconv.Atoi(match[1])
		}
		if current > index {
			latest, index = name, current
		}
	}
	if index < 0 {
		// 一个数字前缀都没有:退回字典序取最后一个(正常不会走到,但别在这里崩)
		sort.Strings(files)
		latest, index = files[len(files)-1], 0
	}
	text, readErr := readLocalFile(filepath.Join(client.RecordDir, latest))
	if readErr != nil {
		return 0, readErr
	}
	envelope, parseErr := DecodeJSON(text)
	if parseErr != nil {
		return 0, usageErrorf("最近一份记录不是合法 JSON:%v", parseErr)
	}
	if flag, ok := objGet(envelope, "sendFailed").(bool); ok && flag {
		out.Warn(fmt.Sprintf("#%03d 这次是发送失败:%s", index, pyStr(objGet(envelope, "error"))))
		return ExitTransport, nil
	}
	value, err := pickIndex(envelope, indexValue(args), args.Index != nil)
	if err != nil {
		return 0, err
	}
	out.JSON(value)
	return ExitOK, nil
}

// ------------------------------------------------------------------ 小工具

// prepare 取这次要打印的值,并处理 --out / --grep。
//
// 顺序是有讲究的:先按 --index 投影,再交给侧输出(--out 写文件 / --grep 筛行);
// 返回的 handled=true 表示输出已经由侧输出完成,调用方不必再打整封。
//
// 这段原来在 10 个命令里各抄了一遍(含两处一模一样的错误处理)。抄多遍的代价不是行数,
// 而是**顺序会慢慢漂移**:实测有的命令先 --out 再 --select,有的反过来 —— 同一组参数
// 在不同子命令上行为不同,是这类重复最典型的后果。
func prepare(response *Response, args *Args, out *Printer) (Value, bool, error) {
	value, err := pickIndex(response.Envelope, indexValue(args), args.Index != nil)
	if err != nil {
		return nil, false, err
	}
	handled, err := emitSideOutput(out, args, value)
	if err != nil {
		return nil, false, err
	}
	return value, handled, nil
}

// verdict 把「服务端 ok」翻成退出码。
func verdict(response *Response) int {
	if response.Ok() {
		return ExitOK
	}
	return ExitBusiness
}

func indexValue(args *Args) int {
	if args.Index == nil {
		return 0
	}
	return *args.Index
}

func pollValue(args *Args) float64 {
	if args.Poll == nil {
		return 1.0
	}
	return *args.Poll
}

func waitTimeoutValue(args *Args) float64 {
	if args.WaitTimeout == nil {
		return 600.0
	}
	return *args.WaitTimeout
}
