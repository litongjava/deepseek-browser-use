package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const syntaxFailEnvelope = `{"ok":false,"code":0,"msg":"执行 JavaScript 失败：SyntaxError: Unexpected token ';'","data":{"errorCode":"SCRIPT_SYNTAX_ERROR","actionStatus":"not-started","retrySafe":true}}`

const unknownMethodEnvelope = `{"ok":false,"code":400,"msg":"[INVALID_PARAMS] set_viewport_size: unsupported method; suggestions=[set_viewport]","data":{"actionStatus":"not-started","retrySafe":true,"stage":"validation"}}`

func runWithStreams(t *testing.T, envelope string, tail ...string) (int, string, string) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, envelope)
	}))
	defer server.Close()
	args, err := parseArgs(append([]string{"--base-url", server.URL, "--no-auto-start", "--no-record"}, tail...))
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
	return report(code, err, printer), stdout.String(), stderr.String()
}

func TestSelectOnFailedCommandReportsTheFailure(t *testing.T) {
	code, stdout, stderr := runWithStreams(t, syntaxFailEnvelope, "js", "a; b", "--select", "data.result", "--raw")
	if code != ExitBusiness {
		t.Fatalf("a failed command exits %d, got %d", ExitBusiness, code)
	}
	if !strings.Contains(stderr, "Command failed") || !strings.Contains(stderr, "SyntaxError") {
		t.Fatalf("stderr must name the failure: %q", stderr)
	}
	if strings.Count(stderr, "SyntaxError") != 1 {
		t.Fatalf("the failure message is printed once: %q", stderr)
	}
	if !strings.Contains(stderr, "--select data.result is absent") {
		t.Fatalf("stderr must say why the selection is empty: %q", stderr)
	}
	if strings.Contains(stderr, "may have completed") {
		t.Fatalf("a failed command must not read as a completed one: %q", stderr)
	}
	if strings.TrimSpace(stdout) != "null" {
		t.Fatalf("stdout stays valid JSON: %q", stdout)
	}
}

func TestFieldsOnFailedCommandReportTheFailure(t *testing.T) {
	_, _, stderr := runWithStreams(t, syntaxFailEnvelope, "js", "a; b", "--fields", "v=data.result")
	if !strings.Contains(stderr, "Command failed") || !strings.Contains(stderr, "SyntaxError") {
		t.Fatalf("stderr must name the failure: %q", stderr)
	}
}

func TestFailureSummaryLeadsWithTheMessage(t *testing.T) {
	_, stdout, _ := runWithStreams(t, unknownMethodEnvelope, "run", "set_viewport_size", "--summary")
	line := strings.TrimSpace(stdout)
	if !strings.HasPrefix(line, "set_viewport_size FAIL ") {
		t.Fatalf("unexpected summary: %q", line)
	}
	msg := strings.Index(line, "msg=")
	status := strings.Index(line, "actionStatus=")
	if msg < 0 || status < 0 || msg > status {
		t.Fatalf("the message, with its suggestion, must come before the status fields: %q", line)
	}
	if !strings.Contains(line[:msg+120], "suggestions=[set_viewport]") {
		t.Fatalf("a truncated line must still show the suggestion: %q", line)
	}
}
