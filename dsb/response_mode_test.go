package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCLIResponseModes(t *testing.T) {
	for _, tc := range []struct {
		options     []string
		mode        string
		diagnostics bool
	}{
		{nil, "compact", false},
		{[]string{"--response-mode", "full"}, "full", false},
		{[]string{"--diagnostics"}, "compact", true},
	} {
		t.Run(tc.mode+string(EncodeJSON(tc.diagnostics, encCompact)), func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				body, _ := io.ReadAll(r.Body)
				value, err := DecodeJSON(string(body))
				if err != nil {
					t.Fatal(err)
				}
				if objGet(value, "responseMode") != tc.mode {
					t.Errorf("request: %s", body)
				}
				if got, _ := objGet(value, "diagnostics").(bool); got != tc.diagnostics {
					t.Errorf("diagnostics: %s", body)
				}
				io.WriteString(w, `{"ok":true,"data":{"path":"data/1/shot.png"}}`)
			}))
			defer server.Close()
			argv := append([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "--json", "run", "screenshot"}, tc.options...)
			if code := run(argv); code != ExitOK || calls != 1 {
				t.Fatalf("exit=%d calls=%d", code, calls)
			}
		})
	}
}
