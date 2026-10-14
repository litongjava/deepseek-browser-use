package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSensitiveBatchRecordingMasksEchoesAndSteps(t *testing.T) {
	for _, raw := range []string{
		`{"method":"commands","params":{"commands":[{"input_text":{"text":"fixture-secret"}}]}}`,
		`{"method":"commands","params":{"commands":[{"execute_js":{"sensitive":true,"body":"fixture-secret"}}]}}`,
	} {
		directory := t.TempDir()
		client := &Client{Record: true, RecordDir: directory}
		payload, _ := DecodeJSON(raw)
		response := &Response{Envelope: ObjOf("ok", false, "msg", "fixture-secret", "data", ObjOf("result", "fixture-secret"))}
		client.recordCall("commands", payload, response, "")
		for _, name := range []string{"001.req.json", "001.res.json", "steps.log"} {
			body, err := os.ReadFile(filepath.Join(directory, name))
			if err != nil {
				t.Fatal(err)
			}
			if strings.Contains(string(body), "fixture-secret") {
				t.Fatalf("secret in %s: %s", name, body)
			}
		}
		if response.Msg() != "fixture-secret" {
			t.Fatal("live response mutated")
		}
	}
}

func TestRejectMultipleStdinConsumersBeforeSending(t *testing.T) {
	if code := run([]string{"--no-auto-start", "run", "execute_js", "--params", "-", "--readback", "-"}); code != ExitUsage {
		t.Fatalf("exit %d", code)
	}
}

func TestReliabilityWireOptions(t *testing.T) {
	for _, tc := range []struct {
		args   []string
		method string
	}{
		{[]string{"diagnose"}, "get_diagnostics"},
		{[]string{"cancel", "42"}, "cancel_command"},
		{[]string{"run", "click_element_by_role", "-p", "role=button", "--expect", `{"js":"true","truthy":true,"timeoutMs":200}`, "--readback", "document.title", "--command-timeout-ms", "1000", "--sensitive"}, "click_element_by_role"},
	} {
		t.Run(tc.method, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				value, err := DecodeJSON(string(body))
				if err != nil {
					t.Fatal(err)
				}
				if objStr(value, "method") != tc.method {
					t.Errorf("unexpected method: %s", body)
				}
				params := objGet(value, "params")
				if tc.method == "cancel_command" && objStr(params, "commandId") != "42" {
					t.Error("command ID missing")
				}
				if tc.method == "click_element_by_role" {
					if objStr(objGet(params, "expect"), "js") != "true" || objStr(params, "readback") != "document.title" || objGet(params, "sensitive") != true {
						t.Errorf("options lost: %s", body)
					}
				}
				io.WriteString(w, `{"ok":true,"data":{"ready":true}}`)
			}))
			defer server.Close()
			args := append([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "--json"}, tc.args...)
			if code := run(args); code != ExitOK {
				t.Fatalf("exit %d", code)
			}
		})
	}
}

func TestCompactIncludesStructuredResult(t *testing.T) {
	var out bytes.Buffer
	printer := &Printer{Mode: "compact", Out: &out, ErrOut: io.Discard}
	response := &Response{Envelope: ObjOf("ok", true, "data", ObjOf("seq", numberFromInt(1), "result", ObjOf("saved", true)))}
	printer.Response(response, "execute_js", false, 0, false, nil)
	if !strings.Contains(out.String(), `result={"saved":true}`) {
		t.Fatal(out.String())
	}
}

func TestBOMStandardInputParameters(t *testing.T) {
	file, err := os.CreateTemp(t.TempDir(), "stdin")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	file.WriteString("\ufeff{\"text\":\"中文\"}")
	file.Seek(0, 0)
	old := os.Stdin
	os.Stdin = file
	defer func() { os.Stdin = old }()
	source := "-"
	params, err := loadPayload(&source, nil)
	if err != nil || objStr(params, "text") != "中文" {
		t.Fatalf("params=%v error=%v", params, err)
	}
}

func TestCredentialRecordFields(t *testing.T) {
	for _, raw := range []string{
		`{"headers":{"Authorization":"fixture-secret"},"access_token":"fixture-secret"}`,
		`{"method":"input_text_by_selector","params":{"selector":"#password","text":"fixture-secret"}}`,
		`{"commands":[{"input_text":{"index":1,"text":"fixture-secret"}}]}`,
		`{"method":"execute_js","params":{"sensitive":true,"body":"fixture-secret"}}`,
	} {
		value, _ := DecodeJSON(raw)
		redacted := EncodeJSON(redactCookieRecords(value), encCompact)
		if strings.Contains(redacted, "fixture-secret") {
			t.Fatal(redacted)
		}
		if !strings.Contains(EncodeJSON(value, encCompact), "fixture-secret") {
			t.Fatal("live request mutated")
		}
	}
}
