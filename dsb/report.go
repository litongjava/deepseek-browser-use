package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
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
			// 执行前被拒绝(not-started)仍是失败,只有未失败的响应才记为跳过;unknown 可能已有副作用,保持未知。
			switch reportActionStatus(data) {
			case "unknown":
				status = "unknown"
			case "not-started":
				if status != "failure" {
					status = "skipped"
				}
			}
			if objGet(data, "redacted") == true {
				status = "unknown"
			}
		}
		counts[status]++
		var actionStatus, errorCode Value
		if value := reportActionStatus(data); value != "" {
			actionStatus = value
		}
		if value := reportErrorCode(response); value != "" {
			errorCode = value
		}
		step := ObjOf("request", name, "response", responseName, "method", objGet(request, "method"), "status", status,
			"actionStatus", actionStatus, "errorCode", errorCode,
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
		md.WriteString("# dsb acceptance report\n\nRecorded evidence only. Command success is not business success.\n\n| Step | Method | Status | Action | Evidence |\n| --- | --- | --- | --- | --- |\n")
		cell := strings.NewReplacer("|", "&#124;", "\n", " ")
		for i, step := range steps {
			method := cell.Replace(objStr(step, "method"))
			action := cell.Replace(objStr(step, "actionStatus"))
			fmt.Fprintf(&md, "| %d | %s | %s | %s | %s |\n", i+1, method, objStr(step, "status"), action, objStr(step, "response"))
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

// reportErrorCodePattern 匹配 msg 开头的 [CODE] 错误码。
var reportErrorCodePattern = regexp.MustCompile(`^\[([A-Z][A-Z0-9_]*)\]`)

// reportActionStatus 读取 data.actionStatus,缺失时回退到 data.execution.actionStatus。
func reportActionStatus(data Value) string {
	if value := objStr(data, "actionStatus"); value != "" {
		return value
	}
	return objStr(objGet(data, "execution"), "actionStatus")
}

// reportErrorCode 读取 data.errorCode,缺失时解析 msg 开头的 [CODE]。
func reportErrorCode(response Value) string {
	if value := objStr(objGet(response, "data"), "errorCode"); value != "" {
		return value
	}
	if match := reportErrorCodePattern.FindStringSubmatch(strings.TrimSpace(objStr(response, "msg"))); match != nil {
		return match[1]
	}
	return ""
}
