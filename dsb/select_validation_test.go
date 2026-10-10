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
	if err != nil || !handled {
		t.Fatalf("prepare: handled=%v err=%v", handled, err)
	}
	raw, err := os.ReadFile(target)
	if err != nil || !strings.Contains(string(raw), "retained") || output.Len() != 0 {
		t.Fatalf("out precedence: file=%s stdout=%q err=%v", raw, output.String(), err)
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
