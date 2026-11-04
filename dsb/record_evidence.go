package main

import "encoding/json"

// A sensitive batch can repeat input in assertions, selectors or later scripts.
// Preserve its shape for reporting, but no parameter values.
func safeRecordedRequest(request Value) *Obj {
	r := ObjOf("id", objGet(request, "id"), "method", objGet(request, "method"), "params", ObjOf("sensitive", true))
	if objStr(request, "method") == "commands" {
		steps := []Value{}
		for _, entry := range asArray(objGet(objGet(request, "params"), "commands")) {
			step := NewObj()
			if obj := asObj(entry); obj != nil {
				for _, key := range obj.Keys() {
					step.Set(key, ObjOf("redacted", true))
				}
			}
			steps = append(steps, step)
		}
		r.Set("params", ObjOf("commands", steps))
	}
	return r
}

// Only protocol-owned paths are projected. Never walk result/body/readback or errors.
func safeEvidence(data Value) *Obj {
	r := ObjOf("redacted", true, "evidenceVersion", numberFromInt(1))
	for _, key := range []string{"actionSucceeded", "businessSucceeded", "actionExecuted", "dispatchStarted", "observationCompleted", "retrySafe", "stopped"} {
		if v, ok := objGet(data, key).(bool); ok {
			r.Set(key, v)
		}
	}
	for _, key := range []string{"count", "planned", "notExecuted", "succeeded", "failed", "expectFailed", "steps"} {
		if v, ok := objGet(data, key).(json.Number); ok {
			if n, err := v.Int64(); err == nil && n >= 0 {
				r.Set(key, v)
			}
		}
	}
	for key, allowed := range map[string][]string{
		"actionStatus": {"not-started", "completed", "unknown"},
		"status":       {"running", "done", "failed", "cancelled", "queued"},
		"stage":        {"validation", "queued", "queue", "execution", "dispatch", "locating", "expectation", "readback", "failure-observation", "batch-step", "mouse-dispatch", "javascript-dispatch"},
		"errorCode":    {"INVALID_PARAMS", "INVALID_ARGUMENT", "ACTION_UNCERTAIN", "ACTION_TIMEOUT", "ACTION_FAILED", "SPURIOUS_DISPATCH", "PAGE_NAVIGATING", "NETWORK_ERROR", "PRECONDITION_FAILED", "STALE_SNAPSHOT", "COMMAND_TIMEOUT", "COMMAND_CANCELLED", "COMMAND_BUSY", "ELEMENT_NOT_FOUND", "ELEMENT_OBSCURED", "AMBIGUOUS_LOCATOR", "EVAL_TIMEOUT", "EXTERNAL_TARGET_CLOSED", "EXTERNAL_TARGET_NOT_FOUND", "EXTERNAL_TARGET_AMBIGUOUS", "TASK_ALREADY_EXISTS", "UNKNOWN"},
	} {
		for _, v := range allowed {
			if objGet(data, key) == v {
				r.Set(key, v)
				break
			}
		}
	}
	if execution := asObj(objGet(data, "execution")); execution != nil {
		r.Set("execution", safeEvidence(execution))
	}
	for _, key := range []string{"expectResult", "preconditionResult"} {
		if passed, ok := objGet(objGet(data, key), "passed").(bool); ok {
			r.Set(key, ObjOf("passed", passed))
		}
	}
	return r
}

func safeRecordedData(data Value, request Value) *Obj {
	r := safeEvidence(data)
	method := objStr(request, "method")
	if method == "get_job" {
		// A polling request has no original sensitivity flag: always keep only protocol evidence.
		r.Set("data", safeRecordedBatch(objGet(data, "data"), nil))
	} else if method == "commands" {
		return safeRecordedBatch(data, objGet(objGet(request, "params"), "commands"))
	}
	return r
}

func safeRecordedBatch(data Value, commands Value) *Obj {
	r := safeEvidence(data)
	if results := asArray(objGet(data, "results")); results != nil {
		steps := []Value{}
		for _, step := range results {
			item := NewObj()
			if index, ok := objGet(step, "index").(json.Number); ok {
				if n, err := index.Int64(); err == nil && n >= 0 {
					item.Set("index", index)
					// Match the response command to its request, never copy an arbitrary response string.
					if original := asArray(commands); int64(len(original)) > n {
						if entry := asObj(original[n]); entry != nil {
							for _, name := range entry.Keys() {
								if name != "expect" && name == objStr(step, "command") {
									item.Set("command", name)
								}
							}
						}
					}
				}
			}
			if ok, known := objGet(step, "ok").(bool); known {
				item.Set("ok", ok)
			}
			item.Set("data", safeEvidence(objGet(step, "data")))
			if passed, known := objGet(objGet(step, "expectResult"), "passed").(bool); known {
				item.Set("expectResult", ObjOf("passed", passed))
			}
			steps = append(steps, item)
		}
		r.Set("results", steps)
	}
	return r
}
