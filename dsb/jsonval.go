package main

// JSON 值层:保序对象 + 与 Python json 模块逐字节一致的编解码。
//
// 为什么要自己写这一层,而不是直接用 encoding/json:
//
//  1. **保序**。Python 的 dict 是有序的,回执与留档里的字段顺序就是服务端给的顺序。
//     encoding/json 的 map 会按键名字典序重排,写出来的留档与 stdout 一眼就能看出被人动过,
//     「第几步开始不对」的排查会变难。
//  2. **数字不能过 float64**。服务端的 jobId 是雪花号(`1790232350369123456`),
//     转成 float64 会掉精度,回填给接口就废了。这里用 json.Number 原样保留字面量。
//  3. **缩进/分隔符要跟 Python 一模一样**。留档文件与 `--compact` 的输出都被脚本和眼睛依赖着。
//
// 值只用这几种: nil / bool / string / json.Number / *Obj / []Value。

import (
	"encoding/json"
	"fmt"
	"io"
	"strconv"
	"strings"
)

// Value 是任意一个 JSON 值。
type Value = any

// Obj 是保序的 JSON 对象。
type Obj struct {
	keys []string
	vals map[string]Value
}

// NewObj 造一个空对象。
func NewObj() *Obj { return &Obj{vals: map[string]Value{}} }

// Set 写入一个键值对;键已存在时只改值,不改变位置(与 Python dict 一致)。
func (o *Obj) Set(key string, value Value) {
	if _, ok := o.vals[key]; !ok {
		o.keys = append(o.keys, key)
	}
	o.vals[key] = value
}

// Get 读一个键。
func (o *Obj) Get(key string) (Value, bool) { v, ok := o.vals[key]; return v, ok }

// Has 判断键在不在。
func (o *Obj) Has(key string) bool { _, ok := o.vals[key]; return ok }

// Keys 按插入顺序返回键(调用方不应修改返回值)。
func (o *Obj) Keys() []string { return o.keys }

// Len 是键的个数。
func (o *Obj) Len() int { return len(o.keys) }

// ObjOf 按「键1,值1,键2,值2,…」构造对象,保持写入顺序。
func ObjOf(pairs ...any) *Obj {
	o := NewObj()
	for i := 0; i+1 < len(pairs); i += 2 {
		key, ok := pairs[i].(string)
		if !ok {
			panic(fmt.Sprintf("ObjOf: 第 %d 个参数必须是键名", i))
		}
		o.Set(key, pairs[i+1])
	}
	return o
}

// asObj 尝试把值当对象取(失败返回 nil)。
func asObj(v Value) *Obj {
	o, _ := v.(*Obj)
	return o
}

// asArray 尝试把值当数组取(失败返回 nil)。
func asArray(v Value) []Value {
	a, _ := v.([]Value)
	return a
}

// objGet 读对象里的键,对象类型不对或键不存在时返回 nil。
func objGet(v Value, key string) Value {
	if o := asObj(v); o != nil {
		got, _ := o.Get(key)
		return got
	}
	return nil
}

// objStr 读对象里的字符串字段(不是字符串时返回空串)。
func objStr(v Value, key string) string {
	s, _ := objGet(v, key).(string)
	return s
}

// objBool 读对象里的布尔字段;返回 (值, 字段是否存在且为布尔)。
func objBool(v Value, key string) (bool, bool) {
	b, ok := objGet(v, key).(bool)
	return b, ok
}

// numberFromInt 把整数包成 json.Number(保持整数字面量,不引入 .0)。
func numberFromInt(value int) json.Number { return json.Number(strconv.Itoa(value)) }

// stringsToValues 把字符串切片转成 Value 数组。
func stringsToValues(items []string) []Value {
	out := make([]Value, 0, len(items))
	for _, item := range items {
		out = append(out, item)
	}
	return out
}

// ------------------------------------------------------------------ 解码

// DecodeJSON 把 JSON 文本解析成 Value 树(保留键序与数字字面量)。
func DecodeJSON(text string) (Value, error) {
	dec := json.NewDecoder(strings.NewReader(text))
	dec.UseNumber()
	value, err := decodeValue(dec)
	if err != nil {
		return nil, err
	}
	// 值后面不能再有内容:Python 的 json.loads 这时会报 "Extra data"
	if _, err := dec.Token(); err != io.EOF {
		if err == nil {
			return nil, fmt.Errorf("Extra data")
		}
		return nil, err
	}
	return value, nil
}

