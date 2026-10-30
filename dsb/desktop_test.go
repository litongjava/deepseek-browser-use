package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFieldsPreservePartialFailureAndExitCode(t *testing.T) {
	args, err := parseArgs([]string{"run", "get_url", "--fields", "url=data.url", "--fields", "missing=data.missing"})
	if err != nil {
		t.Fatal(err)
	}
	p := NewPrinter(args)
	output := &strings.Builder{}
	p.Out = output
	p.ErrOut = &strings.Builder{}
	p.JSON(ObjOf("ok", false, "data", ObjOf("url", "http://tauri.localhost/")))
	if !strings.Contains(output.String(), "\"missing\": null") || !strings.Contains(output.String(), "tauri.localhost") {
		t.Fatal(output.String())
	}
	if report(ExitBusiness, nil, p) != ExitBusiness || report(ExitOK, nil, p) != ExitUsage {
		t.Fatal("exit status lost")
	}
	for _, argv := range [][]string{
		{"attach"}, {"run", "get_url", "--fields", "a=data", "--fields", "a=ok"},
		{"run", "get_url", "--fields", "a=data", "--select", "data"},
	} {
		if _, err := parseArgs(argv); err == nil {
			t.Fatalf("accepted %v", argv)
		}
	}
}

func TestReportMissingEvidenceAndRepeatability(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "001.req.json"), []byte("{\"id\":1,\"method\":\"click\"}"), 0600)
	os.WriteFile(filepath.Join(dir, "001.res.json"), []byte("{\"ok\":false,\"data\":{\"actionStatus\":\"unknown\"}}"), 0600)
	os.WriteFile(filepath.Join(dir, "002.req.json"), []byte("{\"id\":1,\"method\":\"screenshot\"}"), 0600)
	output := &strings.Builder{}
	p := &Printer{Mode: "json", Out: output, ErrOut: &strings.Builder{}}
	client := &Client{RecordDir: dir}
	if _, err := cmdReport(client, &Args{}, p); err != nil {
		t.Fatal(err)
	}
	first := output.String()
	if !strings.Contains(first, "\"unknown\": 2") || !strings.Contains(first, "\"evidenceMissing\": true") {
		t.Fatal(first)
	}
	output.Reset()
	if _, err := cmdReport(client, &Args{}, p); err != nil {
		t.Fatal(err)
	}
	if first != output.String() {
		t.Fatal("report changed without new evidence")
	}
}

func TestReportCountsRefusedCommandsAsFailures(t *testing.T) {
	dir := t.TempDir()
	write := func(index, method, response string) {
		os.WriteFile(filepath.Join(dir, index+".req.json"), []byte("{\"id\":1,\"method\":\""+method+"\"}"), 0600)
		os.WriteFile(filepath.Join(dir, index+".res.json"), []byte(response), 0600)
	}
	write("001", "click", "{\"ok\":false,\"msg\":\"[PRECONDITION_FAILED] precondition not met\",\"data\":{\"execution\":{\"actionStatus\":\"not-started\"}}}")
	write("002", "attach", "{\"ok\":false,\"data\":{\"errorCode\":\"EXTERNAL_TARGET_AMBIGUOUS\",\"actionStatus\":\"not-started\"}}")
	write("003", "attach", "{\"ok\":false,\"msg\":\"connect refused\",\"data\":{\"actionStatus\":\"unknown\"}}")
	write("004", "get_title", "{\"ok\":true,\"data\":{\"title\":\"x\"}}")
	write("005", "a|b", "{\"ok\":true,\"data\":{\"actionStatus\":\"not-started\"}}")
	output := &strings.Builder{}
	p := &Printer{Mode: "json", Out: output, ErrOut: &strings.Builder{}}
	markdown := filepath.Join(dir, "report.md")
	if _, err := cmdReport(&Client{RecordDir: dir}, &Args{Markdown: &markdown}, p); err != nil {
		t.Fatal(err)
	}
	report, err := DecodeJSON(output.String())
	if err != nil {
		t.Fatal(err)
	}
	counts := objGet(report, "counts")
	for key, want := range map[string]string{"success": "1", "failure": "2", "skipped": "1", "unknown": "1"} {
		if got := fmt.Sprint(objGet(counts, key)); got != want {
			t.Fatalf("%s=%s want %s: %s", key, got, want, output.String())
		}
	}
	steps := asArray(objGet(report, "steps"))
	for i, want := range [][3]any{
		{"failure", "not-started", "PRECONDITION_FAILED"},
		{"failure", "not-started", "EXTERNAL_TARGET_AMBIGUOUS"},
		{"unknown", "unknown", nil},
		{"success", nil, nil},
		{"skipped", "not-started", nil},
	} {
		if objGet(steps[i], "status") != want[0] || objGet(steps[i], "actionStatus") != want[1] || objGet(steps[i], "errorCode") != want[2] {
			t.Fatalf("step %d: %v", i+1, steps[i])
		}
	}
	text, err := os.ReadFile(markdown)
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range []string{
		"| Step | Method | Status | Action | Evidence |",
		"| 1 | click | failure | not-started | 001.res.json |",
		"| 3 | attach | unknown | unknown | 003.res.json |",
		"| 4 | get_title | success |  | 004.res.json |",
		"| 5 | a&#124;b | skipped | not-started | 005.res.json |",
	} {
		if !strings.Contains(string(text), line) {
			t.Fatalf("missing %q in\n%s", line, text)
		}
	}
}
