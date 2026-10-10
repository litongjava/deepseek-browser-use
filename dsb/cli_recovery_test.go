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

func TestJSBodyDiagnosticBeforeRequest(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		io.WriteString(w, "{}")
	}))
	defer server.Close()
	for _, options := range [][]string{{"--body", "() => 1"}, {"--body=() => 1"}, {"() => 1", "--body", "() => 2"}} {
		argv := append([]string{"--base-url", server.URL, "--no-auto-start", "--no-record", "js"}, options...)
		_, err := parseArgs(argv)
		if err == nil {
			t.Fatal("--body must remain unsupported")
		}
		for _, want := range []string{"unrecognized arguments: --body", "positional SCRIPT", "dsb js '@script.js'", "PowerShell", "No request was sent"} {
			if !strings.Contains(err.Error(), want) {
				t.Errorf("missing %q in %v", want, err)
			}
		}
		if code := run(argv); code != ExitUsage {
			t.Errorf("exit = %d, want %d", code, ExitUsage)
		}
	}
	if requests.Load() != 0 {
		t.Fatalf("invalid JS invocation sent %d requests", requests.Load())
	}
	// The option terminator must still permit literal scripts that resemble options.
	args, err := parseArgs([]string{"js", "--", "--body"})
	if err != nil || args.Script == nil || *args.Script != "--body" {
		t.Fatalf("literal script: %+v, %v", args, err)
	}
	args, err = parseArgs([]string{"run", "execute_js", "-p", "body=() => 1"})
	if err != nil || args.Method == nil || *args.Method != "execute_js" {
		t.Fatalf("run alternative: %+v, %v", args, err)
	}
}

func TestJSScriptFileWithSpacesAndBOM(t *testing.T) {
	path := filepath.Join(t.TempDir(), "中文 script.js")
	body := "() => {\n  return '北京';\n}\n"
	if err := os.WriteFile(path, []byte("\ufeff"+body), 0o600); err != nil {
		t.Fatal(err)
	}
	args, err := parseArgs([]string{"js", "@" + path, "--var", "city=北京"})
	if err != nil {
		t.Fatal(err)
	}
	fake := &recordingClient{}
	printer := &Printer{Mode: "json", Out: io.Discard, ErrOut: io.Discard}
	code, err := cmdJS(fake, args, printer)
	if err != nil || code != ExitOK {
		t.Fatalf("cmdJS: %d, %v", code, err)
	}
	if got := objStr(fake.lastParams, "body"); got != body {
		t.Fatalf("body = %q, want %q", got, body)
	}
	if got := objStr(objGet(fake.lastParams, "vars"), "city"); got != "北京" {
		t.Fatalf("city = %q", got)
	}
	if asObj(fake.lastParams).Has("retryOnSpurious") {
		t.Fatal("script execution must not enable retries implicitly")
	}
}

func TestJSMissingScriptFileDoesNotSend(t *testing.T) {
	source := "@" + filepath.Join(t.TempDir(), "missing script.js")
	fake := &recordingClient{}
	printer := &Printer{Mode: "json", Out: io.Discard, ErrOut: io.Discard}
	code, err := cmdJS(fake, &Args{Script: &source}, printer)
	if got := report(code, err, printer); got != ExitUsage {
		t.Fatalf("exit = %d", got)
	}
	if fake.lastParams != nil {
		t.Fatal("missing script file must not send a command")
	}
}

func TestSelectFailureRecordsResponseAndLastDoesNotResend(t *testing.T) {
	for _, businessFailure := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "business-failure"}[businessFailure], func(t *testing.T) {
			var requests atomic.Int32
			envelope := `{"ok":true,"code":1,"data":{"title":"done","result":"retained"}}`
			wantExit := ExitUsage
			if businessFailure {
				envelope = `{"ok":false,"code":0,"msg":"test failure","data":{"title":"done","result":"retained"}}`
				wantExit = ExitBusiness
			}
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				io.WriteString(w, envelope)
			}))
			defer server.Close()
			common := []string{"--base-url", server.URL, "--id", "1001", "--no-auto-start", "--record-dir", t.TempDir(), "--session", "recovery"}
			execute := func(tail ...string) (int, string, string, *Client) {
				t.Helper()
				argv := append(append([]string{}, common...), tail...)
				args, err := parseArgs(argv)
				if err != nil {
					t.Fatal(err)
				}
				client, err := buildClient(args)
				if err != nil {
					t.Fatal(err)
				}
				stdout, stderr := &strings.Builder{}, &strings.Builder{}
				printer := NewPrinter(args)
				printer.Out, printer.ErrOut = stdout, stderr
				code, err := dispatch(client, args, printer)
				return report(code, err, printer), stdout.String(), stderr.String(), client
			}
			code, stdout, stderr, client := execute("run", "click_element_by_index", "-p", "index=0", "--select", "data.missing")
			if code != wantExit || strings.TrimSpace(stdout) != "null" {
				t.Fatalf("exit=%d stdout=%q", code, stdout)
			}
			if !strings.Contains(stderr, "Do not repeat") {
				t.Fatalf("missing replay warning: %s", stderr)
			}
			recordPath := filepath.Join(client.RecordDir, "001.res.json")
			before, err := os.ReadFile(recordPath)
			if err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(string(before), "retained") {
				t.Fatal("selection must not truncate the stored response")
			}
			code, stdout, _, _ = execute("last")
			// last reports successful local reading even if the recorded command failed.
			if code != ExitOK || !strings.Contains(stdout, "retained") {
				t.Fatalf("last: exit=%d stdout=%q", code, stdout)
			}
			code, stdout, _, _ = execute("last", "--select", "data.title")
			if code != ExitOK || strings.TrimSpace(stdout) != `"done"` {
				t.Fatalf("selected last: exit=%d stdout=%q", code, stdout)
			}
			if got := requests.Load(); got != 1 {
				t.Fatalf("expected one action and no replay requests, got %d", got)
			}
			after, err := os.ReadFile(recordPath)
			if err != nil || string(before) != string(after) {
				t.Fatalf("last changed original record: %v", err)
			}
			if _, err := os.Stat(filepath.Join(client.RecordDir, "002.res.json")); !os.IsNotExist(err) {
				t.Fatalf("last must not create a new response record: %v", err)
			}
		})
	}
}
