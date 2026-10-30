package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestSelectListRejectedBeforeRequest(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		io.WriteString(w, "{}")
	}))
	defer server.Close()
	for _, options := range [][]string{
		{"--select", "data.title,data.url"},
		{"--select=data.title, data.url"},
		{"--select=data.results.0.data.title,data.results.1.data.url"},
		{"--select", "data.title,data.url", "--select", "data"},
		{"--select", "data.title,data.url", "--out", filepath.Join(t.TempDir(), "response.json")},
	} {
		argv := append([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "run", "go_to_url", "-p", "url=https://example.com"}, options...)
		if code := run(argv); code != ExitUsage {
			t.Errorf("run(%v) = %d, want %d", options, code, ExitUsage)
		}
	}
	if got := requests.Load(); got != 0 {
		t.Fatalf("invalid selection sent %d requests", got)
	}
}

func TestSelectPathCompatibility(t *testing.T) {
	for _, path := range []string{"data", "data.results.0.data.changed", "data.a,b", "data.*", "data.fields[0]", "data..value", ""} {
		if _, err := parseArgs([]string{"run", "get_title", "--select", path}); err != nil {
			t.Errorf("literal path %q rejected: %v", path, err)
		}
	}
	path := "data.a,b.c"
	source := ObjOf("data", ObjOf("a,b", ObjOf("c", "literal")))
	if _, err := parseArgs([]string{"run", "get_title", "--select", path}); err == nil {
		t.Fatal("ambiguous dotted comma path must require lenient mode")
	}
	args, err := parseArgs([]string{"run", "get_title", "--select", path, "--select-lenient"})
	if err != nil {
		t.Fatal(err)
	}
	output, diagnostics := &strings.Builder{}, &strings.Builder{}
	printer := NewPrinter(args)
	printer.Out, printer.ErrOut = output, diagnostics
	printer.JSON(source)
	if strings.TrimSpace(output.String()) != `"literal"` || diagnostics.Len() != 0 {
		t.Fatalf("literal selection: stdout=%q stderr=%q", output.String(), diagnostics.String())
	}
	got, err := selectField(ObjOf("data", ObjOf("a,b", "literal")), "data.a,b")
	if err != nil || got != "literal" {
		t.Fatalf("simple comma key: %v, %v", got, err)
	}
}

func TestSelectMissWarnsAgainstReplay(t *testing.T) {
	for _, lenient := range []bool{false, true} {
		path := "data.missing"
		output, diagnostics := &strings.Builder{}, &strings.Builder{}
		printer := &Printer{Mode: "json", Out: output, ErrOut: diagnostics, Select: &path, SelectLenient: lenient}
		printer.JSON(ObjOf("ok", true, "data", ObjOf("title", "done")))
		for _, want := range []string{"after the response", "may have completed", "Do not repeat", "dsb last", "same session"} {
			if !strings.Contains(diagnostics.String(), want) {
				t.Errorf("missing %q in %q", want, diagnostics.String())
			}
		}
		want := ExitUsage
		if lenient {
			want = ExitOK
		}
		if code := report(ExitOK, nil, printer); code != want {
			t.Errorf("lenient=%v: exit=%d, want %d", lenient, code, want)
		}
		if code := report(ExitBusiness, nil, printer); code != ExitBusiness {
			t.Errorf("business failure changed: %d", code)
		}
	}
}

func TestSelectPreservesOutPrecedence(t *testing.T) {
	path, target := "data.title", filepath.Join(t.TempDir(), "response.json")
	args := &Args{Select: &path, Out: &target}
	output, diagnostics := &strings.Builder{}, &strings.Builder{}
	printer := NewPrinter(args)
	printer.Out, printer.ErrOut = output, diagnostics
	response := &Response{Envelope: ObjOf("ok", true, "data", ObjOf("title", "done", "other", "retained"))}
	_, handled, err := prepare(response, args, printer)
	if err != nil || handled {
		t.Fatalf("prepare: handled=%v err=%v (投影还要由调用方打到 stdout)", handled, err)
	}
	raw, err := os.ReadFile(target)
	if err != nil || !strings.Contains(string(raw), "retained") || output.Len() != 0 {
		t.Fatalf("out precedence: file=%s stdout=%q err=%v", raw, output.String(), err)
	}
	if !strings.Contains(diagnostics.String(), "已写入") {
		t.Fatalf("写文件确认应走 stderr: %q", diagnostics.String())
	}
}

// outProjectionCase 用真实的参数解析 + 替身客户端跑一条子命令,返回退出码、stdout、stderr 与文件内容。
func outProjectionCase(t *testing.T, client CommandAPI, command func(CommandAPI, *Args, *Printer) (int, error),
	argv ...string) (int, string, string, string) {
	t.Helper()
	target := filepath.Join(t.TempDir(), "out.json")
	args, err := parseArgs(append(argv, "--out", target))
	if err != nil {
		t.Fatalf("parseArgs(%v): %v", argv, err)
	}
	output, diagnostics := &strings.Builder{}, &strings.Builder{}
	printer := NewPrinter(args)
	printer.Out, printer.ErrOut = output, diagnostics
	code, err := command(client, args, printer)
	code = report(code, err, printer)
	raw, readErr := os.ReadFile(target)
	if readErr != nil {
		t.Fatalf("--out 文件没写出来: %v", readErr)
	}
	return code, output.String(), diagnostics.String(), string(raw)
}

