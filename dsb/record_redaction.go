package main

import (
	"net/url"
	"regexp"
	"strings"
)

const redactedCookieValue = "[REDACTED]"

func sensitiveRequest(value Value) bool {
	switch node := value.(type) {
	case *Obj:
		if objGet(node, "sensitive") == true {
			return true
		}
		for _, key := range node.Keys() {
			if inputMethod(key) || ((key == "method" || key == "command") && inputMethod(objStr(node, key))) || sensitiveRequest(objGet(node, key)) {
				return true
			}
		}
	case []Value:
		for _, child := range node {
			if sensitiveRequest(child) {
				return true
			}
		}
	}
	return false
}

func inputMethod(method string) bool {
	return strings.HasPrefix(method, "input_text") || method == "type_text" || method == "send_keys" || method == "press_key"
}

// redactCookieRecords copies structured credentials and input payloads before recording.
// Live responses and explicit exports are unchanged. Use sensitive for arbitrary scripts.
func redactCookieRecords(value Value) Value {
	switch typed := value.(type) {
	case *Obj:
		result := NewObj()
		isSetCookie := objStr(typed, "method") == "set_cookie" || objStr(typed, "command") == "set_cookie"
		for _, key := range typed.Keys() {
			child := redactCookieRecords(objGet(typed, key))
			if secretField(key) || (secretField(objStr(typed, "name")) && key == "value") {
				child = redactedCookieValue
			}
			if key == "cookies" {
				if cookies, ok := child.([]Value); ok {
					for _, cookie := range cookies {
						redactCookieValue(cookie)
					}
				}
			}
			if key == "set_cookie" || (key == "params" && isSetCookie) {
				redactCookieValue(child)
			}
			result.Set(key, child)
		}
		method := objStr(typed, "method")
		if method == "" {
			method = objStr(typed, "command")
		}
		if params := asObj(objGet(result, "params")); params != nil {
			redactInput(params, method)
		}
		for _, key := range result.Keys() {
			if child := asObj(objGet(result, key)); child != nil {
				redactInput(child, key)
			}
		}
		if strings.HasPrefix(method, "input_text") || method == "type_text" || method == "send_keys" {
			if result.Has("data") {
				result.Set("data", ObjOf("redacted", true))
			}
		}
		redactInput(result, method)
		return result
	case []Value:
		result := make([]Value, len(typed))
		for index, child := range typed {
			result[index] = redactCookieRecords(child)
		}
		return result
	case string:
		return redactBodyText(typed)
	default:
		return value
	}
}

// credentialHints is a cheap pre-check before a string is parsed as a body.
var credentialHints = []string{"pass", "authorization", "token", "apikey", "api_key", "api-key", "secret", "cookie"}

// quotedPair matches one "key": "value" pair; used when the text is not valid JSON (e.g. a truncated body).
var quotedPair = regexp.MustCompile(`"((?:[^"\\]|\\.)*)"(\s*:\s*)"((?:[^"\\]|\\.)*)"`)

// redactBodyText masks credential values carried inside a string, such as a postData
// or response body holding JSON or form-encoded text. It complements the structured
// field redaction above (older servers return request bodies verbatim) and returns
// the input unchanged when nothing matches.
func redactBodyText(text string) string {
	if len(text) < 3 || !mayContainCredential(text) {
		return text
	}
	trimmed := strings.TrimSpace(text)
	if strings.HasPrefix(trimmed, "{") || strings.HasPrefix(trimmed, "[") {
		if parsed, err := DecodeJSON(trimmed); err == nil {
			redacted := redactCookieRecords(parsed)
			encoded := EncodeJSON(redacted, encCompact)
			if encoded == EncodeJSON(parsed, encCompact) {
				return text
			}
			return encoded
		}
		return redactQuotedPairs(text)
	}
	if looksFormEncoded(trimmed) {
		return redactForm(text)
	}
	return redactQuotedPairs(text)
}

func mayContainCredential(text string) bool {
	lower := strings.ToLower(text)
	for _, hint := range credentialHints {
		if strings.Contains(lower, hint) {
			return true
		}
	}
	return false
}

// looksFormEncoded requires no whitespace and key=value in every non-empty piece.
func looksFormEncoded(text string) bool {
	if text == "" || !strings.Contains(text, "=") || strings.IndexFunc(text, func(r rune) bool {
		return r == ' ' || r == '\t' || r == '\n' || r == '\r'
	}) >= 0 {
		return false
	}
	for _, piece := range strings.Split(text, "&") {
		if piece != "" && strings.Index(piece, "=") <= 0 {
			return false
		}
	}
	return true
}

func redactForm(text string) string {
	pieces := strings.Split(text, "&")
	changed := false
	for index, piece := range pieces {
		eq := strings.Index(piece, "=")
		if eq <= 0 {
			continue
		}
		key, value := piece[:eq], piece[eq+1:]
		name, err := url.QueryUnescape(key)
		if err != nil {
			name = key
		}
		if value != "" && value != redactedCookieValue && secretField(name) {
			pieces[index] = key + "=" + redactedCookieValue
			changed = true
		}
	}
	if !changed {
		return text
	}
	return strings.Join(pieces, "&")
}

func redactQuotedPairs(text string) string {
	return quotedPair.ReplaceAllStringFunc(text, func(match string) string {
		groups := quotedPair.FindStringSubmatch(match)
		if !secretField(groups[1]) || groups[3] == "" || groups[3] == redactedCookieValue {
			return match
		}
		return `"` + groups[1] + `"` + groups[2] + `"` + redactedCookieValue + `"`
	})
}

func secretField(key string) bool {
	key = strings.ToLower(strings.NewReplacer("-", "", "_", "").Replace(key))
	for _, word := range []string{"password", "passwd", "authorization", "accesstoken", "refreshtoken", "idtoken", "apikey", "secret", "cookie"} {
		if strings.Contains(key, word) && key != "cookies" {
			return true
		}
	}
	return key == "token"
}

func redactInput(params *Obj, method string) {
	if objGet(params, "sensitive") == true {
		for _, key := range params.Keys() {
			if key != "sensitive" {
				params.Set(key, redactedCookieValue)
			}
		}
		return
	}
	if strings.HasPrefix(method, "input_text") || method == "type_text" || method == "send_keys" || method == "press_key" {
		for _, key := range []string{"text", "value", "keys", "key"} {
			if params.Has(key) {
				params.Set(key, redactedCookieValue)
			}
		}
	}
}

// The caller supplies a copied object, never the live request or response.
func redactCookieValue(value Value) {
	if cookie := asObj(value); cookie != nil && cookie.Has("value") {
		cookie.Set("value", redactedCookieValue)
	}
}
