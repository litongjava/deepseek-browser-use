package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestObservationOptionsWireContract(t *testing.T) {
	for _, tc := range []struct {
		argv []string
		want string
	}{
		{[]string{"state", "--text-only"}, `{"highlight":false,"screenshot":false,"includeElements":false}`},
		{[]string{"state", "--highlight", "--screenshot"}, `{"highlight":true,"screenshot":true,"includeElements":true}`},
		{[]string{"close", "--close-browser"}, `{"closeBrowser":true}`},
		{[]string{"run", "go_to_url", "-p", "url=https://example.com", "--wait-until", "commit"}, `{"url":"https://example.com","waitUntil":"commit"}`},
	} {
		t.Run(tc.argv[0]+tc.want, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				body, _ := io.ReadAll(r.Body)
				value, err := DecodeJSON(string(body))
				if err != nil {
					t.Error(err)
				}
				want, _ := DecodeJSON(tc.want)
				if EncodeJSON(objGet(value, "params"), encDefault) != EncodeJSON(want, encDefault) {
					t.Errorf("request: %s; want params %s", body, tc.want)
				}
				io.WriteString(w, `{"ok":true,"data":{"text":"ready"}}`)
			}))
			defer server.Close()
			argv := append([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "--json"}, tc.argv...)
			if code := run(argv); code != ExitOK || calls != 1 {
				t.Fatalf("exit=%d requests=%d", code, calls)
			}
		})
	}
}

func TestServiceProfilesAreIsolated(t *testing.T) {
	root := t.TempDir()
	primary := ServerLayout{RepoDir: root, Port: DefaultPort}
	second := ServerLayout{RepoDir: root, Port: DefaultPort + 1}
	if primary.profileDir() != filepath.Join(root, ".dsb-backend", "profile") {
		t.Fatal("default service must retain its existing profile")
	}
	if primary.profileDir() == second.profileDir() {
		t.Fatal("different services must not share a default profile")
	}
	if second.profileDir() != (ServerLayout{RepoDir: root, Port: DefaultPort + 1}).profileDir() {
		t.Fatal("restarting a service must retain its profile")
	}
}
