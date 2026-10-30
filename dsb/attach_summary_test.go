package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const (
	attachOKEnvelope   = `{"ok":true,"code":1,"data":{"id":"1001","browser":{"mode":"external-cdp","product":"155.0.8059.84","environment":"unknown","targetId":"42CE","host":"unknown","lifecycleOwner":"external","targetType":"page","url":"http://127.0.0.1:18765/index.html"}}}`
	targetsEnvelope    = `{"ok":true,"code":1,"data":{"mode":"external-cdp","environmentIdentity":{"product":"155"},"targets":[{"targetId":"42CE","type":"page","url":"http://127.0.0.1:18765/index.html","title":"DSB Desktop Test App"},{"targetId":"77AB","type":"page","url":"http://127.0.0.1:18765/second.html","title":"Second"}]}}`
	attachFailEnvelope = `{"ok":false,"msg":"[EXTERNAL_TARGET_AMBIGUOUS] 2 targets matched; run targets and select by targetId or exact URL","data":{"errorCode":"EXTERNAL_TARGET_AMBIGUOUS","matched":2,"targets":[{"targetId":"42CE","type":"page","url":"http://a/","title":"A"},{"targetId":"77AB","type":"page","url":"http://b/","title":"B"}],"execution":{"actionStatus":"not-started","retrySafe":true}}}`
)

func runAttachCommand(t *testing.T, envelope string, tail ...string) (int, string) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, envelope)
	}))
	defer server.Close()
	argv := append([]string{"--base-url", server.URL, "--no-auto-start", "--no-record"}, tail...)
	args, err := parseArgs(argv)
	if err != nil {
		t.Fatal(err)
	}
	client, err := buildClient(args)
	if err != nil {
		t.Fatal(err)
	}
	stdout, stderr := &strings.Builder{}, &strings.Builder{}
	printer := NewPrinter(args)
	printer.Out, printer.ErrOut = stdout, stderr
	code, err := dispatch(client, args, printer)
	return report(code, err, printer), stdout.String()
}

func TestAttachSummaryOneLine(t *testing.T) {
	code, text := runAttachCommand(t, attachOKEnvelope, "attach", "--endpoint", "http://127.0.0.1:9222", "--summary")
	lines := nonEmptyLines(text)
	if code != ExitOK || len(lines) != 1 || !strings.HasPrefix(lines[0], "attach OK ") {
		t.Fatalf("exit=%d output=%q", code, text)
	}
	for _, want := range []string{`"targetId":"42CE"`, `"url":"http://127.0.0.1:18765/index.html"`, `"mode":"external-cdp"`, `"id":"1001"`} {
		if !strings.Contains(lines[0], want) {
			t.Errorf("missing %s in %q", want, lines[0])
		}
	}
}

func TestTargetsSummaryListsCandidates(t *testing.T) {
	code, text := runAttachCommand(t, targetsEnvelope, "targets", "--endpoint", "http://127.0.0.1:9222", "--summary")
	lines := nonEmptyLines(text)
	if code != ExitOK || len(lines) != 1 || !strings.HasPrefix(lines[0], "targets OK ") {
		t.Fatalf("exit=%d output=%q", code, text)
	}
	for _, want := range []string{"mode=external-cdp", "count=2", "DSB Desktop Test App|http://127.0.0.1:18765/index.html|42CE", "Second|http://127.0.0.1:18765/second.html|77AB"} {
		if !strings.Contains(lines[0], want) {
			t.Errorf("missing %s in %q", want, lines[0])
		}
	}
}

func TestAttachFailureSummary(t *testing.T) {
	code, text := runAttachCommand(t, attachFailEnvelope, "attach", "--endpoint", "http://127.0.0.1:9222", "--url", "http://a/", "--summary")
	lines := nonEmptyLines(text)
	if code != ExitBusiness || len(lines) != 1 || !strings.HasPrefix(lines[0], "attach FAIL ") {
		t.Fatalf("exit=%d output=%q", code, text)
	}
	for _, want := range []string{"matched=2", "count=2", "A|http://a/|42CE", "msg=[EXTERNAL_TARGET_AMBIGUOUS]", `"actionStatus":"not-started"`} {
		if !strings.Contains(lines[0], want) {
			t.Errorf("missing %s in %q", want, lines[0])
		}
	}
}

func TestAttachTargetsDefaultOutputUnchanged(t *testing.T) {
	for _, envelope := range []string{attachOKEnvelope, targetsEnvelope, attachFailEnvelope} {
		for _, command := range []string{"attach", "targets"} {
			for _, flags := range [][]string{nil, {"--json"}} {
				_, text := runAttachCommand(t, envelope, append([]string{command, "--endpoint", "http://127.0.0.1:9222"}, flags...)...)
				decoded, err := DecodeJSON(text)
				if err != nil {
					t.Fatalf("%s %v: output is not a bare JSON envelope: %q", command, flags, text)
				}
				want, _ := DecodeJSON(envelope)
				if EncodeJSON(decoded, encCompact) != EncodeJSON(want, encCompact) {
					t.Fatalf("%s %v: envelope changed: %q", command, flags, text)
				}
			}
		}
	}
}
