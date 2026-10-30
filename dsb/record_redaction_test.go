package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCookieRecordRedaction(t *testing.T) {
	tests := []struct {
		name string
		json string
	}{
		{"get", `{"ok":true,"data":{"cookies":[{"name":"session","value":"fixture-secret","domain":"example.com"}],"count":1}}`},
		{"set", `{"method":"set_cookie","params":{"name":"session","value":"fixture-secret","url":"https://example.com"}}`},
		{"batch", `{"method":"commands","params":{"commands":[{"set_cookie":{"name":"session","value":"fixture-secret"}}]}}`},
		{"nested-job", `{"data":{"result":{"results":[{"command":"get_cookies","data":{"cookies":[{"name":"session","value":"fixture-secret"}]}}]}}}`},
		{"method-batch", `{"commands":[{"method":"set_cookie","params":{"value":"fixture-secret"}}]}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			original, err := DecodeJSON(test.json)
			if err != nil {
				t.Fatal(err)
			}
			before := EncodeJSON(original, encDefault)
			redacted := EncodeJSON(redactCookieRecords(original), encDefault)
			if strings.Contains(redacted, "fixture-secret") || !strings.Contains(redacted, redactedCookieValue) {
				t.Fatal("structured Cookie value was not redacted")
			}
			if EncodeJSON(original, encDefault) != before {
				t.Fatal("redaction changed the live value")
			}
		})
	}
}

func TestCookieRecordPreservesUnrelatedFields(t *testing.T) {
	original := ObjOf("data", ObjOf("value", "visible text", "count", numberFromInt(1),
		"cookies", []Value{ObjOf("name", "session", "value", "", "expires", numberFromInt(-1), "httpOnly", true)}))
	redacted := redactCookieRecords(original)
	if objStr(objGet(redacted, "data"), "value") != "visible text" {
		t.Fatal("unrelated value was changed")
	}
	cookie := asArray(objGet(objGet(redacted, "data"), "cookies"))[0]
	if objStr(cookie, "name") != "session" || objGet(cookie, "httpOnly") != true || objStr(cookie, "value") != redactedCookieValue {
		t.Fatal("Cookie metadata must be preserved")
	}
	for _, value := range []Value{nil, true, "plain", numberFromInt(42), NewObj(), []Value{nil}} {
		if EncodeJSON(value, encDefault) != EncodeJSON(redactCookieRecords(value), encDefault) {
			t.Fatal("non-Cookie value changed")
		}
	}
}

func TestCookieRecordingKeepsLiveExportUsable(t *testing.T) {
	folder := t.TempDir()
	client, err := NewClient(ClientOptions{BaseURL: "http://example.invalid", TaskID: 42,
		Record: true, Session: "cookie-test", RecordDir: folder, Timeout: 30})
	if err != nil {
		t.Fatal(err)
	}
	payload := ObjOf("id", numberFromInt(42), "method", "set_cookie", "params", ObjOf("name", "session", "value", "fixture-request-secret"))
	envelope := ObjOf("ok", true, "data", ObjOf("count", numberFromInt(1), "cookies", []Value{
		ObjOf("name", "session", "value", "fixture-response-secret", "domain", "example.com"),
	}))
	response := &Response{Envelope: envelope, ElapsedMs: 1}
	client.recordCall("set_cookie", payload, response, "")
	for _, name := range []string{"001.req.json", "001.res.json", "steps.log"} {
		text, err := os.ReadFile(filepath.Join(folder, name))
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(text), "fixture-request-secret") || strings.Contains(string(text), "fixture-response-secret") {
			t.Fatal("automatic record leaked a fixture secret")
		}
	}
	if objStr(objGet(payload, "params"), "value") != "fixture-request-secret" {
		t.Fatal("live request changed")
	}
	if !strings.Contains(EncodeJSON(response.Envelope, encDefault), "fixture-response-secret") {
		t.Fatal("live response changed")
	}
}

func TestBodyStringRecordRedaction(t *testing.T) {
	const key = "sk-test-1234567890abcdef"
	tests := []struct {
		name string
		json string
		keep []string
	}{
		{"json-postData", `{"ok":true,"data":{"requests":[{"method":"POST","postData":"{\"id\":\"deepseek\",\"apiKey\":\"sk-test-1234567890abcdef\",\"baseUrl\":\"https://api.example.test\",\"auth\":{\"password\":\"fixture-pass\"}}"}]}}`,
			[]string{`\"id\":\"deepseek\"`, `\"baseUrl\":\"https://api.example.test\"`, `"method": "POST"`}},
		{"form-postData", `{"data":{"requests":[{"postData":"user=alice&api_key=sk-test-1234567890abcdef&pass%77ord=fixture-pass&empty="}]}}`,
			[]string{"user=alice", "api_key=" + redactedCookieValue, "empty="}},
		{"truncated-json", `{"data":{"request":{"postData":"{\"apiKey\":\"sk-test-1234567890abcdef\",\"base"}}}`,
			[]string{`\"base`}},
		{"response-body", `{"data":{"body":"{\"data\":{\"refresh_token\":\"fixture-rt\",\"name\":\"visible\"}}"}}`,
			[]string{"visible"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			original, err := DecodeJSON(test.json)
			if err != nil {
				t.Fatal(err)
			}
			before := EncodeJSON(original, encDefault)
			redacted := EncodeJSON(redactCookieRecords(original), encDefault)
			if strings.Contains(redacted, key) || strings.Contains(redacted, "fixture-") {
				t.Fatalf("credential inside a body string was not redacted: %s", redacted)
			}
			for _, kept := range test.keep {
				if !strings.Contains(redacted, kept) {
					t.Fatalf("non-sensitive content %q was lost: %s", kept, redacted)
				}
			}
			if EncodeJSON(original, encDefault) != before {
				t.Fatal("redaction changed the live value")
			}
		})
	}
}

func TestBodyStringRedactionLeavesPlainText(t *testing.T) {
	for _, text := range []string{"please enter your password = thanks", `{"id":"deepseek","tokenCount":12}`,
		"q=token+bucket&page=2", "plain"} {
		if redactBodyText(text) != text {
			t.Fatalf("plain text changed: %q", text)
		}
	}
}

func TestBodyStringRecordingFiles(t *testing.T) {
	folder := t.TempDir()
	client, err := NewClient(ClientOptions{BaseURL: "http://example.invalid", TaskID: 42,
		Record: true, Session: "body-test", RecordDir: folder, Timeout: 30})
	if err != nil {
		t.Fatal(err)
	}
	payload := ObjOf("id", numberFromInt(42), "method", "get_requests", "params", NewObj())
	postData := `{"id":"deepseek","apiKey":"sk-test-1234567890abcdef"}`
	envelope := ObjOf("ok", true, "data", ObjOf("count", numberFromInt(1), "requests", []Value{
		ObjOf("method", "POST", "url", "https://app.example.test/api/providers", "postData", postData),
	}))
	client.recordCall("get_requests", payload, &Response{Envelope: envelope, ElapsedMs: 1}, "")
	text, err := os.ReadFile(filepath.Join(folder, "001.res.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(text), "sk-test-1234567890abcdef") || !strings.Contains(string(text), "deepseek") {
		t.Fatalf("automatic record leaked or lost body content: %s", text)
	}
	if !strings.Contains(EncodeJSON(envelope, encDefault), "sk-test-1234567890abcdef") {
		t.Fatal("live response changed")
	}
}
