package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTaskRecord(t *testing.T, folder, stem, request, response string) {
	t.Helper()
	for suffix, text := range map[string]string{".req.json": request, ".res.json": response} {
		if err := os.WriteFile(filepath.Join(folder, stem+suffix), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
}

func TestLastExplicitTaskIsolation(t *testing.T) {
	folder := t.TempDir()
	writeTaskRecord(t, folder, "999", `{"id":42}`, `{"ok":true,"data":{"mark":"older"}}`)
	writeTaskRecord(t, folder, "1000", `{"id":"42"}`, `{"ok":true,"data":{"mark":"mine"}}`)
	writeTaskRecord(t, folder, "1001", `{"id":43}`, `{"ok":true,"data":{"mark":"other","id":42}}`)
	for _, argv := range [][]string{{"--id", "42", "last"}, {"last", "--id", "42"}, {"last"}, {"last", "--id", "44"}} {
		t.Run(strings.Join(argv, "_"), func(t *testing.T) {
			args, err := parseArgs(argv)
			if err != nil {
				t.Fatal(err)
			}
			id := 42
			if args.ID != nil && *args.ID == "44" {
				id = 44
			}
			client := &Client{TaskID: id, RecordDir: folder}
			stdout, stderr := &strings.Builder{}, &strings.Builder{}
			code, err := cmdLast(client, args, &Printer{Out: stdout, ErrOut: stderr})
			if id == 44 {
				if err == nil || stdout.Len() != 0 {
					t.Fatal("missing task must fail without exposing another response")
				}
				return
			}
			want := "mine"
			if args.ID == nil {
				want = "other"
			}
			if err != nil || code != ExitOK || !strings.Contains(stdout.String(), want) {
				t.Fatalf("last returned %d, %v, %s", code, err, stdout.String())
			}
		})
	}
}

func TestRecordTaskIdentityValidation(t *testing.T) {
	folder := t.TempDir()
	for index, request := range []string{
		`{}`, `{"id":null}`, `{"id":true}`, `{"id":42.0}`, `{"id":"42.0"}`, `{"id":`,
		`{"id":999999999999999999999999}`,
	} {
		stem := fmt.Sprintf("%03d", index)
		writeTaskRecord(t, folder, stem, request, `{"ok":true}`)
		if recordBelongsToTask(folder, stem+".res.json", 42) || recordBelongsToTask(folder, stem+".res.json", 1001) {
			t.Fatal("invalid request identity must not match")
		}
	}
	if recordBelongsToTask(folder, "missing.res.json", 42) {
		t.Fatal("missing request must not match")
	}
}

func TestLastDoesNotFallbackAfterMatchingFailure(t *testing.T) {
	for _, response := range []string{
		`{"sendFailed":true,"error":"fixture network failure"}`, `{"ok":`,
	} {
		t.Run(response, func(t *testing.T) {
			folder := t.TempDir()
			writeTaskRecord(t, folder, "001", `{"id":42}`, `{"ok":true,"data":"old success"}`)
			writeTaskRecord(t, folder, "002", `{"id":42}`, response)
			args, err := parseArgs([]string{"last", "--id", "42"})
			if err != nil {
				t.Fatal(err)
			}
			stdout, stderr := &strings.Builder{}, &strings.Builder{}
			code, err := cmdLast(&Client{TaskID: 42, RecordDir: folder}, args, &Printer{Out: stdout, ErrOut: stderr})
			if (err == nil && code == ExitOK) || stdout.Len() != 0 {
				t.Fatal("latest failed record must not fall back to a success")
			}
		})
	}
}
