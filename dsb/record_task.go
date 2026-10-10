package main

import (
	"encoding/json"
	"path/filepath"
	"strconv"
	"strings"
)

// Only the paired request identifies a task; response data.id may be business data.
// Invalid or absent request records must never match the default task implicitly.
func recordBelongsToTask(directory, responseName string, taskID int) bool {
	requestName := strings.TrimSuffix(responseName, ".res.json") + ".req.json"
	text, err := readLocalFile(filepath.Join(directory, requestName))
	if err != nil {
		return false
	}
	request, err := DecodeJSON(text)
	if err != nil {
		return false
	}
	var literal string
	switch id := objGet(request, "id").(type) {
	case json.Number:
		literal = id.String()
	case string:
		literal = id
	default:
		return false
	}
	parsed, err := strconv.Atoi(literal)
	return err == nil && parsed == taskID
}
