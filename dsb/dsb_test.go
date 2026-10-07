package main

// 客户端自测(纯本地,不连服务)。
//
// 这里不连服务:参数解析、编号接续、输出策略、JSON 编解码、信封级字段全部是纯本地逻辑。
// 用例里的期望值都是从 Python 客户端实跑出来的原样结果,不是「看着差不多」——
// 两份客户端必须在同一份输入上给出同一份输出,否则换客户端的意义就没了。

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// ------------------------------------------------------------------ 不做脱敏

// TestNoRedaction 确认这份客户端**不**改动回执内容:手机号、证件号、邮箱、长号码
// 全部原样保留。旧 Python 版默认打码(会把要回填的 requestId/jobId 也一起掩掉),
// 这里刻意相反 —— 日志与输出都是原文,排查时看到什么就是什么。
func TestNoRedaction(t *testing.T) {
	values := []string{
		"13800138000",
		"11010119900307123X",
		"91310118MAK7DA2R14",
		"zhang.san+tag@example.co.uk",
		"订单号123456789012345678",
		"hr-1-1790232350369",
	}
	for _, source := range values {
		object := ObjOf("field", source, "requestId", source, "jobId", source)
		if got := EncodeJSON(object, encDefault); !strings.Contains(got, source) {
			t.Errorf("值 %q 被改动了:%s", source, got)
		}
	}
}

// ------------------------------------------------------------------ 参数解析

func TestParseKVMatchPython(t *testing.T) {
	cases := []struct {
		items    []string
		expected string
	}{
		{[]string{"url=https://example.com", "timeoutMs=900"},
			`{"url": "https://example.com", "timeoutMs": 900}`},
		{[]string{"headless=true", "mode=mouse"}, `{"headless": true, "mode": "mouse"}`},
		{[]string{"text=Mac Mini", "max=5"}, `{"text": "Mac Mini", "max": 5}`},
		{[]string{"index=0"}, `{"index": 0}`},
	}
	for _, item := range cases {
		actual, err := parseKV(item.items)
		if err != nil {
			t.Fatalf("parseKV(%v) 报错: %v", item.items, err)
		}
		if got := EncodeJSON(actual, encDefault); got != item.expected {
			t.Errorf("parseKV(%v) = %s,期望 %s", item.items, got, item.expected)
		}
	}
	if _, err := parseKV([]string{"nope"}); err == nil {
		t.Error("缺少 = 时应报用法错")
	}
	if _, err := parseKV([]string{"=1"}); err == nil {
		t.Error("参数名为空时应报用法错")
	}
}

func TestParseValueMatchPython(t *testing.T) {
	cases := []struct {
		text     string
		expected string
	}{
		{"1", "1"}, {"0", "0"}, {"true", "true"}, {"false", "false"}, {"1.5", "1.5"},
		{`{"a":1}`, `{"a": 1}`}, {"[1,2]", "[1, 2]"}, {"Mac Mini", `"Mac Mini"`},
		{`"引号"`, `"引号"`}, {"", `""`},
		// Python 的 json.loads 不认这些,所以原样当字符串
		{"007", `"007"`}, {"+1", `"+1"`}, {".5", `".5"`}, {"1.", `"1."`},
	}
	for _, item := range cases {
		actual := parseValue(item.text)
		if got := EncodeJSON(actual, encDefault); got != item.expected {
			t.Errorf("parseValue(%q) = %s,期望 %s", item.text, got, item.expected)
		}
	}
	// 1e3 会被 JSON 解析成浮点
	if got := EncodeJSON(parseValue("1e3"), encDefault); got != "1e3" {
		t.Errorf("parseValue(1e3) = %s(应保留字面量)", got)
	}
}

// ------------------------------------------------------------------ 保序与数字精度

func TestObjectKeepsInsertionOrder(t *testing.T) {
	object := ObjOf("z", numberFromInt(1), "a", numberFromInt(2), "m", numberFromInt(3))
	if got := EncodeJSON(object, encCompact); got != `{"z":1,"a":2,"m":3}` {
		t.Errorf("键序被改动: %s", got)
	}
	// 覆盖已有键不改变位置
	object.Set("z", numberFromInt(9))
	if got := EncodeJSON(object, encCompact); got != `{"z":9,"a":2,"m":3}` {
		t.Errorf("覆盖键后位置变了: %s", got)
	}
}

func TestBigNumbersSurviveRoundTrip(t *testing.T) {
	const snowflake = "1790232350369123456"
	value, err := DecodeJSON(`{"jobId":"` + snowflake + `","n":` + snowflake + `}`)
	if err != nil {
		t.Fatal(err)
	}
	if got := EncodeJSON(value, encCompact); got != `{"jobId":"`+snowflake+`","n":`+snowflake+`}` {
		t.Errorf("大整数被改写: %s", got)
	}
}

// ------------------------------------------------------------------ 任务 id

func TestAsTaskIDMatchesPython(t *testing.T) {
	for _, bad := range []any{"abc", "10a", "", true} {
		if _, err := asTaskID(bad); err == nil {
			t.Errorf("非法 id %#v 没有报用法错", bad)
		}
	}
	for _, good := range []struct {
		input    any
		expected int
	}{{1001, 1001}, {"1001", 1001}, {990001, 990001}} {
		actual, err := asTaskID(good.input)
		if err != nil {
			t.Errorf("合法 id %#v 报错: %v", good.input, err)
			continue
		}
		if actual != good.expected {
			t.Errorf("id %#v -> %d,期望 %d", good.input, actual, good.expected)
		}
	}
}

// ------------------------------------------------------------------ 摘要行

