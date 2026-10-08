package main

// 输入侧工具:参数解析(-p k=v / --params @文件)、读文本来源、读批量来源、按开关拼客户端。
//
// 「不拼字符串」是这份客户端的一条设计取向:参数用 `-p key=value`(值按 JSON 解析)或
// `--params @file.json`,避开 PowerShell/cmd 吃引号的坑;长脚本用 `js @脚本.js`。

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

// parseKV 把 `-p key=value` 解析成对象。值优先按 JSON 解析(数字/布尔/数组/对象/字符串)。
func parseKV(items []string) (*Obj, error) {
	result := NewObj()
	for _, item := range items {
		cut := strings.Index(item, "=")
		if cut < 0 {
			return nil, usageErrorf("参数要写成 key=value 的形式,收到的是:%s", pyRepr(item))
		}
		key := strings.TrimSpace(item[:cut])
		if key == "" {
			return nil, usageErrorf("参数名不能为空:%s", pyRepr(item))
		}
		result.Set(key, parseValue(item[cut+1:]))
	}
	return result, nil
}

// parseValue 把值按 JSON 解析;解析不了就当字符串(这样 `-p text=Mac Mini` 也能用)。
func parseValue(text string) Value {
	trimmed := strings.TrimSpace(text)
	if trimmed == "" {
		return ""
	}
	value, err := DecodeJSON(trimmed)
	if err != nil {
		return trimmed
	}
	return value
}

// loadPayload 合并两个参数来源:`--params <JSON|@文件>` 与 `-p k=v`,后者优先。
//
// 文件里写**整个请求体**(`{"id":1001,"method":"request_human_input","params":{…}}`)也认,
// 会自动只取里面的 `params`。手边常常已经有一份完整请求体(留档文件、文档示例、别人贴过来的
// curl 载荷),原样存成文件喂进来的做法很自然;以前会把 `id`/`method` 当命令参数一起发下去,
// 而真正的参数一个都没传,报回来的却是「缺少参数 prompt」这种完全指不到原因的话。
func loadPayload(source *string, kv []string) (*Obj, error) {
	params := NewObj()
	if source != nil {
		text := ""
		switch {
		case strings.HasPrefix(*source, "@"):
			read, err := readLocalFile((*source)[1:])
			if err != nil {
				return nil, err
			}
			text = read
		case *source == "-":
			read, err := io.ReadAll(os.Stdin)
			if err != nil {
				return nil, usageErrorf("读标准输入失败:%v", err)
			}
			text = string(read)
		default:
			text = *source
		}
		loaded, err := DecodeJSON(text)
		if err != nil {
			return nil, usageErrorf("参数不是合法 JSON:%v", err)
		}
		object := asObj(loaded)
		if object == nil {
			return nil, usageErrorf("参数必须是 JSON 对象,例如 {\"selector\":\"#ok\"}")
		}
		// 整个请求体的识别标志是 method 字段(裸参数对象不会有它);只取 params 那一层
		if object.Has("method") {
			if inner := asObj(objGet(object, "params")); inner != nil {
				object = inner
			}
		}
		for _, key := range object.Keys() {
			value, _ := object.Get(key)
			params.Set(key, value)
		}
	}
	kvParams, err := parseKV(kv)
	if err != nil {
		return nil, err
	}
	for _, key := range kvParams.Keys() {
		value, _ := kvParams.Get(key)
		params.Set(key, value)
	}
	return params, nil
}

// readLocalFile 读本地文件;读不到算用法错(退出码 3),不要抛出难看的 traceback。
//
// 编码按 utf-8-sig 处理:**Windows PowerShell 的 `Out-File -Encoding utf8` 默认写 BOM**,
// 而带 BOM 的文本喂给 JSON 解析会直接报错 —— 用户自己存的参数文件十有八九带 BOM,所以这里必须容忍。
func readLocalFile(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", usageErrorf("读不了文件 %s:%v", path, err)
	}
	return strings.TrimPrefix(string(data), "\ufeff"), nil
}

// readTextSource 读一段文本:`@文件` 或直接给内容,`-` 表示标准输入。
func readTextSource(source string) (string, error) {
	if source == "-" {
		data, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", usageErrorf("读标准输入失败:%v", err)
		}
		return string(data), nil
	}
	if strings.HasPrefix(source, "@") {
		return readLocalFile(source[1:])
	}
	return source, nil
}

