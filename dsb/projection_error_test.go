package main

import (
	"strings"
	"testing"
)

func TestProjectionKeepsBusinessFailureVisible(t *testing.T) {
	for _, flags := range [][]string{
		{"--select", "data.result"},
		{"--select", "data.errorCode"},
		{"--fields", "result=data.result", "--fields", "code=data.errorCode"},
		{"--select", "data.result", "--select-lenient"},
	} {
		args, err := parseArgs(append([]string{"js", "1+1"}, flags...))
		if err != nil {
			t.Fatal(err)
		}
		stdout, stderr := &strings.Builder{}, &strings.Builder{}
		printer := NewPrinter(args)
		printer.Out, printer.ErrOut = stdout, stderr
		response := &Response{Envelope: ObjOf("ok", false, "msg", `missing task {"token":"secret-value"}`,
			"data", ObjOf("errorCode", "TASK_NOT_FOUND", "actionStatus", "not-started", "hint", "Read list_tasks"))}
		printer.Response(response, "execute_js", false, 0, false, nil)
		if code := report(ExitBusiness, nil, printer); code != ExitBusiness {
			t.Fatalf("business exit changed: %d", code)
		}
		for _, want := range []string{"TASK_NOT_FOUND", "missing task", "Read list_tasks", "[REDACTED]"} {
			if !strings.Contains(stderr.String(), want) {
				t.Fatalf("missing %q: %s", want, stderr)
			}
		}
		if strings.Contains(stderr.String(), "secret-value") || strings.Count(stderr.String(), "Command failed") != 1 {
			t.Fatalf("unsafe or duplicate diagnostic: %s", stderr)
		}
		if _, err := DecodeJSON(stdout.String()); err != nil {
			t.Fatalf("stdout no longer contains only projected JSON: %s", stdout)
		}
		if !args.SelectLenient && strings.Contains(stdout.String(), "missing task") {
			t.Fatal("error summary must not leak into projected stdout")
		}
	}
}

func TestProjectedErrorSummaryIsBoundedAndSuccessIsSilent(t *testing.T) {
	path := "data.result"
	stdout, stderr := &strings.Builder{}, &strings.Builder{}
	printer := &Printer{Select: &path, Out: stdout, ErrOut: stderr}
	printer.JSON(ObjOf("ok", true, "data", ObjOf("result", 2)))
	if stderr.Len() != 0 {
		t.Fatal("success emitted a failure warning")
	}
	printer.JSON(ObjOf("ok", false, "msg", strings.Repeat("长", 4000),
		"data", ObjOf("result", nil, "errorCode", "FAILED", "hint", strings.Repeat("h", 4000))))
	if len([]rune(stderr.String())) > 850 || !strings.Contains(stderr.String(), "truncated") {
		t.Fatalf("unbounded summary: %d characters", len([]rune(stderr.String())))
	}
}