func TestSummarizeMatchesPython(t *testing.T) {
	summary := summarize(ObjOf("count", numberFromInt(2), "failed", numberFromInt(1), "results", []Value{
		ObjOf("index", numberFromInt(0), "command", "get_title", "ok", true),
		ObjOf("index", numberFromInt(1), "command", "click_element_by_index", "ok", false),
	}))
	if !strings.Contains(summary, "count=2") || !strings.Contains(summary, "1:click_element_by_index=fail") {
		t.Errorf("摘要内容不对: %q", summary)
	}
	// 0 与 false 不算空,要照常出现
	got := summarize(ObjOf("count", numberFromInt(0), "succeeded", true, "failed", false, "stable", true))
	if got != " count=0 succeeded=True failed=False stable=True" {
		t.Errorf("摘要 = %q,期望 %q", got, " count=0 succeeded=True failed=False stable=True")
	}
	// 挑不出标量字段时只有 results 那一段
	if got := summarize(ObjOf("nested", ObjOf("a", numberFromInt(1)), "value", jsonNumber("1.5"))); got != " value=1.5" {
		t.Errorf("对象字段不该进摘要: %q", got)
	}
	// 超长字符串截断到 40 个字符 + 省略号
	long := strings.Repeat("https://x", 20)
	if got := summarize(ObjOf("url", long)); !strings.Contains(got, "…") {
		t.Errorf("超长字符串应被截断: %q", got)
	}
}

// ------------------------------------------------------------------ 编号接续