func TestOutWithSelectPrintsSelection(t *testing.T) {
	client := &stubClient{stubBase{data: ObjOf("title", "页面标题", "other", "retained")}}
	code, stdout, stderr, file := outProjectionCase(t, client, cmdRun, "run", "get_title", "--select", "data.title")
	if code != ExitOK || strings.TrimSpace(stdout) != `"页面标题"` {
		t.Fatalf("exit=%d stdout=%q", code, stdout)
	}
	if !strings.Contains(file, "retained") || !strings.Contains(stderr, "已写入") {
		t.Fatalf("file=%q stderr=%q", file, stderr)
	}
}

func TestOutWithFieldsPrintsFieldsObject(t *testing.T) {
	client := &stubClient{stubBase{data: ObjOf("title", "页面标题", "other", "retained")}}
	for name, command := range map[string]func(CommandAPI, *Args, *Printer) (int, error){
		"run": cmdRun, "state": cmdState, "js": cmdJS,
	} {
		argv := []string{name}
		switch name {
		case "run":
			argv = append(argv, "get_title")
		case "js":
			argv = append(argv, "document.title")
		}
		argv = append(argv, "--fields", "t=data.title", "--fields", "ok=ok")
		code, stdout, _, file := outProjectionCase(t, client, command, argv...)
		value, err := DecodeJSON(stdout)
		if err != nil || code != ExitOK {
			t.Fatalf("%s: exit=%d stdout 不是纯 JSON: %q (%v)", name, code, stdout, err)
		}
		if objStr(value, "t") != "页面标题" || objGet(value, "ok") != true {
			t.Errorf("%s: fields 投影不对: %q", name, stdout)
		}
		if !strings.Contains(file, "retained") {
			t.Errorf("%s: 文件应是整封: %q", name, file)
		}
	}
}

func TestOutWithMissingPathStillExitsUsage(t *testing.T) {
	client := &stubClient{stubBase{data: ObjOf("title", "页面标题")}}
	code, stdout, _, _ := outProjectionCase(t, client, cmdRun, "run", "get_title", "--select", "data.missing")
	if code != ExitUsage || strings.TrimSpace(stdout) != "null" {
		t.Errorf("--select 打偏: exit=%d stdout=%q", code, stdout)
	}
	code, stdout, stderr, _ := outProjectionCase(t, client, cmdRun, "run", "get_title", "--fields", "m=data.missing")
	if code != ExitUsage || !strings.Contains(stdout, `"m": null`) || !strings.Contains(stderr, "Missing output field") {
		t.Errorf("--fields 打偏: exit=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	// 失败响应:退出码保持业务失败,投影照打
	failing := &stubClient{stubBase{data: ObjOf("title", "页面标题"), okSet: true, ok: false}}
	code, stdout, _, _ = outProjectionCase(t, failing, cmdRun, "run", "get_title", "--select", "data.title")
	if code != ExitBusiness || strings.TrimSpace(stdout) != `"页面标题"` {
		t.Errorf("失败响应: exit=%d stdout=%q", code, stdout)
	}
}

func TestOutAloneUnchanged(t *testing.T) {
	client := &stubClient{stubBase{data: ObjOf("title", "页面标题")}}
	code, stdout, stderr, file := outProjectionCase(t, client, cmdRun, "run", "get_title")
	lines := nonEmptyLines(stdout)
	if code != ExitOK || len(lines) != 1 || !strings.HasPrefix(lines[0], "已写入 ") || stderr != "" {
		t.Errorf("--out 单用: exit=%d stdout=%q stderr=%q", code, stdout, stderr)
	}
	if !strings.Contains(file, "页面标题") {
		t.Errorf("file=%q", file)
	}
	// --json --out 不带投影:维持现状(stdout 不打中文确认)
	code, stdout, _, _ = outProjectionCase(t, client, cmdRun, "run", "get_title", "--json")
	if code != ExitOK || stdout != "" {
		t.Errorf("--json --out: exit=%d stdout=%q", code, stdout)
	}
}

func TestTaskIDSourceDiagnostics(t *testing.T) {
	t.Setenv("DSB_TASK_ID", "task-label")
	base := "http://127.0.0.1:1"
	for _, source := range []string{"DSB_TASK_ID", "--id"} {
		args := &Args{BaseURL: &base, NoRecord: true, NoAutoStart: true}
		if source == "--id" {
			id := "task-label"
			args.ID = &id
		}
		_, err := buildClient(args)
		if err == nil || !strings.Contains(err.Error(), source) || !strings.Contains(err.Error(), "--session") {
			t.Errorf("%s diagnostic: %v", source, err)
		}
	}
	id := "1001"
	client, err := buildClient(&Args{BaseURL: &base, ID: &id, NoRecord: true, NoAutoStart: true})
	if err != nil || client.TaskID != 1001 {
		t.Fatalf("explicit numeric ID must override invalid environment: %v", err)
	}
}

func TestMethodsFilterRemainsListingOnly(t *testing.T) {
	args, err := parseArgs([]string{"methods", "click"})
	if err != nil || args.Command != "methods" || args.Filter == nil || *args.Filter != "click" || args.Method != nil {
		t.Fatalf("methods filter: %+v, %v", args, err)
	}
	if _, err := parseArgs([]string{"methods", "--method", "click"}); err == nil {
		t.Fatal("unsupported --method must remain a usage error")
	}
}
