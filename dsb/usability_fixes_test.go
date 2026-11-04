package main

import (
	"errors"
	"io"
	"runtime/debug"
	"testing"
	"time"
)

func TestTextParamsStayLiteral(t *testing.T) {
	parsed, err := parseKV([]string{"text=2026", "selector=007", "index=3", "exact=true"})
	if err != nil {
		t.Fatal(err)
	}
	if value, _ := parsed.Get("text"); value != "2026" {
		t.Fatalf("text must stay a string, got %#v", value)
	}
	if value, _ := parsed.Get("selector"); value != "007" {
		t.Fatalf("selector must stay literal, got %#v", value)
	}
	if value, _ := parsed.Get("exact"); value != true {
		t.Fatalf("other params still decode as JSON, got %#v", value)
	}
}

func TestVersionShowsCommit(t *testing.T) {
	settings := []debug.BuildSetting{{Key: "vcs.revision", Value: "8dc3b62cd256"}, {Key: "vcs.modified", Value: "true"}}
	if got := versionFromSettings(settings); got != version+" (8dc3b62c-dirty)" {
		t.Fatalf("unexpected version: %s", got)
	}
	if got := versionFromSettings(nil); got != version {
		t.Fatalf("no vcs info must fall back to the plain version: %s", got)
	}
}

func quietPrinter() *Printer {
	return &Printer{Mode: "full", Out: io.Discard, ErrOut: io.Discard}
}

func TestWaitForIdleNeedsTwoEmptyReads(t *testing.T) {
	counts := []int{1, 0, 1, 0, 0}
	reads := 0
	read := func(string) (int, int, error) {
		value := counts[reads]
		reads++
		return value, 0, nil
	}
	code, err := waitForIdle("x", 100, "restart", quietPrinter(), read, func(time.Duration) {})
	if err != nil || code != ExitOK {
		t.Fatalf("code=%d err=%v", code, err)
	}
	if reads != len(counts) {
		t.Fatalf("one empty read during a handover must not count as idle: reads=%d", reads)
	}
}

func TestWaitForIdleGivesUpWithoutActing(t *testing.T) {
	read := func(string) (int, int, error) { return 2, 0, nil }
	code, err := waitForIdle("x", 4, "restart", quietPrinter(), read, func(time.Duration) {})
	if err != nil || code != ExitBusiness {
		t.Fatalf("a busy service must refuse: code=%d err=%v", code, err)
	}
}

func TestWaitForIdleStopsWhenServiceIsGone(t *testing.T) {
	read := func(string) (int, int, error) { return 0, 0, errors.New("gone") }
	code, err := waitForIdle("x", 4, "stop", quietPrinter(), read, func(time.Duration) {})
	if err != nil || code != ExitOK {
		t.Fatalf("code=%d err=%v", code, err)
	}
}

func TestRawPrintsSelectedTextVerbatim(t *testing.T) {
	var sink stringSink
	path := "data.result"
	printer := &Printer{Mode: "json", Out: &sink, ErrOut: io.Discard, Select: &path, Raw: true}
	printer.JSON(ObjOf("data", ObjOf("result", "line one\nline two")))
	if sink.text != "line one\nline two\n" {
		t.Fatalf("raw output must keep real newlines: %q", sink.text)
	}
}

type stringSink struct{ text string }

func (s *stringSink) Write(data []byte) (int, error) {
	s.text += string(data)
	return len(data), nil
}