func TestNextIndexContinues(t *testing.T) {
	folder := t.TempDir()
	if err := os.WriteFile(filepath.Join(folder, "001.req.json"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(folder, "007.res.json"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got := nextIndex(folder); got != 8 {
		t.Errorf("nextIndex = %d,期望 8", got)
	}
	if got := nextIndex(filepath.Join(folder, "nope")); got != 1 {
		t.Errorf("目录不存在时应从 1 开始,得到 %d", got)
	}
}

// ------------------------------------------------------------------ BOM 与文件读取

func TestReadLocalFileToleratesBOM(t *testing.T) {
	folder := t.TempDir()
	bom := filepath.Join(folder, "with-bom.json")
	if err := os.WriteFile(bom, []byte("\ufeff[{\"get_title\":{}}]"), 0o644); err != nil {
		t.Fatal(err)
	}
	text, err := readLocalFile(bom)
	if err != nil {
		t.Fatal(err)
	}
	if strings.HasPrefix(text, "\ufeff") || !strings.HasPrefix(text, "[{") {
		t.Errorf("带 BOM 的文件没被读干净: %q", text[:min(10, len(text))])
	}

	plain := filepath.Join(folder, "no-bom.json")
	if err := os.WriteFile(plain, []byte("[{\"get_title\":{}}]"), 0o644); err != nil {
		t.Fatal(err)
	}
	if text, err := readLocalFile(plain); err != nil || !strings.HasPrefix(text, "[{") {
		t.Errorf("不带 BOM 的文件读错了: %q %v", text, err)
	}
	if _, err := readLocalFile(filepath.Join(folder, "missing.json")); err == nil {
		t.Error("文件不存在时应报用法错")
	}
	if _, ok := mustUsage(t, func() error { _, err := readLocalFile(filepath.Join(folder, "m.json")); return err }); !ok {
		t.Error("文件不存在时应是用法错(退出码 3)")
	}
}

// TestReadBatchSourceByPath:batch 的位置参数是**文件路径**,不是 JSON 正文。
func TestReadBatchSourceByPath(t *testing.T) {
	folder := t.TempDir()
	path := filepath.Join(folder, "cmds.json")
	if err := os.WriteFile(path, []byte(`[{"get_title":{}}]`), 0o644); err != nil {
		t.Fatal(err)
	}
	text, err := readBatchSource(&path)
	if err != nil || !strings.HasPrefix(text, "[{") {
		t.Errorf("readBatchSource 应按文件路径读: %q %v", text, err)
	}
	// @ 前缀可写可不写
	atPath := "@" + path
	if text, err := readBatchSource(&atPath); err != nil || !strings.HasPrefix(text, "[{") {
		t.Errorf("@ 前缀也该认: %q %v", text, err)
	}
	missing := filepath.Join(folder, "missing.json")
	if _, err := readBatchSource(&missing); err == nil {
		t.Error("文件不存在时应报用法错")
	}
}

// ------------------------------------------------------------------ 输出策略

func sampleResponse() *Response {
	envelope := ObjOf("data", ObjOf("count", numberFromInt(2), "results", []Value{
		ObjOf("index", numberFromInt(0), "command", "get_title", "ok", true)}),
		"ok", true, "code", numberFromInt(1))
	return &Response{URL: "http://x", Status: 200, Envelope: envelope, Raw: "{}", ElapsedMs: 12}
}

func TestPrinterModes(t *testing.T) {
	// --compact 只输出一行
	buffer := &strings.Builder{}
	printer := &Printer{Mode: "compact", Out: buffer, ErrOut: buffer}
	printer.Response(sampleResponse(), "commands", false, 0, false, nil)
	lines := nonEmptyLines(buffer.String())
	if len(lines) != 1 || !strings.HasPrefix(lines[0], "commands OK") {
		t.Errorf("--compact 应只输出一行,得到 %v", lines)
	}

	// --json 只输出 JSON
	buffer = &strings.Builder{}
	printer = &Printer{Mode: "json", Out: buffer, ErrOut: buffer}
	printer.Response(sampleResponse(), "commands", false, 0, false, nil)
	if !strings.HasPrefix(strings.TrimSpace(buffer.String()), "{") {
		t.Errorf("--json 应只输出 JSON,得到 %q", buffer.String())
	}

	// payload 指定时只印被挑中的那一步
	buffer = &strings.Builder{}
	printer = &Printer{Mode: "full", Out: buffer, ErrOut: buffer}
	printer.Response(sampleResponse(), "commands", false, 0, true, ObjOf("picked", numberFromInt(1)))
	text := buffer.String()
	if !strings.Contains(text, "commands OK") || !strings.Contains(text, `"picked": 1`) {
		t.Errorf("payload 未生效: %q", text)
	}

	// 摘要为空时不能只回一行「OK 21ms」:那等于把答案吞了,要退回一行 JSON
	noSummary := &Response{URL: "http://x", Status: 200, Envelope: ObjOf("data",
		ObjOf("tabs", []Value{ObjOf("index", numberFromInt(0), "url", "https://a.example")}),
		"ok", true, "code", numberFromInt(1)), Raw: "{}", ElapsedMs: 21}
	buffer = &strings.Builder{}
	printer = &Printer{Mode: "compact", Out: buffer, ErrOut: buffer}
	printer.Response(noSummary, "get_tabs", false, 0, false, nil)
	lines = nonEmptyLines(buffer.String())
	if len(lines) != 2 || !strings.HasPrefix(lines[0], "get_tabs OK") || !strings.Contains(lines[1], `"tabs"`) {
		t.Errorf("摘要为空时应退回一行 JSON,得到 %v", lines)
	}
}

// ------------------------------------------------------------------ --select

func TestSelectFieldSemantics(t *testing.T) {
	// 显式 null 与「路径不存在」是两回事
	source := ObjOf("data", ObjOf("fields", []Value{nil}))
	value, err := selectField(source, "data.fields.0")
	if err != nil || value != nil {
		t.Errorf("显式 null 应原样返回: %v %v", value, err)
	}
	for _, path := range []string{"data.fields.1", "data.missing", "data.fields.-1"} {
		if _, err := selectField(source, path); err == nil {
			t.Errorf("路径 %s 不存在,应报用法错", path)
		}
	}
	if got, err := selectField(ObjOf("a", []Value{numberFromInt(1), numberFromInt(2)}), "a.1"); err != nil ||
		pyStr(got) != "2" {
		t.Errorf("数字下标取值失败: %v %v", got, err)
	}
}

// TestProjectionSelectsExactField:--select 精确取字段,值原样(不做任何改写)。
func TestProjectionPreservesUsableJobID(t *testing.T) {
	payload := ObjOf("data", ObjOf("jobId", "1790232350369123456", "phone", "13800138000"))
	for _, item := range []struct{ path, expected string }{
		{"data.jobId", "1790232350369123456"},
		{"data.phone", "13800138000"},
	} {
		buffer := &strings.Builder{}
		path := item.path
		printer := &Printer{Mode: "json", Out: buffer, ErrOut: buffer, Select: &path}
		printer.JSON(payload)
		var decoded any
		if err := json.Unmarshal([]byte(buffer.String()), &decoded); err != nil {
			t.Fatalf("输出不是合法 JSON: %q", buffer.String())
		}
		if decoded != item.expected {
			t.Errorf("--select %s = %#v,期望 %#v", item.path, decoded, item.expected)
		}
	}
}

// TestSelectionDoesNotHideFailures:失败响应同样按路径投影。
func TestSelectionDoesNotHideFailures(t *testing.T) {
	path := "data.text"
	buffer := &strings.Builder{}
	printer := &Printer{Mode: "json", Out: buffer, ErrOut: buffer, Select: &path}
	printer.JSON(ObjOf("ok", true, "data", ObjOf("text", "Email test@example.com")))
	if got := strings.TrimSpace(buffer.String()); got != `"Email test@example.com"` {
		t.Errorf("投影应原样取值,得到: %s", got)
	}

	// 失败响应**只要路径存在**就照常投影:批量回执里某一步失败时整批 ok 是 false,
	// 但 data.results[N] 仍在 —— 「只看失败那一步」正是靠这条才成立。
	failed := ObjOf("ok", false, "msg", "第 0 条命令失败", "data", ObjOf("count", numberFromInt(2),
		"results", []Value{
			ObjOf("index", numberFromInt(0), "command", "go_to_url", "ok", false),
			ObjOf("index", numberFromInt(1), "command", "get_title", "ok", true),
		}))
	stepPath := "data.results.1.command"
	buffer = &strings.Builder{}
	printer = &Printer{Mode: "json", Out: buffer, ErrOut: buffer, Select: &stepPath}
	printer.JSON(failed)
	if got := strings.TrimSpace(buffer.String()); got != `"get_title"` {
		t.Errorf("失败响应上路径存在时应照常投影,得到 %s", got)
	}

	// 路径不存在才退回整封,并在 stderr 说明
	missing := "data.nope"
	buffer = &strings.Builder{}
	errBuffer := &strings.Builder{}
	printer = &Printer{Mode: "json", Out: buffer, ErrOut: errBuffer, Select: &missing}
	printer.JSON(failed)
	if !strings.HasPrefix(strings.TrimSpace(buffer.String()), "{") {
		t.Errorf("路径不存在时应退回整封: %s", buffer.String())
	}
	if !strings.Contains(errBuffer.String(), "不存在") {
		t.Errorf("退回应在 stderr 说明: %q", errBuffer.String())
	}
}

// TestSelectionKeepsFullRecord:--select 只影响 stdout,记录仍是完整的那份。
func TestSelectionKeepsFullRecord(t *testing.T) {
	folder := t.TempDir()
	client, err := NewClient(ClientOptions{BaseURL: "http://x", TaskID: 1001, Session: "test",
		Record: true, RecordDir: folder, Timeout: 300})
	if err != nil {
		t.Fatal(err)
	}
	envelope := ObjOf("ok", true, "data", ObjOf("title", "Example", "text", "test@example.com",
		"extra", numberFromInt(123)))
	client.recordCall("get_title", ObjOf("id", numberFromInt(1001), "method", "get_title",
		"params", NewObj()), &Response{URL: "http://x", Status: 200, Envelope: envelope,
		Raw: "{}", ElapsedMs: 1}, "")

	entries, err := os.ReadDir(folder)
	if err != nil {
		t.Fatal(err)
	}
	var record string
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".res.json") {
			data, err := os.ReadFile(filepath.Join(folder, entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			record = string(data)
		}
	}
	if !strings.Contains(record, "test@example.com") {
		t.Errorf("记录应是原文(不打码): %s", record)
	}
	if !strings.Contains(record, `"extra": 123`) {
		t.Errorf("记录的其它字段应保留: %s", record)
	}
	if _, err := os.Stat(filepath.Join(folder, "steps.log")); err != nil {
		t.Error("应写出 steps.log")
	}
	// 记录文件是 CRLF(与旧 Python 客户端的文本模式逐字节一致,脚本与眼睛都依赖它)
	raw, err := os.ReadFile(filepath.Join(folder, "001.res.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), "\r\n") {
		t.Error("记录文件应是 CRLF 换行")
	}
}

// ------------------------------------------------------------------ 信封级字段

// TestEnvelopeLevelFields:responseMode / diagnostics 必须在**信封**层,不能塞进 params。
func TestEnvelopeLevelFields(t *testing.T) {
	captured := ""
	client, err := NewClient(ClientOptions{BaseURL: "http://x", TaskID: 1001, Session: "",
		ResponseMode: "compact", Diagnostics: true, Timeout: 300})
	if err != nil {
		t.Fatal(err)
	}
	_ = captured
	// 借道一个假的 request:把 payload 抓出来
	body := client.buildCommandEnvelope("get_browser_state", ObjOf("highlight", false), nil)
	text := EncodeJSON(body, encDefault)
	decoded, err := DecodeJSON(text)
	if err != nil {
		t.Fatal(err)
	}
	if objStr(decoded, "responseMode") != "compact" {
		t.Errorf("responseMode 应在信封层: %s", text)
	}
	if flag, ok := objGet(decoded, "diagnostics").(bool); !ok || !flag {
		t.Errorf("diagnostics 应在信封层: %s", text)
	}
	if objGet(decoded, "params").(*Obj).Has("responseMode") {
		t.Errorf("responseMode 不该出现在 params 里: %s", text)
	}
	if !strings.Contains(text, `"id": 1001`) {
		t.Errorf("信封应带数字 id: %s", text)
	}
}

// ------------------------------------------------------------------ batch 三种写法

func TestUnwrapCommands(t *testing.T) {
	commands, err := DecodeJSON(`[{"get_title":{}}]`)
	if err != nil {
		t.Fatal(err)
	}
	variants := []string{
		`[{"get_title":{}}]`,
		`{"commands":[{"get_title":{}}]}`,
		`{"id":1001,"method":"commands","params":{"commands":[{"get_title":{}}]}}`,
	}
	for _, text := range variants {
		parsed, err := DecodeJSON(text)
		if err != nil {
			t.Fatal(err)
		}
		if got := EncodeJSON(unwrapCommands(parsed), encCompact); got != EncodeJSON(commands, encCompact) {
			t.Errorf("unwrapCommands(%s) = %s", text, got)
		}
	}
}

// ------------------------------------------------------------------ --params 整个请求体

func TestLoadPayloadAcceptsWholeRequestEnvelope(t *testing.T) {
	folder := t.TempDir()
	path := filepath.Join(folder, "envelope-params.json")
	content := `{"id": 1001, "method": "request_human_input", "params": {"prompt": "请完成登录", "selector": "input[name=\"account\"]"}}`
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	source := "@" + path
	loaded, err := loadPayload(&source, nil)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"prompt": "请完成登录", "selector": "input[name=\"account\"]"}`
	if got := EncodeJSON(loaded, encDefault); got != want {
		t.Errorf("loadPayload = %s,期望 %s", got, want)
	}
	// -p 覆盖 --params
	loaded, err = loadPayload(&source, []string{"prompt=改一下"})
	if err != nil {
		t.Fatal(err)
	}
	if objStr(loaded, "prompt") != "改一下" {
		t.Errorf("-p 应覆盖 --params,得到 %q", objStr(loaded, "prompt"))
	}
}

// ------------------------------------------------------------------ js 的 retryOnSpurious

func TestJSRetryOnSpuriousReachesParams(t *testing.T) {
	for _, item := range []struct {
		flag     bool
		expected bool
	}{{true, true}, {false, false}} {
		fake := &recordingClient{}
		script := "() => 1"
		args := &Args{Script: &script, RetrySpur: item.flag}
		printBuffer := &strings.Builder{}
		printer := &Printer{Mode: "json", Out: printBuffer, ErrOut: printBuffer}
		if _, err := cmdJS(fake, args, printer); err != nil {
			t.Fatal(err)
		}
		params := asObj(fake.lastParams)
		got := params.Has("retryOnSpurious")
		if got != item.expected {
			t.Errorf("--retry-on-spurious=%v 时 params.retryOnSpurious 存在性 = %v", item.flag, got)
		}
	}
}

// ------------------------------------------------------------------ 观测告警

func TestTextOnlyRetainsWarningsOnStderr(t *testing.T) {
	client := &stubClient{stubBase{data: ObjOf("text", "页面正文", "capture_degraded", true,
		"capture_note", "截图不可用，联系 test@example.com", "observationComplete", false,
		"indicesUsable", false, "browser", ObjOf("engine", "chromium", "profileDir", "test"))}}
	stdout := &strings.Builder{}
	stderr := &strings.Builder{}
	args := &Args{TextOnly: true}
	printer := &Printer{Mode: "full", Out: stdout, ErrOut: stderr}
	if code, err := cmdState(client, args, printer); err != nil || code != ExitOK {
		t.Fatalf("cmdState = %d, %v", code, err)
	}
	if strings.TrimSpace(stdout.String()) != "页面正文" {
		t.Errorf("stdout 应只有页面文本: %q", stdout.String())
	}
	for _, expected := range []string{"截图取证不完整", "observationComplete=false", "indicesUsable=false"} {
		if !strings.Contains(stderr.String(), expected) {
			t.Errorf("stderr 应含 %q,得到 %q", expected, stderr.String())
		}
	}
	if !strings.Contains(stderr.String(), "test@example.com") {
		t.Errorf("告警里的原文应保留(不打码): %q", stderr.String())
	}
}

// TestStartEngineWarningSurvivesFileOutput:--out 不能把引擎告警吞掉。
func TestStartEngineWarningSurvivesFileOutput(t *testing.T) {
	folder := t.TempDir()
	target := filepath.Join(folder, "start.json")
	client := &stubClient{stubBase{data: ObjOf("engineHonored", false)}}
	stdout := &strings.Builder{}
	stderr := &strings.Builder{}
	args := &Args{Out: &target}
	printer := &Printer{Mode: "full", Out: stdout, ErrOut: stderr}
	if _, err := cmdStart(client, args, printer); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(stderr.String(), "engineHonored=false") {
		t.Errorf("引擎告警应留在 stderr: %q", stderr.String())
	}
}

// ------------------------------------------------------------------ 用法错的退出码

func TestCommandMisuseHint(t *testing.T) {
	hint := subcommandMisuseHint([]string{"--port", "10049", "--id", "1001", "run", "js", "@x.js"})
	if hint == "" || !strings.Contains(hint, "js 是子命令") {
		t.Errorf("应给出子命令误用提示,得到 %q", hint)
	}
	// run start / run close 是正常用法,不该提示
	if got := subcommandMisuseHint([]string{"run", "start"}); got != "" {
		t.Errorf("run start 不该提示,得到 %q", got)
	}
	if got := subcommandMisuseHint([]string{"run", "go_to_url"}); got != "" {
		t.Errorf("run go_to_url 不该提示,得到 %q", got)
	}
}

func TestSinkAndHelpers(t *testing.T) {
	// isEmptyJSON:0 与 false 不算空
	if isEmptyJSON(numberFromInt(0)) || isEmptyJSON(false) || isEmptyJSON("") != true {
		t.Error("isEmptyJSON 判定不对")
	}
	if isEmptyJSON(NewObj()) != true || isEmptyJSON([]Value{}) != true {
		t.Error("空对象/空数组应算空")
	}
	if pyStr(nil) != "None" || pyStr(true) != "True" || pyStr(false) != "False" {
		t.Error("pyStr 对 None/布尔 的渲染应与 Python 一致")
	}
}

// ------------------------------------------------------------------ 后端管理

// TestLocateJarPriority:jar 的查找顺序是「配置里指定的 > releases 里最新的 > target 里构建的 > dist 发行包」。
func TestLocateJarPriority(t *testing.T) {
	repo := t.TempDir()
	mk := func(rel string, content string) string {
		t.Helper()
		full := filepath.Join(repo, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
		return full
	}

	// 什么都没有:报用法错,并把找过的地方列出来
	if _, err := locateJar(repo, ""); err == nil {
		t.Error("找不到 jar 时应报用法错")
	} else if _, ok := asUsage(err); !ok {
		t.Errorf("应是用法错(退出码 3),得到 %T", err)
	}

	// dist 里有发行包
	distJar := mk(filepath.Join("dist", "deepseek-browser-use-1.0.0-windows-x64.jar"), "dist")
	if got, err := locateJar(repo, ""); err != nil || got != distJar {
		t.Errorf("应回退到 dist,得到 %q %v", got, err)
	}

	// target 优先于 dist
	targetJar := mk(filepath.Join("playwright-server", "target", "playwright-server-1.0.0.jar"), "target")
	if got, err := locateJar(repo, ""); err != nil || got != targetJar {
		t.Errorf("target 应优先于 dist,得到 %q %v", got, err)
	}
	// sources/javadoc 不算
	mk(filepath.Join("playwright-server", "target", "playwright-server-1.0.0-sources.jar"), "src")
	if got, _ := locateJar(repo, ""); got != targetJar {
		t.Errorf("sources jar 不该被选中,得到 %q", got)
	}

	// releases 优先于 target
	releaseJar := mk(filepath.Join(".dsb-backend", "releases", "aaaa", "backend.jar"), "release")
	if got, err := locateJar(repo, ""); err != nil || got != releaseJar {
		t.Errorf("releases 应优先于 target,得到 %q %v", got, err)
	}

	// 显式指定最优先(而且不必存在于是仓库里)
	explicit := filepath.Join(t.TempDir(), "custom.jar")
	if err := os.WriteFile(explicit, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, err := locateJar(repo, explicit); err != nil || got != explicit {
		t.Errorf("显式指定应最优先,得到 %q %v", got, err)
	}
}

// TestLocateJarPrefersNewestRelease:多个 release 时取 mtime 最新的那个。
func TestLocateJarPrefersNewestRelease(t *testing.T) {
	repo := t.TempDir()
	older := filepath.Join(repo, ".dsb-backend", "releases", "old", "backend.jar")
	newer := filepath.Join(repo, ".dsb-backend", "releases", "new", "backend.jar")
	for _, path := range []string{older, newer} {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	// 把 older 的时间往前拨,newer 应当是赢家
	past := time.Now().Add(-2 * time.Hour)
	if err := os.Chtimes(older, past, past); err != nil {
		t.Fatal(err)
	}
	if got, err := locateJar(repo, ""); err != nil || got != newer {
		t.Errorf("应取最新的 release,得到 %q %v", got, err)
	}
}

// TestCollectJarCandidatesListsAll:status 要看得到「全部候选」,而不只是会选中的那个 ——
// 排查时最常问的是「为什么跑的是这份、还有没有更新的」。
func TestCollectJarCandidatesListsAll(t *testing.T) {
	repo := t.TempDir()
	mk := func(rel string) string {
		t.Helper()
		full := filepath.Join(repo, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte("jar"), 0o644); err != nil {
			t.Fatal(err)
		}
		return full
	}

	dist := mk(filepath.Join("dist", "deepseek-browser-use-1.0.0-"+runtime.GOOS+"-"+mapArch(runtime.GOARCH)+".jar"))
	target := mk(filepath.Join("playwright-server", "target", "playwright-server-1.0.0.jar"))
	releaseA := mk(filepath.Join(".dsb-backend", "releases", "aaaaaaaa1111", "backend.jar"))
	releaseB := mk(filepath.Join(".dsb-backend", "releases", "bbbbbbbb2222", "backend.jar"))
	// releaseA 更新一点 → 应排在 releaseB 前面
	past := time.Now().Add(-3 * time.Hour)
	if err := os.Chtimes(releaseB, past, past); err != nil {
		t.Fatal(err)
	}

	candidates := collectJarCandidates(repo, "")
	if len(candidates) != 4 {
		t.Fatalf("应列出 4 个候选,得到 %d:%+v", len(candidates), candidates)
	}
	// 顺序:两个 release(新的在前)→ target → dist
	wantOrder := []string{releaseA, releaseB, target, dist}
	for index, want := range wantOrder {
		if candidates[index].Path != want {
			t.Errorf("第 %d 个候选应为 %s,得到 %s", index, want, candidates[index].Path)
		}
	}
	// release 候选要带上 commit 简写,dist 要带上 playwright 版本(没有则为空)
	if candidates[0].Source != "releases" || candidates[0].Detail != "aaaaaaaa" {
		t.Errorf("release 候选的来源/commit 不对:%+v", candidates[0])
	}
	if candidates[3].Source != "dist" {
		t.Errorf("最后一个应是 dist,得到 %+v", candidates[3])
	}
	if candidates[0].Size == 0 || candidates[0].ModTime.IsZero() {
		t.Errorf("候选应带上大小与时间:%+v", candidates[0])
	}

	// 显式指定时只有一个候选
	explicit := mk(filepath.Join("custom", "my.jar"))
	explicitCandidates := collectJarCandidates(repo, explicit)
	if len(explicitCandidates) != 1 || explicitCandidates[0].Source != "explicit" {
		t.Errorf("显式指定应只回一个候选:%+v", explicitCandidates)
	}
}

// TestDescribeCandidate:一行描述要能说清「来源 + 版本 + 时间 + 大小」。
func TestDescribeCandidate(t *testing.T) {
	candidate := JarCandidate{
		Path:    "/repo/.dsb-backend/releases/87a5e4d7aa/backend.jar",
		Source:  "releases",
		Detail:  "87a5e4d7",
		ModTime: time.Date(2026, 10, 6, 10, 52, 0, 0, time.UTC),
		Size:    54 * 1024 * 1024,
	}
	text := describeCandidate(candidate)
	for _, want := range []string{"/backend.jar", "releases", "87a5e4d7", "2026-10-06 10:52", "MB"} {
		if !strings.Contains(text, want) {
			t.Errorf("描述里应含 %q,得到 %q", want, text)
		}
	}
}

// TestMavenDriverPlatform:构建时要按当前平台裁剪 driver bundle(否则 5 个平台的 node 全进 jar)。
func TestMavenDriverPlatform(t *testing.T) {
	// 至少不能是空串 —— 空串会让 shade 把全部平台都打进去
	if got := mavenDriverPlatform(); got == "" {
		t.Errorf("当前平台(%s/%s)应有对应的 driver.platform", runtime.GOOS, runtime.GOARCH)
	}
}
func TestPlaywrightRevOrdering(t *testing.T) {
	paths := []string{
		"deepseek-browser-use-1.0.0-windows-x64.jar",                   // 不带 rev
		"deepseek-browser-use-1.0.0-windows-x64-playwright-1.53.0.jar", // 1530
		"deepseek-browser-use-1.0.0-windows-x64-playwright-1.63.0.jar", // 1630
	}
	want := paths[2]
	if got := preferHighestPlaywright(paths); got != want {
		t.Errorf("应挑 playwright 最高的那份,得到 %q", got)
	}
}

// TestConfigRoundTrip:配置文件能写能读,内容一致。
//
// 借 HOME/USERPROFILE 把「用户主目录」指到临时目录,免得污染真实配置。
func TestConfigRoundTrip(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)

	repo := filepath.Join(home, "repo")
	if err := os.MkdirAll(filepath.Join(repo, "playwright-server"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, "pom.xml"), []byte("<project/>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if !looksLikeRepo(repo) {
		t.Fatal("这个临时目录应当被认成仓库根")
	}

	// 一开始没有配置文件
	if config, path, err := loadConfig(); err != nil || config.RepoDir != "" {
		t.Fatalf("配置文件不该有内容:%v %v %v", config, path, err)
	}
	if _, err := saveConfig(&Config{RepoDir: repo}); err != nil {
		t.Fatal(err)
	}
	config, path, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	if config.RepoDir != repo {
		t.Errorf("repoDir = %q,期望 %q", config.RepoDir, repo)
	}
	if !strings.HasSuffix(path, filepath.Join(".dsb", "config.json")) {
		t.Errorf("配置路径应在 ~/.dsb/config.json,得到 %s", path)
	}
}

// TestConfigTargets:具名目标能写能读,而且老配置(只有 repoDir)照常能读。
func TestConfigTargets(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)

	// 老格式:只有 repoDir,没有 host/port/targets —— 必须还能读
	path := filepath.Join(home, ".dsb", "config.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"repoDir":"D:\\old\\repo"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	legacy, _, err := loadConfig()
	if err != nil {
		t.Fatalf("老格式配置应能读:%v", err)
	}
	if legacy.RepoDir == "" || legacy.Port != 0 || len(legacy.Targets) != 0 {
		t.Errorf("老格式解析不对:%+v", legacy)
	}

	// 写一份带 targets 的配置,再读回来
	config := &Config{Host: "127.0.0.1", Port: 10049, Targets: map[string]Target{
		"lab":    {Host: "10.0.0.5", Port: 10049, Note: "实验机"},
		"local2": {Host: "127.0.0.1", Port: 10050, RepoDir: "/tmp/repo"},
	}}
	if _, err := saveConfig(config); err != nil {
		t.Fatal(err)
	}
	reloaded, _, err := loadConfig()
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.Host != "127.0.0.1" || reloaded.Port != 10049 {
		t.Errorf("默认目标没存对:%+v", reloaded)
	}
	if got := reloaded.Targets["lab"]; got.Host != "10.0.0.5" || got.Port != 10049 || got.Note != "实验机" {
		t.Errorf("远端目标没存对:%+v", got)
	}
	if got := reloaded.Targets["local2"]; got.Port != 10050 || got.RepoDir != "/tmp/repo" {
		t.Errorf("本机目标没存对:%+v", got)
	}
}

// TestResolveEndpointPriority:地址的优先级是「--base-url > --use > --host/--port > 环境变量 > 配置文件 > 默认」。
func TestResolveEndpointPriority(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	for _, key := range []string{"DSB_BASE_URL", "DSB_HOST", "DSB_PORT"} {
		t.Setenv(key, "")
	}
	if _, err := saveConfig(&Config{Port: 10049, Targets: map[string]Target{
		"lab": {Host: "10.0.0.5", Port: 10049},
	}}); err != nil {
		t.Fatal(err)
	}

	// 默认(配置文件只有 port,没 host)→ localhost:10049
	endpoint, err := resolveEndpoint(&Args{})
	if err != nil {
		t.Fatal(err)
	}
	if endpoint.BaseURL != "http://localhost:10049" || !endpoint.Local {
		t.Errorf("默认目标不对:%+v", endpoint)
	}

	// --use 指到远端
	use := "lab"
	endpoint, err = resolveEndpoint(&Args{Use: &use})
	if err != nil {
		t.Fatal(err)
	}
	if endpoint.BaseURL != "http://10.0.0.5:10049" || endpoint.Local {
		t.Errorf("--use lab 解析不对:%+v", endpoint)
	}
	if !strings.Contains(endpoint.Origin, "lab") {
		t.Errorf("来源应写明具名目标:%+v", endpoint)
	}

	// --use 名字写错 → 用法错,且要把已登记的名字列出来
	missing := "nope"
	if _, err := resolveEndpoint(&Args{Use: &missing}); err == nil {
		t.Error("不存在的目标应报用法错")
	} else if _, ok := asUsage(err); !ok {
		t.Errorf("应是用法错,得到 %T", err)
	} else if !strings.Contains(err.Error(), "lab") {
		t.Errorf("报错应列出已登记的目标:%v", err)
	}

	// --base-url 最优先
	base := "http://192.168.1.9:8888"
	endpoint, err = resolveEndpoint(&Args{BaseURL: &base, Use: &use})
	if err != nil || endpoint.BaseURL != base || endpoint.Local {
		t.Errorf("--base-url 应最优先:%+v %v", endpoint, err)
	}

	// --host 单独给,端口取配置里的
	host := "10.0.0.7"
	endpoint, err = resolveEndpoint(&Args{Host: &host})
	if err != nil || endpoint.BaseURL != "http://10.0.0.7:10049" {
		t.Errorf("--host 应配上配置里的端口:%+v %v", endpoint, err)
	}
}

// TestIsLocalHost:只有明确的本地形态才算本机 —— 其余一律当远端(宁可少拉起一次)。
func TestIsLocalHost(t *testing.T) {
	for _, host := range []string{"localhost", "LOCALHOST", "127.0.0.1", "127.0.0.53", "0.0.0.0", "::1", ""} {
		if !isLocalHost(host) {
			t.Errorf("%q 应算本机", host)
		}
	}
	for _, host := range []string{"10.0.0.5", "192.168.1.9", "browser.lan", "8.8.8.8", "example.com"} {
		if isLocalHost(host) {
			t.Errorf("%q 不该算本机", host)
		}
	}
}

// TestRemoteTargetNeverAutoStarts:远端目标不自动拉起 —— 否则会把「地址写错了」
// 变成「在本机起了个没用的服务,然后照样失败」。
func TestRemoteTargetNeverAutoStarts(t *testing.T) {
	remote := &Client{AutoStart: true, LocalTarget: false}
	if remote.shouldAutoStart(transportErrorf("连不上 http://10.0.0.5:10049:x(服务起了吗?地址对吗?)")) {
		t.Error("远端目标不该触发自动拉起")
	}
	local := &Client{AutoStart: true, LocalTarget: true}
	if !local.shouldAutoStart(transportErrorf("连不上 http://127.0.0.1:10049:x(服务起了吗?地址对吗?)")) {
		t.Error("本机目标应当触发自动拉起")
	}
}

// TestResolveRepoDirPriority:仓库位置的优先级是「显式 > 环境变量 > 配置文件 > 自动探测」。
func TestResolveRepoDirPriority(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("DSB_REPO_DIR", "")

	explicit := filepath.Join(home, "explicit")
	if got, source, err := resolveRepoDir(explicit); err != nil || got != explicit || source != "--repo-dir" {
		t.Errorf("显式指定应最优先,得到 %q %q %v", got, source, err)
	}

	fromEnv := filepath.Join(home, "from-env")
	t.Setenv("DSB_REPO_DIR", fromEnv)
	if got, source, err := resolveRepoDir(""); err != nil || got != fromEnv || source != "DSB_REPO_DIR" {
		t.Errorf("环境变量优先于配置文件,得到 %q %q %v", got, source, err)
	}
}

// TestPortFromBaseURL:自动拉起要用客户端连的那个端口。
func TestPortFromBaseURL(t *testing.T) {
	for _, item := range []struct {
		baseURL  string
		expected int
	}{
		{"http://localhost:10049", 10049},
		{"http://127.0.0.1:12345", 12345},
		{"http://localhost", 80},
	} {
		if got := portFromBaseURL(item.baseURL); got != item.expected {
			t.Errorf("portFromBaseURL(%q) = %d,期望 %d", item.baseURL, got, item.expected)
		}
	}
}

// TestShouldAutoStart:只有「本机 + 连不上」才触发自动拉起;超时与业务失败都不触发。
func TestShouldAutoStart(t *testing.T) {
	client := &Client{AutoStart: true, LocalTarget: true}
	if !client.shouldAutoStart(transportErrorf("连不上 http://x:connection refused(服务起了吗?地址对吗?)")) {
		t.Error("连不上时应触发自动拉起")
	}
	if client.shouldAutoStart(transportErrorf("请求超时 http://x(超过 300 秒)")) {
		t.Error("超时说明服务在跑,不该触发自动拉起")
	}
	if client.shouldAutoStart(usageErrorf("参数写错了")) {
		t.Error("用法错不该触发自动拉起")
	}
	// --no-auto-start
	off := &Client{AutoStart: false, LocalTarget: true}
	if off.shouldAutoStart(transportErrorf("连不上 http://x")) {
		t.Error("关掉自动拉起后不该触发")
	}
	// 只触发一次
	once := &Client{AutoStart: true, LocalTarget: true, autoStarted: true}
	if once.shouldAutoStart(transportErrorf("连不上 http://x")) {
		t.Error("已经自动拉起过一次,不该再来一次")
	}
}

// TestServerStatusJSONShape:status --json 的字段是稳定的(脚本要按它取值)。
func TestServerStatusJSONShape(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("DSB_REPO_DIR", "")

	// 指向一个空的临时仓库:健康检查会失败,但结构仍在
	repo := filepath.Join(home, "repo")
	if err := os.MkdirAll(filepath.Join(repo, "playwright-server"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repo, "pom.xml"), []byte("<project/>"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := saveConfig(&Config{RepoDir: repo}); err != nil {
		t.Fatal(err)
	}

	// 挑一个几乎肯定没人监听的端口,免得撞上真在跑的服务
	port := 6553
	build := &strings.Builder{}
	args := &Args{JSON: true, Port: &port}
	printer := &Printer{Mode: "json", Out: build, ErrOut: build}
	if code, err := cmdServerStatus(args, printer, port); err != nil || code != ExitOK {
		t.Fatalf("cmdServerStatus = %d, %v", code, err)
	}
	value, err := DecodeJSON(build.String())
	if err != nil {
		t.Fatalf("status --json 不是合法 JSON:%q", build.String())
	}
	for _, key := range []string{"baseUrl", "port", "healthy", "repoDir", "pidFile", "outLog"} {
		if !asObj(value).Has(key) {
			t.Errorf("status --json 缺字段 %s:%s", key, build.String())
		}
	}
	if healthy, _ := objGet(value, "healthy").(bool); healthy {
		t.Errorf("端口 %d 不该是健康的", port)
	}
}

// ------------------------------------------------------------------ 测试替身

// stubBase 实现 CommandAPI 的全部方法,返回同一份可配置的响应。
// 具体测试只需内嵌它、覆盖自己关心的一两个方法。
type stubBase struct {
	data  Value
	okSet bool
	ok    bool
}

func (s *stubBase) stubResponse() *Response {
	ok := true
	if s.okSet {
		ok = s.ok
	}
	return &Response{URL: "test", Status: 200,
		Envelope: ObjOf("ok", ok, "data", s.data), Raw: "", ElapsedMs: 1}
}

func (s *stubBase) Command(method string, params Value, taskID *int, timeout float64,
	label string) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) Batch(commands []Value, stopOnError, stopOnExpectFailure, asynchronous bool,
	maxDurationMs int, timeout float64, taskID *int) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) Health() (*Response, error)  { return s.stubResponse(), nil }
func (s *stubBase) Methods() (*Response, error) { return s.stubResponse(), nil }
func (s *stubBase) Config() (*Response, error)  { return s.stubResponse(), nil }
func (s *stubBase) Tasks() (*Response, error)   { return s.stubResponse(), nil }
func (s *stubBase) Start(browser string, headless bool, taskID *int) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) Close(taskID *int) (*Response, error) { return s.stubResponse(), nil }
func (s *stubBase) Shutdown() (*Response, error)         { return s.stubResponse(), nil }
func (s *stubBase) Upload(path, filename string) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) Uploads() (*Response, error) { return s.stubResponse(), nil }
func (s *stubBase) DeleteUpload(name string) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) Job(jobID string, includeResult bool) (*Response, error) {
	return s.stubResponse(), nil
}
func (s *stubBase) WaitJob(jobID string, poll, timeout float64,
	onTick func(*Response)) (*Response, error) {
	return s.stubResponse(), nil
}

// stubClient 是返回固定数据的替身。
type stubClient struct{ stubBase }

// recordingClient 记下最后一次命令的 params。
type recordingClient struct {
	stubBase
	lastParams Value
}

func (r *recordingClient) Command(method string, params Value, taskID *int, timeout float64,
	label string) (*Response, error) {
	r.lastParams = params
	return &Response{URL: "http://x", Status: 200,
		Envelope: ObjOf("data", ObjOf("result", numberFromInt(1)), "ok", true,
			"code", numberFromInt(1)), Raw: "{}", ElapsedMs: 5}, nil
}

var _ CommandAPI = (*stubClient)(nil)
var _ CommandAPI = (*recordingClient)(nil)

// ------------------------------------------------------------------ 小工具

func nonEmptyLines(text string) []string {
	out := []string{}
	for _, line := range strings.Split(text, "\n") {
		if strings.TrimSpace(line) != "" {
			out = append(out, strings.TrimRight(line, "\r"))
		}
	}
	return out
}

func jsonNumber(text string) Value {
	value, _ := DecodeJSON(text)
	return value
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func mustUsage(t *testing.T, fn func() error) (error, bool) {
	t.Helper()
	err := fn()
	_, ok := asUsage(err)
	return err, ok
}

var _ io.Writer = (*strings.Builder)(nil)
