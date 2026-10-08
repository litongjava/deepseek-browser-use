package main

// 输出层:Printer(摘要 + JSON)、文本落盘、以及两个输出开关(--out / --grep)。
//
// 关于换行的一件事,值得单独说明:Python 的文本流在 Windows 上会把 `\n` 翻译成 `\r\n`,
// 所以旧客户端写出来的留档文件与 `--out` 文件都是 CRLF(实测过 logs/agent/dsb/*.res.json)。
// 为了「同一份 `--out` 输出在换掉客户端之后仍然逐字节一样」,这里对文本出口做同样的翻译。
// 注意这不是在补一个缺陷:`\r\n` 经过翻译会变成 `\r\r\n` 也照搬 —— 与 Python 文本模式一致。

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

// newTextSink 造一个「像 Python 文本流那样翻译换行」的出口(仅 Windows)。
func newTextSink(writer io.Writer) io.Writer {
	if runtime.GOOS == "windows" {
		return &newlineTranslator{writer: writer}
	}
	return writer
}

type newlineTranslator struct{ writer io.Writer }

func (t *newlineTranslator) Write(data []byte) (int, error) {
	// 写入长度按原字节数上报,免得调用方以为少写了
	translated := strings.ReplaceAll(string(data), "\n", "\r\n")
	if _, err := io.WriteString(t.writer, translated); err != nil {
		return 0, err
	}
	return len(data), nil
}

// writeTextFile 按 UTF-8、无 BOM 写文本(CRLF 翻译同 Python)。
//
// 回执里全是中文,Windows 上用默认编码写会直接抛异常 —— 所以这里固定 UTF-8。
func writeTextFile(path string, text string) error {
	if directory := filepath.Dir(path); directory != "" && directory != "." {
		if err := os.MkdirAll(directory, 0o755); err != nil {
			return err
		}
	}
	handle, err := os.Create(path)
	if err != nil {
		return err
	}
	defer handle.Close()
	_, err = newTextSink(handle).Write([]byte(text))
	return err
}

// Printer 是输出策略:默认「摘要 + JSON」,`--json` 只出 JSON,`--compact`(`--summary`)只出摘要。
//
// `--summary` 是**本地输出**开关,与服务端协议里的 `responseMode:"compact"`(响应精简模式)不是
// 一回事 —— 后者要用 `--response-mode compact` 传。同名但不同义,以前实测照文档用错过一次,
// 所以这里连名字一起改清楚。
type Printer struct {
	Mode   string // full / json / compact
	Out    io.Writer
	ErrOut io.Writer
	Select *string
	// SelectLenient 为真时,--select 的路径不存在就退回打印整封(旧行为)。
	SelectLenient bool
	// SelectMissed 记录「这次 --select 打偏了」,由 report() 翻成退出码 3。
	SelectMissed bool
}

// NewPrinter 按命令行开关造一个 Printer(对应 Python 的 printer_for)。
func NewPrinter(args *Args) *Printer {
	mode := "full"
	if args.JSON || args.Select != nil {
		mode = "json"
	} else if args.Compact {
		mode = "compact"
	}
	return &Printer{
		Mode:          mode,
		Out:           newTextSink(os.Stdout),
		ErrOut:        newTextSink(os.Stderr),
		Select:        args.Select,
		SelectLenient: args.SelectLenient,
	}
}

// Response 打印一次调用的结果:默认「一行摘要 + 完整信封」。
// payload 给定时(hasPayload=true)改印它,比如只要批量里的某一步。
func (p *Printer) Response(response *Response, label string, hasIndex bool, index int, hasPayload bool, payload Value) {
	if p.Select != nil {
		if hasPayload {
			p.JSON(payload)
		} else {
			p.JSON(response.Envelope)
		}
		return
	}
	if p.Mode != "json" {
		prefix := ""
		if hasIndex {
			prefix = fmt.Sprintf("#%03d ", index)
		}
		verdict := "FAIL"
		if response.Ok() {
			verdict = "OK"
		}
		head := fmt.Sprintf("%s%s %s %dms", prefix, label, verdict, response.ElapsedMs)
		detail := summarize(response.Data())
		if !response.Ok() && response.Msg() != "" {
			detail += " msg=" + response.Msg()
		}
		p.printLine(head + detail)
		// 摘要为空时**不能**只回一行「OK 27ms」:那等于把答案吞了(实测 get_tabs / get_console_logs
		// / get_dialog 都是这样,让人以为「没有数据」)。这时退回一行 JSON,信息不丢、也还是一行。
		if p.Mode == "compact" && detail == "" {
			body := response.Data()
			if hasPayload {
				body = payload
			}
			p.printLine(EncodeJSON(body, encCompact))
		}
	}
	if p.Mode != "compact" {
		if hasPayload {
			p.JSON(payload)
		} else {
			p.JSON(response.Envelope)
		}
	}
}

