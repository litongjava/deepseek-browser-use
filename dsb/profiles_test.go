package main

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestProfileInvalidArguments(t *testing.T) {
	for _, argv := range [][]string{
		{"start", "--profile="}, {"start", "--profile", "  "},
		{"start", "--user-data-dir="}, {"start", "--profile-directory", "Default"},
		{"start", "--profile", "litongjava", "--user-data-dir", "C:/data"},
		{"start", "--profile", "litongjava", "--profile-directory", "Default"},
		{"start", "--profile", "litongjava", "--browser", "firefox"},
		{"start", "--profile", "litongjava", "--browser", "edge"},
		{"profile"}, {"profile", "delete"}, {"profiles", "list"},
		{"profile", "clone"}, {"profile", "clone", "--name", "litongjava"},
		{"profile", "clone", "--name=", "--source-user-data-dir", "C:/data"},
		{"profile", "clone", "--name", "litongjava", "--source-user-data-dir="},
		{"profile", "clone", "--name", "litongjava", "--source-user-data-dir", "C:/data", "--source-profile-directory="},
		{"profile", "list", "--name", "litongjava"},
	} {
		if _, err := parseArgs(argv); err == nil {
			t.Errorf("accepted invalid arguments %v", argv)
		}
	}
}

// All HTTP endpoints are test servers. No browser or profile files are touched.
func TestProfileCommandWireContract(t *testing.T) {
	for _, tc := range []struct {
		argv      []string
		method    string
		params    string
		preflight bool
	}{
		{[]string{"profiles"}, "list_profiles", "{}", false},
		{[]string{"profile", "list"}, "list_profiles", "{}", false},
		{[]string{"profile", "clone", "--name", "litongjava", "--source-user-data-dir", "C:/User Data"}, "clone_profile", `{"name":"litongjava","sourceUserDataDir":"C:/User Data","sourceProfileDirectory":"Default"}`, false},
		{[]string{"profile", "clone", "--name", "litongjava", "--source-user-data-dir", "C:/User Data", "--source-profile-directory", "Profile 2"}, "clone_profile", `{"name":"litongjava","sourceUserDataDir":"C:/User Data","sourceProfileDirectory":"Profile 2"}`, false},
		{[]string{"start", "--profile", "litongjava", "--headful"}, "start", `{"headless":false,"browser":"chrome","profile":"litongjava"}`, true},
		{[]string{"start", "--browser", "auto", "--user-data-dir", "C:/User Data", "--profile-directory", "Profile 2"}, "start", `{"headless":true,"browser":"chrome","userDataDir":"C:/User Data","profileDirectory":"Profile 2"}`, true},
	} {
		t.Run(strings.Join(tc.argv, " "), func(t *testing.T) {
			requests, commands := 0, 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests++
				if r.Method == http.MethodGet {
					io.WriteString(w, `{"ok":true,"data":{"methods":["start","list_profiles","clone_profile"]}}`)
					return
				}
				commands++
				body, err := io.ReadAll(r.Body)
				if err != nil {
					t.Error(err)
				}
				value, err := DecodeJSON(string(body))
				if err != nil {
					t.Error(err)
				}
				if objStr(value, "method") != tc.method {
					t.Errorf("method: %s", string(body))
				}
				want, _ := DecodeJSON(tc.params)
				if EncodeJSON(objGet(value, "params"), encDefault) != EncodeJSON(want, encDefault) {
					t.Errorf("params: %s; want %s", string(body), tc.params)
				}
				io.WriteString(w, `{"ok":true,"data":{"profiles":[],"profile":{"name":"litongjava"},"copiedFiles":2,"copiedBytes":10,"warning":"Login is not guaranteed"}}`)
			}))
			defer server.Close()
			argv := append([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "--json"}, tc.argv...)
			if code := run(argv); code != ExitOK {
				t.Errorf("exit = %d", code)
			}
			wantRequests := 1
			if tc.preflight {
				wantRequests++
			}
			if requests != wantRequests || commands != 1 {
				t.Errorf("requests=%d commands=%d", requests, commands)
			}
		})
	}
}

type profileFailureClient struct {
	stubBase
	methodsErr error
	calls      int
}

func (c *profileFailureClient) Methods() (*Response, error) { return c.stubResponse(), c.methodsErr }
func (c *profileFailureClient) Command(method string, params Value, taskID *int, timeout float64, label string) (*Response, error) {
	c.calls++
	return c.stubResponse(), nil
}
func TestProfilePreflightNeverStartsOnFailure(t *testing.T) {
	args, err := parseArgs([]string{"start", "--profile", "litongjava"})
	if err != nil {
		t.Fatal(err)
	}
	for _, client := range []*profileFailureClient{
		{stubBase: stubBase{data: ObjOf("methods", []Value{"start"})}},
		{methodsErr: errors.New("unreachable")},
		{stubBase: stubBase{okSet: true, ok: false}},
	} {
		response, err := startWithProfile(client, args, "")
		if err == nil && (response == nil || response.Ok()) {
			t.Fatal("preflight failure returned success")
		}
		if client.calls != 0 {
			t.Fatal("start sent after failed capability check")
		}
	}
}

func TestProfileBusinessFailureNoFallback(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			io.WriteString(w, `{"ok":true,"data":{"methods":["list_profiles"]}}`)
			return
		}
		calls++
		io.WriteString(w, `{"ok":false,"error":"Unknown profile"}`)
	}))
	defer server.Close()
	if code := run([]string{"--base-url", server.URL, "--no-record", "--no-auto-start", "start", "--profile", "unknown"}); code != ExitBusiness {
		t.Errorf("exit=%d", code)
	}
	if calls != 1 {
		t.Errorf("sent %d start requests", calls)
	}
}
