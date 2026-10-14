package main

import "strings"

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
	default:
		return value
	}
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