func decodeValue(dec *json.Decoder) (Value, error) {
	token, err := dec.Token()
	if err != nil {
		return nil, err
	}
	if delim, ok := token.(json.Delim); ok {
		switch delim {
		case '{':
			object := NewObj()
			for dec.More() {
				keyToken, err := dec.Token()
				if err != nil {
					return nil, err
				}
				key, _ := keyToken.(string)
				value, err := decodeValue(dec)
				if err != nil {
					return nil, err
				}
				object.Set(key, value)
			}
			if _, err := dec.Token(); err != nil { // 吃掉 '}'
				return nil, err
			}
			return object, nil
		case '[':
			array := []Value{}
			for dec.More() {
				value, err := decodeValue(dec)
				if err != nil {
					return nil, err
				}
				array = append(array, value)
			}
			if _, err := dec.Token(); err != nil { // 吃掉 ']'
				return nil, err
			}
			return array, nil
		}
		return nil, fmt.Errorf("非法分隔符 %v", delim)
	}
	switch token.(type) {
	case string, json.Number, bool, nil:
		return token, nil
	}
	return nil, fmt.Errorf("非法 JSON 记号 %v", token)
}

// ------------------------------------------------------------------ 编码

// encOpts 描述一次编码的缩进与分隔符,三个预设对应 Python 的三种 json.dumps 用法。
type encOpts struct {
	pretty bool
	indent int
	sep    string
	kvSep  string
}

var (
	// encIndent2 对应 json.dumps(..., ensure_ascii=False, indent=2):留档与人类可读输出
	encIndent2 = encOpts{pretty: true, indent: 2, sep: ",", kvSep: ": "}
	// encDefault 对应 json.dumps(..., ensure_ascii=False):发出去的请求体
	encDefault = encOpts{sep: ", ", kvSep: ": "}
	// encCompact 对应 json.dumps(..., separators=(",", ":")):--compact 的一行 JSON
	encCompact = encOpts{sep: ",", kvSep: ":"}
)

// EncodeJSON 按给定的风格把 Value 编码成 JSON 文本。
func EncodeJSON(value Value, opts encOpts) string {
	var builder strings.Builder
	encodeInto(&builder, value, opts, 0)
	return builder.String()
}

func encodeInto(builder *strings.Builder, value Value, opts encOpts, level int) {
	switch typed := value.(type) {
	case nil:
		builder.WriteString("null")
	case bool:
		if typed {
			builder.WriteString("true")
		} else {
			builder.WriteString("false")
		}
	case string:
		encodeString(builder, typed)
	case json.Number:
		builder.WriteString(string(typed))
	case *Obj:
		encodeObject(builder, typed, opts, level)
	case []Value:
		encodeArray(builder, typed, opts, level)
	default:
		// 兜底:正常路径不会走到这里
		encodeString(builder, fmt.Sprint(typed))
	}
}

func encodeObject(builder *strings.Builder, object *Obj, opts encOpts, level int) {
	if object.Len() == 0 {
		builder.WriteString("{}")
		return
	}
	builder.WriteByte('{')
	for index, key := range object.keys {
		if index > 0 {
			if opts.pretty {
				builder.WriteByte(',')
			} else {
				builder.WriteString(opts.sep)
			}
		}
		if opts.pretty {
			builder.WriteByte('\n')
			writeIndent(builder, opts, level+1)
		}
		encodeString(builder, key)
		builder.WriteString(opts.kvSep)
		encodeInto(builder, object.vals[key], opts, level+1)
	}
	if opts.pretty {
		builder.WriteByte('\n')
		writeIndent(builder, opts, level)
	}
	builder.WriteByte('}')
}

func encodeArray(builder *strings.Builder, array []Value, opts encOpts, level int) {
	if len(array) == 0 {
		builder.WriteString("[]")
		return
	}
	builder.WriteByte('[')
	for index, item := range array {
		if index > 0 {
			if opts.pretty {
				builder.WriteByte(',')
			} else {
				builder.WriteString(opts.sep)
			}
		}
		if opts.pretty {
			builder.WriteByte('\n')
			writeIndent(builder, opts, level+1)
		}
		encodeInto(builder, item, opts, level+1)
	}
	if opts.pretty {
		builder.WriteByte('\n')
		writeIndent(builder, opts, level)
	}
	builder.WriteByte(']')
}

