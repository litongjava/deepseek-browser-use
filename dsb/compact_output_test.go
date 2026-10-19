package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestCompactStartOmitsPrivatePaths(t *testing.T) {
	var output bytes.Buffer
	printer := &Printer{Mode: "compact", Out: &output, ErrOut: &output}
	response := &Response{Envelope: ObjOf("ok", true, "data", ObjOf("id", "42", "engineHonored", true,
		"browser", ObjOf("headless", false, "type", "chrome", "profileDir", "private-profile-path", "note", strings.Repeat("detail", 1000))))}
	printer.Response(response, "start", false, 0, false, nil)
	text := output.String()
	if strings.Contains(text, "private-profile-path") || strings.Contains(text, "detail") || !strings.Contains(text, `"headless":false`) || len(nonEmptyLines(text)) != 1 {
		t.Fatal(text)
	}
}

func TestCompactFallbackBoundedAndJSONUnchanged(t *testing.T) {
	data := ObjOf("tabs", []Value{ObjOf("title", strings.Repeat("中文", 2000))}, "access_token", "fixture-secret")
	response := &Response{Envelope: ObjOf("ok", true, "data", data)}
	var output bytes.Buffer
	printer := &Printer{Mode: "compact", Out: &output, ErrOut: &output}
	printer.Response(response, "get_tabs", false, 0, false, nil)
	if !strings.Contains(output.String(), "truncated") || len([]rune(output.String())) > 1400 || strings.Contains(output.String(), "fixture-secret") {
		t.Fatal("fallback output was not bounded and redacted")
	}
	output.Reset()
	printer.Mode = "json"
	printer.Response(response, "get_tabs", false, 0, false, nil)
	decoded, err := DecodeJSON(output.String())
	if err != nil || EncodeJSON(decoded, encCompact) != EncodeJSON(response.Envelope, encCompact) {
		t.Fatal("explicit JSON output changed")
	}
}

func TestCompactFailureMessageBounded(t *testing.T) {
	var output bytes.Buffer
	printer := &Printer{Mode: "compact", Out: &output, ErrOut: &output}
	response := &Response{Envelope: ObjOf("ok", false, "msg", strings.Repeat("failure\n", 1000),
		"data", ObjOf("execution", ObjOf("commandId", "42", "actionStatus", "unknown")))}
	printer.Response(response, "click", false, 0, false, nil)
	if len(nonEmptyLines(output.String())) != 1 || !strings.Contains(output.String(), `"commandId":"42"`) || !strings.Contains(output.String(), "truncated") {
		t.Fatal(output.String())
	}
}
