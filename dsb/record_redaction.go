package main

const redactedCookieValue = "[REDACTED]"

// redactCookieRecords copies structured Cookie fields before automatic recording.
// Explicit exports and live responses retain their original values. Arbitrary text,
// JavaScript, headers, server traces, and historical files are outside this scope.
func redactCookieRecords(value Value) Value {
	switch typed := value.(type) {
	case *Obj:
		result := NewObj()
		isSetCookie := objStr(typed, "method") == "set_cookie" || objStr(typed, "command") == "set_cookie"
		for _, key := range typed.Keys() {
			child := redactCookieRecords(objGet(typed, key))
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

// The caller supplies a copied object, never the live request or response.
func redactCookieValue(value Value) {
	if cookie := asObj(value); cookie != nil && cookie.Has("value") {
		cookie.Set("value", redactedCookieValue)
	}
}