// readBatchSource 批量命令的来源:省略或 `-` 读标准输入,其余按**文件路径**读(`@` 前缀可写可不写)。
func readBatchSource(source *string) (string, error) {
	if source == nil || *source == "-" {
		data, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", usageErrorf("读标准输入失败:%v", err)
		}
		return string(data), nil
	}
	path := *source
	if strings.HasPrefix(path, "@") {
		path = path[1:]
	}
	return readLocalFile(path)
}

// unwrapCommands 把批量命令的几种写法归一成数组。
//
// 认三种:纯数组(推荐)、`{"commands":[…]}`、以及整个请求体
// `{"id":…,"method":"commands","params":{"commands":[…]}}`。第三种来自留档文件与文档示例 ——
// 喂进来时文件里明明就是数组,只是多包了一层,以前会得到一句「批量命令必须是数组」,指不到真正的原因。
func unwrapCommands(payload Value) Value {
	if object := asObj(payload); object != nil {
		if commands, ok := object.Get("commands"); ok {
			return commands
		}
		if params := asObj(objGet(payload, "params")); params != nil {
			if commands, ok := params.Get("commands"); ok {
				return commands
			}
		}
	}
	return payload
}

// buildClient 按「命令行 > 具名目标 > 环境变量 > 配置文件 > 内置默认」拼出客户端。
//
// 目标(host/port)的决定权交给 resolveEndpoint —— 多主机/多端口、具名目标、本机判断都在那里,
// 这里只负责剩下的记录与输出相关选项。
func buildClient(args *Args) (*Client, error) {
	endpoint, err := resolveEndpoint(args)
	if err != nil {
		return nil, err
	}
	if args.Verbose {
		fmt.Fprintf(os.Stderr, "[debug] 目标:%s(来源:%s,本机:%v)\n",
			endpoint.BaseURL, endpoint.Origin, endpoint.Local)
	}

	envString := func(key string) *string {
		if value := os.Getenv(key); value != "" {
			return &value
		}
		return nil
	}

	var taskID any = DefaultTaskID
	if value := envString("DSB_TASK_ID"); value != nil {
		taskID = *value
	}
	if args.ID != nil {
		taskID = *args.ID
	}

	var session *string
	if !args.NoRecord {
		if value := firstNonEmpty(derefOr(args.Session, ""), derefOr(envString("DSB_SESSION"), "")); value != "" {
			session = &value
		} else {
			value := DefaultSession
			session = &value
		}
	}

	return NewClient(ClientOptions{
		BaseURL:      endpoint.BaseURL,
		Host:         endpoint.Host,
		Port:         endpoint.Port,
		LocalTarget:  endpoint.Local,
		TaskID:       taskID,
		Timeout:      args.Timeout,
		Session:      derefOr(session, ""),
		Record:       session != nil,
		Verbose:      args.Verbose,
		RecordDir:    derefOr(args.RecordDir, ""),
		ResponseMode: derefOr(args.ResponseMode, ""),
		Diagnostics:  args.Diagnostics,
		AutoStart:    autoStartEnabled(args) && endpoint.Local,
	})
}

// firstNonEmpty 取第一个非空值。
func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

// autoStartEnabled 决定连不上服务时要不要自动拉起后端。
// 命令行 --no-auto-start 关掉,环境变量 DSB_AUTO_START=0/false/off 也关掉;默认开。
func autoStartEnabled(args *Args) bool {
	if args.NoAutoStart {
		return false
	}
	switch strings.ToLower(strings.TrimSpace(os.Getenv("DSB_AUTO_START"))) {
	case "0", "false", "off", "no":
		return false
	}
	return true
}

func derefOr(value *string, fallback string) string {
	if value == nil {
		return fallback
	}
	return *value
}

// jsonNumberInt 只接受整数字符串。
func jsonNumberInt(text string) (int, error) {
	trimmed := strings.TrimSpace(text)
	var parsed int
	if _, err := fmt.Sscanf(trimmed, "%d", &parsed); err != nil {
		return 0, err
	}
	if strconv.Itoa(parsed) != trimmed {
		return 0, fmt.Errorf("不是整数")
	}
	return parsed, nil
}
