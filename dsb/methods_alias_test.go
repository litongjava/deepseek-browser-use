package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestListMethodsMatchesMethods(t *testing.T) {
	for _, failure := range []bool{false, true} {
		envelope := `{"ok":true,"data":{"methods":["start","click_element_by_index","mouse_click"]}}`
		wantCode := ExitOK
		if failure {
			envelope = `{"ok":false,"msg":"listing failed"}`
			wantCode = ExitBusiness
		}
		requests := 0
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			requests++
			if r.Method != http.MethodGet || r.URL.Path != pathMethods {
				t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
			}
			io.WriteString(w, envelope)
		}))
		for _, options := range [][]string{nil, {"CLICK"}, {"click", "--json"}, {"missing", "--compact"}, {"--select", "ok"}, {"--grep", "click"}, {"--out", filepath.Join(t.TempDir(), "methods.json")}} {
			var wantOutput, wantFile string
			for _, command := range []string{"methods", "list_methods"} {
				argv := append([]string{"--base-url", server.URL, "--id", "1001", "--session", "methods-alias-test", "--no-auto-start", "--no-record", command}, options...)
				args, err := parseArgs(argv)
				if err != nil {
					t.Fatal(err)
				}
				client, err := buildClient(args)
				if err != nil {
					t.Fatal(err)
				}
				stdout := &strings.Builder{}
				printer := NewPrinter(args)
				printer.Out, printer.ErrOut = stdout, io.Discard
				code, err := dispatch(client, args, printer)
				if got := report(code, err, printer); got != wantCode {
					t.Fatalf("%s %v: exit=%d, want %d", command, options, got, wantCode)
				}
				file := ""
				if args.Out != nil {
					raw, err := os.ReadFile(*args.Out)
					if err != nil {
						t.Fatal(err)
					}
					file = string(raw)
				}
				if command == "methods" {
					wantOutput, wantFile = stdout.String(), file
				} else if stdout.String() != wantOutput || file != wantFile {
					t.Fatalf("alias output differs for %v: %q vs %q", options, stdout.String(), wantOutput)
				}
			}
		}
		server.Close()
		if requests != 14 {
			t.Fatalf("got %d listing requests, want 14", requests)
		}
	}
}

func TestListMethodsHelpAndProtocolCompatibility(t *testing.T) {
	_, err := parseArgs([]string{"list_methods", "--help"})
	if help, ok := err.(*helpRequested); !ok || help.Command != "list_methods" {
		t.Fatalf("alias help: %v", err)
	}
	if _, err := parseArgs([]string{"list_methods", "--method", "click"}); err == nil {
		t.Fatal("unsupported --method must remain a usage error")
	}
	args, err := parseArgs([]string{"run", "list_methods"})
	if err != nil || args.Command != "run" || args.Method == nil || *args.Method != "list_methods" {
		t.Fatalf("protocol invocation: %+v, %v", args, err)
	}
	if hint := subcommandMisuseHint([]string{"run", "list_methods"}); hint != "" {
		t.Fatalf("valid protocol method got misuse hint: %s", hint)
	}
}