func writeIndent(builder *strings.Builder, opts encOpts, level int) {
	for i := 0; i < opts.indent*level; i++ {
		builder.WriteByte(' ')
	}
}

// encodeString 按 Python 的规则转义(ensure_ascii=False:非 ASCII 原样输出,
// 只有引号、反斜杠、六个短转义与其余控制字符要转)。
func encodeString(builder *strings.Builder, text string) {
	builder.WriteByte('"')
	for _, r := range text {
		switch r {
		case '"':
			builder.WriteString(`\"`)
		case '\\':
			builder.WriteString(`\\`)
		case '\n':
			builder.WriteString(`\n`)
		case '\r':
			builder.WriteString(`\r`)
		case '\t':
			builder.WriteString(`\t`)
		case '\b':
			builder.WriteString(`\b`)
		case '\f':
			builder.WriteString(`\f`)
		default:
			if r < 0x20 {
				fmt.Fprintf(builder, `\u%04x`, r)
			} else {
				builder.WriteRune(r)
			}
		}
	}
	builder.WriteByte('"')
}

// ------------------------------------------------------------------ 取值工具

// selectField 按点分路径取值(支持数字数组下标),不执行任何表达式。
//
// 与 Python 一致:显式 null 与「路径不存在」是两回事 —— 前者原样返回 null,
// 后者报用法错。这也是 `--select` 能区分「字段是空」与「字段根本没有」的原因。
func selectField(value Value, path string) (Value, error) {
	for _, part := range strings.Split(path, ".") {
		if object := asObj(value); object != nil {
			got, ok := object.Get(part)
			if !ok {
				return nil, usageErrorf("--select 路径不存在:%s (在 %s)", path, part)
			}
			value = got
			continue
		}
		if array := asArray(value); array != nil && isDigits(part) {
			index, err := strconv.Atoi(part)
			if err == nil && index < len(array) {
				value = array[index]
				continue
			}
		}
		return nil, usageErrorf("--select 路径不存在:%s (在 %s)", path, part)
	}
	return value, nil
}

// isDigits 判断是否全是 ASCII 数字(Python 的 str.isdigit 近似版)。
func isDigits(text string) bool {
	if text == "" {
		return false
	}
	for _, r := range text {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// pickIndex 从批量回执里取出第 N 步(index==0 也能用在单条响应上)。
func pickIndex(envelope Value, index int, hasIndex bool) (Value, error) {
	data := objGet(envelope, "data")
	results := asArray(objGet(data, "results"))
	if results == nil {
		if !hasIndex || index == 0 {
			return envelope, nil
		}
		return nil, usageErrorf("--index %d 越界:这条响应不是批量结果(没有 data.results)", index)
	}
	if !hasIndex {
		return envelope, nil
	}
	if index < 0 || index >= len(results) {
		return nil, usageErrorf("--index %d 越界:这一批共 %d 步", index, len(results))
	}
	return results[index], nil
}

// isEmptyJSON 判断值是否属于 Python 里 `x in (None, "", [], {})` 的那几类空。
//
// 注意 0 与 false **不算空**(Python 的 == 比较里它们不等于元组里的任何一项),
// 所以 `count=0`、`failed=False` 都要照常出现在摘要行里。
func isEmptyJSON(value Value) bool {
	switch typed := value.(type) {
	case nil:
		return true
	case string:
		return typed == ""
	case []Value:
		return len(typed) == 0
	case *Obj:
		return typed.Len() == 0
	}
	return false
}

// pyStr 按 Python 的 str() 规则渲染一个值(True/False/None、数字字面量、中文原样)。
// 摘要行与提示文案靠它跟 Python 版本对齐。
func pyStr(value Value) string {
	switch typed := value.(type) {
	case nil:
		return "None"
	case bool:
		if typed {
			return "True"
		}
		return "False"
	case string:
		return typed
	case json.Number:
		return string(typed)
	case *Obj:
		return EncodeJSON(typed, encDefault)
	case []Value:
		return EncodeJSON(typed, encDefault)
	}
	return fmt.Sprint(value)
}

// pyRepr 近似 Python 的 repr(),只用于报错文案里的值展示。
func pyRepr(value Value) string {
	if text, ok := value.(string); ok {
		return "'" + strings.ReplaceAll(text, "'", "\\'") + "'"
	}
	return pyStr(value)
}
