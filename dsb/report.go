package main

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Reports only consume existing records. Missing evidence is never synthesized.
func cmdReport(client *Client, args *Args, out *Printer) (int, error) {
	entries, err := os.ReadDir(client.RecordDir)
	if err != nil {
		return 0, err
	}
	names := []string{}
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".req.json") {
			names = append(names, entry.Name())
		}
	}
	sort.Slice(names, func(i, j int) bool {
		a := recordIndexPattern.FindString(names[i])
		b := recordIndexPattern.FindString(names[j])
		if len(a) != len(b) {
			return len(a) < len(b)
		}
		return a < b
	})
	steps := []Value{}
	counts := map[string]int{"success": 0, "failure": 0, "skipped": 0, "unknown": 0}
	for _, name := range names {
		responseName := strings.TrimSuffix(name, ".req.json") + ".res.json"
		if args.ID != nil && !recordBelongsToTask(client.RecordDir, responseName, client.TaskID) {
			continue
		}
		reqText, reqErr := readLocalFile(filepath.Join(client.RecordDir, name))
		request, parseErr := DecodeJSON(reqText)
		resText, resErr := readLocalFile(filepath.Join(client.RecordDir, responseName))
		response, decodeErr := DecodeJSON(resText)
		status := "unknown"
		data := objGet(response, "data")
		if reqErr == nil && parseErr == nil && resErr == nil && decodeErr == nil {
			if ok, known := objGet(response, "ok").(bool); known {
				if ok {
					status = "success"
				} else {
					status = "failure"
				}
			}
			if objStr(data, "actionStatus") == "unknown" || objStr(objGet(data, "execution"), "actionStatus") == "unknown" {
				status = "unknown"
			}
			if objStr(data, "actionStatus") == "not-started" || objStr(objGet(data, "execution"), "actionStatus") == "not-started" {
				status = "skipped"
			}
			if objGet(data, "redacted") == true {
				status = "unknown"
			}
		}
		counts[status]++
		step := ObjOf("request", name, "response", responseName, "method", objGet(request, "method"), "status", status,
			"commandSucceeded", objGet(response, "ok"), "actionSucceeded", objGet(data, "actionSucceeded"),
			"businessSucceeded", objGet(data, "businessSucceeded"), "evidence", response)
		if reqErr != nil || parseErr != nil || resErr != nil || decodeErr != nil {
			step.Set("evidenceMissing", true)
		}
		steps = append(steps, step)
	}
	report := ObjOf("schemaVersion", numberFromInt(1), "toolVersion", version, "recordDirectory", client.RecordDir,
		"scope", "Recorded commands only; success does not imply business success. Missing timestamps and application identity remain unknown.",
		"counts", ObjOf("success", numberFromInt(counts["success"]), "failure", numberFromInt(counts["failure"]), "skipped", numberFromInt(counts["skipped"]), "unknown", numberFromInt(counts["unknown"])), "steps", steps)
	if args.Markdown != nil {
		var md strings.Builder
		md.WriteString("# dsb acceptance report\n\nRecorded evidence only. Command success is not business success.\n\n| Step | Method | Status | Evidence |\n| --- | --- | --- | --- |\n")
		for i, step := range steps {
			method := strings.NewReplacer("|", "&#124;", "\n", " ").Replace(objStr(step, "method"))
			fmt.Fprintf(&md, "| %d | %s | %s | %s |\n", i+1, method, objStr(step, "status"), objStr(step, "response"))
		}
		if err := writeTextFile(*args.Markdown, md.String()); err != nil {
			return 0, err
		}
	}
	handled, err := emitSideOutput(out, args, report)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.JSON(report)
	}
	return ExitOK, nil
}
