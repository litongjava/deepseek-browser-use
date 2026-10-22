package main

import (
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
