package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// recordCall 把这一次调用落盘:NNN.req.json / NNN.res.json + 一行 steps.log。
func (c *Client) recordCall(label string, payload Value, response *Response, sendError string) {
	if !c.Record {
		return
	}
	index, err := c.claimRecordIndex()
	if err != nil {
		if c.Verbose {
			fmt.Fprintf(os.Stderr, "[warn] 记录失败:%v\n", err)
		}
		return
	}
	writeErr := func() error {
		requestText := EncodeJSON(redactCookieRecords(payload), encIndent2)
		// 用 writeTextFile 而不是 os.WriteFile:它与旧 Python 版一样按文本模式把 \n 翻成 \r\n,
		// 换掉客户端之后留档文件的字节不变(实测旧记录就是 CRLF)。
		if err := writeTextFile(filepath.Join(c.RecordDir, fmt.Sprintf("%03d.req.json", index)),
			requestText); err != nil {
			return err
		}
		var responseText string
		if response != nil {
			responseText = EncodeJSON(redactCookieRecords(response.Envelope), encIndent2)
		} else {
			responseText = EncodeJSON(ObjOf("sendFailed", true, "error", sendError), encIndent2)
		}
		if err := writeTextFile(filepath.Join(c.RecordDir, fmt.Sprintf("%03d.res.json", index)),
			responseText); err != nil {
			return err
		}
		stamp := time.Now().Format("2006-01-02 15:04:05")
		var line string
		if response == nil {
			line = fmt.Sprintf("%s #%03d id=%d %s SEND-FAILED %s", stamp, index, c.TaskID, label, sendError)
		} else {
			verdict := "FAIL"
			if response.Ok() {
				verdict = "OK"
			}
			line = fmt.Sprintf("%s #%03d id=%d %s %s %dms%s", stamp, index, c.TaskID, label,
				verdict, response.ElapsedMs, summarize(response.Data()))
			if !response.Ok() {
				line += " msg=" + response.Msg()
			}
		}
		handle, err := os.OpenFile(filepath.Join(c.RecordDir, "steps.log"),
			os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			return err
		}
		defer handle.Close()
		_, err = handle.WriteString(line + "\n")
		return err
	}()
	if writeErr != nil && c.Verbose {
		fmt.Fprintf(os.Stderr, "[warn] 记录失败:%v\n", writeErr)
	}
}

// claimRecordIndex 原子地占一个记录编号。
//
// 以前是「进程启动时算一次 nextIndex,之后自己往下加」:两个并发的 dsb(智能体的工具循环
// 里很常见)会从同一个号开始写,NNN.req.json / NNN.res.json 互相覆盖,steps.log 的行也交错。
// 这里用 O_CREATE|O_EXCL 抢占,占不到就试下一个。
func (c *Client) claimRecordIndex() (int, error) {
	if err := os.MkdirAll(c.RecordDir, 0o755); err != nil {
		return 0, err
	}
	index := c.Counter
	if index < 1 {
		index = 1
	}
	for attempt := 0; attempt < 10000; attempt++ {
		path := filepath.Join(c.RecordDir, fmt.Sprintf("%03d.req.json", index))
		handle, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if err == nil {
			handle.Close()
			c.Counter = index + 1
			return index, nil
		}
		if !os.IsExist(err) {
			return 0, err
		}
		index++
	}
	return 0, usageErrorf("记录编号连续被占用,写不进去:%s", c.RecordDir)
}

// nextIndex 接着已有的编号往下记,不覆盖上一轮的记录。
func nextIndex(directory string) int {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return 1
	}
	highest := 0
	pattern := regexp.MustCompile(`^(\d+)\.(?:req|res)\.json$`)
	for _, entry := range entries {
		if match := pattern.FindStringSubmatch(entry.Name()); match != nil {
			if value, err := strconv.Atoi(match[1]); err == nil && value > highest {
				highest = value
			}
		}
	}
	return highest + 1
}

// defaultRecordDir 记录目录:与 PowerShell 客户端保持一致,落在仓库的 logs/agent/<会话>/ 下。
func defaultRecordDir(session string) string {
	if session == "" {
		return filepath.Join("logs", "agent", "dsb")
	}
	if base := os.Getenv("DSB_RECORD_DIR"); base != "" {
		return filepath.Join(base, session)
	}
	return filepath.Join("logs", "agent", session)
}

// summarize 把回执里的关键字段压成一行,便于 steps.log 里一眼看出发生了什么。
//
// 只挑「一眼能判断这一步成不成」的标量字段;挑不出任何字段时返回空串,由调用方决定退化成什么。
func summarize(data Value) string {
	object := asObj(data)
	if object == nil {
		return ""
	}
	order := []string{"count", "countBlocking", "countStrict", "succeeded", "failed", "expectFailed",
		"seq", "status", "mode", "matched", "stable", "closed", "elementCount", "jobId", "step",
		"title", "url", "value", "visible", "enabled", "checked", "result", "actionStatus",
		"retrySafe", "observationComplete", "snapshotConsistent", "indicesUsable"}
	parts := make([]string, 0, 8)
	for _, key := range order {
		item, exists := object.Get(key)
		if !exists || isEmptyJSON(item) {
			continue
		}
		// 只收标量:字符串、布尔、数字。对象/数组不是「一眼能判断成不成」的字段。
		switch typed := item.(type) {
		case string:
			runes := []rune(typed)
			if len(runes) > 40 {
				typed = string(runes[:40]) + "…"
			}
			parts = append(parts, key+"="+typed)
		case bool:
			parts = append(parts, key+"="+pyStr(typed))
		case json.Number:
			parts = append(parts, key+"="+string(typed))
		}
	}
	if shot, ok := object.Get("screenshot"); ok {
		parts = append(parts, "shot="+pyStr(shot))
	}
	if results := asArray(objGet(data, "results")); results != nil {
		marks := make([]string, 0, 12)
		for position, step := range results {
			if position >= 12 {
				break
			}
			stepObj := asObj(step)
			if stepObj == nil {
				continue
			}
			okValue, _ := stepObj.Get("ok")
			mark := "fail"
			if flag, isBool := okValue.(bool); isBool && flag {
				mark = "ok"
			}
			// 与 Python 一致:expectResult 是个对象、且里面的 passed 不是真值时,标成 expect-fail
			// (passed 缺失也算没过 —— Python 的 `not None` 就是 True)
			if expectResult := asObj(objGet(step, "expectResult")); expectResult != nil {
				passedValue, exists := expectResult.Get("passed")
				passed, isBool := passedValue.(bool)
				if !exists || !isBool || !passed {
					mark = "expect-fail"
				}
			}
			index, indexExists := stepObj.Get("index")
			command, commandExists := stepObj.Get("command")
			if !indexExists {
				index = nil
			}
			if !commandExists {
				command = nil
			}
			marks = append(marks, pyStr(index)+":"+pyStr(command)+"="+mark)
		}
		parts = append(parts, "| "+strings.Join(marks, " "))
	}
	if len(parts) == 0 {
		return ""
	}
	return " " + strings.Join(parts, " ")
}
