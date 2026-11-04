package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSensitiveBatchKeepsEvidenceWithoutPayloads(t *testing.T) {
	req, _ := DecodeJSON(`{"method":"commands","params":{"commands":[{"input_text":{"index":1,"text":"PRIVATE"}},{"execute_js":{"body":"PRIVATE"}}]}}`)
	res, _ := DecodeJSON(`{"ok":false,"msg":"PRIVATE","data":{"planned":3,"notExecuted":1,"results":[{"index":0,"command":"input_text","ok":true,"data":{"value":"PRIVATE","actionStatus":"completed","result":{"status":"PRIVATE"}}},{"index":1,"command":"execute_js","ok":false,"msg":"PRIVATE","data":{"errorCode":"COMMAND_TIMEOUT","execution":{"stage":"readback","actionStatus":"unknown"}},"expectResult":{"passed":false,"actual":"PRIVATE"}}]}}`)
	dir := t.TempDir()
	c := &Client{Record: true, RecordDir: dir}
	c.recordCall("commands", req, &Response{Envelope: res}, "")
	for _, name := range []string{"001.req.json", "001.res.json", "steps.log"} {
		b, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil || strings.Contains(string(b), "PRIVATE") {
			t.Fatalf("%s: %v %s", name, err, b)
		}
	}
	b, _ := os.ReadFile(filepath.Join(dir, "001.res.json"))
	saved, err := DecodeJSON(string(b))
	if err != nil {
		t.Fatal(err)
	}
	children := reportBatchSteps(objGet(saved, "data"))
	if len(children) != 3 || objStr(children[0], "status") != "success" || objStr(children[1], "status") != "unknown" || objStr(children[2], "status") != "skipped" {
		t.Fatal(EncodeJSON(children, encIndent2))
	}
	if !strings.Contains(EncodeJSON(res, encCompact), "PRIVATE") {
		t.Fatal("mutated live response")
	}
}

func TestJobRecordingDoesNotReExposeSensitiveResult(t *testing.T) {
	req := ObjOf("method", "get_job")
	data, _ := DecodeJSON(`{"status":"done","data":{"results":[{"index":0,"command":"execute_js","ok":true,"data":{"result":"PRIVATE"}}]}}`)
	saved := safeRecordedData(data, req)
	if strings.Contains(EncodeJSON(saved, encCompact), "PRIVATE") || len(reportBatchSteps(objGet(saved, "data"))) != 1 {
		t.Fatal(EncodeJSON(saved, encCompact))
	}
}

func TestBatchAndJobOutputPreserveFailure(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "steps.json")
	os.WriteFile(source, []byte(`[{"get_title":{}}]`), 0600)
	for _, argv := range [][]string{{"batch", source}, {"job", "j"}, {"batch", source, "--async", "--wait"}, {"job", "j", "--wait"}} {
		args, err := parseArgs(append(argv, "--json", "--out", filepath.Join(dir, "result.json")))
		if err != nil {
			t.Fatal(err)
		}
		output := &strings.Builder{}
		p := NewPrinter(args)
		p.Out = output
		p.ErrOut = &strings.Builder{}
		client := &stubClient{stubBase{data: ObjOf("jobId", "j", "status", "failed"), okSet: true, ok: false}}
		var code int
		if argv[0] == "batch" {
			code, err = cmdBatch(client, args, p)
		} else {
			code, err = cmdJob(client, args, p)
		}
		if err != nil || code != ExitBusiness {
			t.Fatalf("%v: %d %v", argv, code, err)
		}
		if _, err = DecodeJSON(output.String()); err != nil {
			t.Fatalf("stdout not JSON: %q", output.String())
		}
	}
}

type asyncEvidenceClient struct{ stubBase }

func (c *asyncEvidenceClient) WaitJob(jobID string, poll, timeout float64, tick func(*Response)) (*Response, error) {
	r := &Response{Envelope: ObjOf("ok", true, "data", ObjOf("status", "failed", "steps", numberFromInt(1)))}
	tick(r)
	return r, nil
}

func TestAsyncWaitPrintsOnlyFinalJSONAndPreservesJobFailureOnExportError(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "steps.json")
	os.WriteFile(source, []byte(`[{"get_title":{}}]`), 0600)
	for _, destination := range []string{filepath.Join(dir, "result.json"), dir} {
		args, err := parseArgs([]string{"batch", source, "--async", "--wait", "--json", "--out", destination})
		if err != nil {
			t.Fatal(err)
		}
		stdout, stderr := &strings.Builder{}, &strings.Builder{}
		printer := NewPrinter(args)
		printer.Out = stdout
		printer.ErrOut = stderr
		client := &asyncEvidenceClient{stubBase{data: ObjOf("jobId", "j", "status", "running")}}
		code, err := cmdBatch(client, args, printer)
		if code != ExitBusiness || err != nil {
			t.Fatalf("code=%d err=%v", code, err)
		}
		if destination != dir {
			value, err := DecodeJSON(stdout.String())
			if err != nil || objStr(objGet(value, "data"), "status") != "failed" {
				t.Fatalf("%v %q", err, stdout.String())
			}
		} else if !strings.Contains(stderr.String(), "Output failed") {
			t.Fatal(stderr.String())
		}
	}
}