// JSON 打印一个值:`--compact` 压成一行,否则缩进 2。
func (p *Printer) JSON(value Value) {
	if p.Select != nil {
		p.warnUnreliable(value)
		// 失败响应**也要**照 --select 投影。批量回执里只要有一步失败,整批的 ok 就是 false,
		// 但 data.results[N] 依然完整返回 —— 以前这里一看到 ok:false 就把整封打出来,于是
		// 「用 --select 只看失败的那一步」正好在最需要它的时候失效(实测一批 6 步、只想看第 4 步,
		// 结果每次都被上千行整封回执淹掉)。只有路径确实不存在(例如单条命令没有 data.results)
		// 才退回整封,并在 stderr 说明是退回去了,免得调用方以为投影生效了。
		picked, err := selectField(value, *p.Select)
		if err != nil {
			if p.SelectLenient {
				// 调用方明确要了宽松模式:照旧退回整封,退出码也照旧是 0
				p.Warn(fmt.Sprintf("--select %s 在这条响应里不存在,改印完整信封(--select-lenient)", *p.Select))
				p.printLine(EncodeJSON(value, encIndent2))
				return
			}
			p.SelectMissed = true
			// 默认:路径不存在是**用法错**,不是成功。stdout 打 null(仍是合法 JSON,
			// 管道不会炸),退出码由 report() 翻成 3 —— 让「你取的东西没了」变成调用方
			// 看得见的事实,而不是悄悄换成一个形状完全不同的东西还报成功。
			p.Warn(fmt.Sprintf("--select %s 在这条响应里不存在,stdout 打 null(要整封加 --select-lenient)", *p.Select))
			p.printLine("null")
			return
		}
		p.printLine(EncodeJSON(picked, encIndent2))
		return
	}
	if p.Mode == "compact" {
		p.printLine(EncodeJSON(value, encCompact))
	} else {
		p.printLine(EncodeJSON(value, encIndent2))
	}
}

// warnUnreliable:快照不可靠 / 动作结果未知时在 stderr 提一句(成功与失败两条路都要提)。
func (p *Printer) warnUnreliable(value Value) {
	data := asObj(objGet(value, "data"))
	if data == nil {
		return
	}
	if flag, ok := data.Get("snapshotConsistent"); ok {
		if consistent, isBool := flag.(bool); isBool && !consistent {
			p.Warn("快照不可靠:" + pyStr(mustGet(data, "snapshotIssues")))
		}
	}
	unknownAction := false
	if status, ok := data.Get("actionStatus"); ok && status == "unknown" {
		unknownAction = true
	}
	incomplete := false
	if complete, ok := data.Get("observationComplete"); ok {
		if flag, isBool := complete.(bool); isBool && !flag {
			incomplete = true
		}
	}
	if unknownAction || incomplete {
		p.Warn("动作结果或观测不完整，请先读取业务结果，勿自动重试")
	}
}

func mustGet(object *Obj, key string) Value {
	value, _ := object.Get(key)
	return value
}

// Line 打一行正文(`--json` 模式不打)。
func (p *Printer) Line(text string) {
	if p.Mode != "json" {
		p.printLine(text)
	}
}

// Log 无条件往 stdout 打一行(server logs 这种「本身就是输出」的场景用,`--json` 也照打)。
func (p *Printer) Log(text string) {
	fmt.Fprintln(p.Out, text)
}

// Warn 往 stderr 打一行(`--json` 也照打:告警不是 JSON 负载的一部分)。
func (p *Printer) Warn(text string) {
	fmt.Fprintln(p.ErrOut, text)
}

func (p *Printer) printLine(text string) {
	fmt.Fprintln(p.Out, text)
}

// ------------------------------------------------------------------ 输出开关

// emitSideOutput 处理 --out / --grep;返回 true 表示输出已由这里完成,调用方不必再打整封。
//
// 两个开关都**不改变发出去的请求**,只是把返回的东西换个打法 —— 所以带上它们不会让一次
// 已经成功的调用变成失败,也不会多花一次往返。
func emitSideOutput(printer *Printer, args *Args, value Value) (bool, error) {
	if args.Out == nil && args.Grep == nil {
		return false, nil
	}
	text := EncodeJSON(value, encIndent2)
	if args.Out != nil {
		if err := writeTextFile(*args.Out, text); err != nil {
			return false, usageErrorf("写不了文件 %s:%v", *args.Out, err)
		}
		lines := len(strings.Split(text, "\n"))
		// 走 Line 而不是 printLine:--json 时 stdout 必须是纯 JSON,
		// 否则管道那头收到的是一句中文而不是 JSON。
		printer.Line(fmt.Sprintf("已写入 %s（%d 字符，%d 行）", *args.Out, len(text), lines))
	}
	if args.Grep != nil {
		expression, err := regexp.Compile(*args.Grep)
		if err != nil {
			return false, usageErrorf("--grep 的正则写法非法:%v", err)
		}
		allLines := strings.Split(text, "\n")
		hits := make([]string, 0, 8)
		for _, line := range allLines {
			if expression.MatchString(line) {
				hits = append(hits, line)
			}
		}
		for _, line := range hits {
			printer.Line(line)
		}
		printer.Line(fmt.Sprintf("--grep %s:共 %d 行,命中 %d 行",
			pyRepr(*args.Grep), len(allLines), len(hits)))
	}
	return true, nil
}
