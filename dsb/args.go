package main

// 命令行解析:复刻 Python argparse 的几条关键语义,而不只是「能跑」。
//
//  1. **通用选项放子命令前后都行**(`dsb --port 10049 health` 与 `dsb health --port 10049`)。
//     Python 那边靠「顶层 parser + 各子命令 parser 都挂一遍、子命令用 SUPPRESS 默认值避免覆盖」实现,
//     这里等价地实现为「一套选项表,子命令出现后再并入它的专属选项」。
//  2. **长选项可前缀缩写**(argparse 的 allow_abbrev 默认开着):`--retry-o` 就是 `--retry-on-spurious`。
//     文档里没这么写,但既然旧客户端认,新客户端就该认 —— 否则同一份命令在两份客户端上一个通一个不通。
//  3. **负数值要能当值**:`--viewport-expansion -1` 里的 `-1` 不是选项。argparse 靠
//     「没有哪个选项长得像负数」这条规则来判断,这里照做。
//  4. **参数错也是用法错**(退出码 3),不是 argparse 默认的 2 —— 脚本里要能把「命令敲错了」
//     与「服务端挂了」分开。argparse 那层是被 ArgumentParser.error 钩子改掉的,这里直接返回 UsageError。
//
// 所有错误文案尽量贴近 argparse 原话(the following arguments are required / unrecognized arguments /
// expected one argument),因为调用方与文档都在按这些字样判断。

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
)

// argSpec 描述一个选项。
type argSpec struct {
	names      []string // 全部拼写,如 {"--compact", "--summary"}
	dest       string   // 存到哪
	takesValue bool     // 是否吃一个值(否则是开关)
	repeatable bool     // 是否可重复(append 语义)
	metavar    string
	help       string
}

// commandNames 子命令名。
//
// start / close 既是子命令**也是**服务端方法名,不能一律当成误用(见 subcommandMisuseHint)。
var commandNames = []string{"health", "methods", "config", "tasks", "last", "recipes", "start", "close",
	"shutdown", "run", "batch", "job", "upload", "uploads", "state", "js", "selftest", "server"}

// subcommandAlsoMethod 这两个名字在服务端也是合法方法,`dsb run start` / `dsb run close` 是正常用法。
var subcommandAlsoMethod = map[string]bool{"start": true, "close": true}

// commandHelp 每个子命令的一行说明(与 Python 版 --help 里的措辞一致)。
var commandHelp = map[string]string{
	"health":   "健康检查",
	"methods":  "命令清单",
	"config":   "服务端生效配置",
	"tasks":    "当前活着的任务",
	"last":     "重放本会话最近一次的响应",
	"recipes":  "配方列表;--run 直接跑一个",
	"start":    "起一个任务",
	"close":    "关掉这个任务",
	"shutdown": "关掉所有任务与共享浏览器",
	"run":      "执行一条命令",
	"batch":    "批量执行命令(数组 JSON,默认读标准输入)",
	"job":      "查/等异步任务",
	"upload":   "把文件送到服务端暂存区",
	"uploads":  "列出暂存文件;--delete 删一个",
	"state":    "页面状态摘要",
	"js":       "执行 JavaScript",
	"selftest": "对当前服务跑一遍端到端自检",
	"server":   "管理后端服务与目标(init/build/start/stop/restart/status/logs/target)",
}

// commonSpecs 是通用选项:顶层与每个子命令都认。
func commonSpecs() []argSpec {
	return []argSpec{
		{names: []string{"--base-url"}, dest: "base_url", takesValue: true, metavar: "BASE_URL",
			help: "完整基地址,例如 http://10.0.0.5:10049(优先于 --host/--port)"},
		{names: []string{"--host"}, dest: "host", takesValue: true, metavar: "HOST",
			help: fmt.Sprintf("服务端主机(默认 %s);可以是别的机器,例如 10.0.0.5", DefaultHost)},
		{names: []string{"--port"}, dest: "port", takesValue: true, metavar: "PORT",
			help: fmt.Sprintf("服务端端口(默认 %d);同一台机器上跑多个实例时用它区分", DefaultPort)},
		{names: []string{"--use"}, dest: "use", takesValue: true, metavar: "NAME",
			help: "使用登记过的具名目标(见 `dsb server target list`),省得每次敲主机与端口"},
		{names: []string{"--id"}, dest: "id", takesValue: true, metavar: "ID",
			help: fmt.Sprintf("任务 ID(默认 %d);必须是数字", DefaultTaskID)},
		{names: []string{"--timeout"}, dest: "timeout", takesValue: true, metavar: "TIMEOUT",
			help: "单次请求超时秒数(默认 300)"},
		{names: []string{"--session"}, dest: "session", takesValue: true, metavar: "SESSION",
			help: fmt.Sprintf("记录会话名,落到 logs/agent/<会话>/(默认 %s)", DefaultSession)},
		{names: []string{"--record-dir"}, dest: "record_dir", takesValue: true, metavar: "RECORD_DIR",
			help: "记录目录的根(默认 logs/agent)"},
		{names: []string{"--no-record"}, dest: "no_record",
			help: "不把请求与响应写到本地"},
		{names: []string{"--no-auto-start"}, dest: "no_auto_start",
			help: "连不上服务时不自动拉起后端(默认会自动拉起,见 `dsb server --help`)"},
		{names: []string{"--json"}, dest: "json", help: "只输出 JSON(便于管道)"},
		{names: []string{"--select"}, dest: "select", takesValue: true, metavar: "PATH",
			help: "仅输出指定字段的 JSON，如 data.text / data.results.1.data.changed；仍照常留档，" +
				"失败响应同样按路径投影(只在路径不存在时才退回整封)"},
		{names: []string{"--select-lenient"}, dest: "select_lenient",
			help: "--select 的路径不存在时退回打印完整信封(旧行为);默认是打 null 并以退出码 3 报用法错"},
		{names: []string{"--compact", "--summary"}, dest: "compact",
			help: "只输出一行摘要(--summary 是同一个开关的正名;注意它只管本地输出," +
				"服务端的响应精简模式要用 --response-mode compact)"},
		{names: []string{"--response-mode"}, dest: "response_mode", takesValue: true, metavar: "MODE",
			help: "请求信封里的 responseMode(服务端目前认 compact=响应精简模式),与 --compact/--summary 无关"},
		{names: []string{"--diagnostics"}, dest: "diagnostics",
			help: "请求信封里带 diagnostics:true,精简模式也保留点击诊断字段"},
		{names: []string{"--index"}, dest: "index", takesValue: true, metavar: "INDEX",
			help: "从批量结果里取第 N 步(单条响应只能用 0)"},
		{names: []string{"-v", "--verbose"}, dest: "verbose", help: "打印调试信息"},
	}
}

// outputSwitches 是 --out / --grep 两个输出开关(挂给特定子命令)。
func outputSwitches() []argSpec {
	return []argSpec{
		{names: []string{"--out"}, dest: "out", takesValue: true, metavar: "FILE",
			help: "把完整(脱敏后的)JSON 写进文件,标准输出只留一行提示"},
		{names: []string{"--grep"}, dest: "grep", takesValue: true, metavar: "REGEX",
			help: "只打印 JSON 里匹配该正则的行,并附一行「共 N 行、命中 M 行」"},
	}
}

// subcommandSpecs 是每个子命令的专属选项与位置参数。
var subcommandSpecs = map[string][]argSpec{
	"methods": {{names: []string{"filter"}, dest: "filter", takesValue: false, metavar: "FILTER",
		help: "只看名字里含这个片段的方法"}},
	"recipes": {
		{names: []string{"--run"}, dest: "run", takesValue: true, metavar: "RUN", help: "要跑的配方名"},
		{names: []string{"--var"}, dest: "var", takesValue: true, repeatable: true,
			help: "配方变量,写成 k=v,可重复"},
	},
	"start": {
		{names: []string{"--browser"}, dest: "browser", takesValue: true, metavar: "BROWSER",
			help: "auto/chromium/chrome/edge/firefox"},
		{names: []string{"--headful"}, dest: "headful", help: "弹出真实窗口(默认无头)"},
	},
	"run": {
		{names: []string{"method"}, dest: "method", metavar: "METHOD", help: "命令名,例如 go_to_url"},
		{names: []string{"--params"}, dest: "params", takesValue: true, metavar: "PARAMS",
			help: "参数 JSON,或 @文件.json,或 - 读标准输入"},
		{names: []string{"-p", "--param"}, dest: "param", takesValue: true, repeatable: true,
			help: "参数 key=value(值按 JSON 解析),可重复"},
		{names: []string{"--retry-on-spurious", "--retry-spurious"}, dest: "retry_on_spurious",
			help: "这次重发无害时打开:页面上报 Object doesn't exist 这类伪故障时由服务端自动重发" +
				"(动作类命令默认不重发,先用只读命令确认上次没生效再开这个开关)"},
	},
	"batch": {
		{names: []string{"file"}, dest: "file", metavar: "FILE",
			help: "命令数组文件;省略或 - 表示读标准输入"},
		{names: []string{"--keep-going"}, dest: "keep_going",
			help: "一条失败也继续跑完(服务端 stopOnError=false)"},
		{names: []string{"--stop-on-expect-failure"}, dest: "stop_on_expect_failure",
			help: "expect 断言没过就停下"},
		{names: []string{"--async"}, dest: "async_mode", help: "后台跑,立刻返回 jobId"},
		{names: []string{"--wait"}, dest: "wait", help: "配合 --async:轮询到任务结束"},
		{names: []string{"--poll"}, dest: "poll", takesValue: true, metavar: "POLL",
			help: "轮询间隔秒数(默认 1)"},
		{names: []string{"--wait-timeout"}, dest: "wait_timeout", takesValue: true, metavar: "WAIT_TIMEOUT",
			help: "等任务的上限秒数(默认 600)"},
		{names: []string{"--max-duration-ms"}, dest: "max_duration_ms", takesValue: true,
			metavar: "MAX_DURATION_MS", help: "整批的总时长上限"},
	},
	"job": {
		{names: []string{"job_id"}, dest: "job_id", metavar: "JOB_ID", help: "异步任务 ID"},
		{names: []string{"--wait"}, dest: "wait", help: "轮询到任务结束"},
		{names: []string{"--poll"}, dest: "poll", takesValue: true, metavar: "POLL",
			help: "轮询间隔秒数(默认 1)"},
		{names: []string{"--wait-timeout"}, dest: "wait_timeout", takesValue: true, metavar: "WAIT_TIMEOUT",
			help: "等任务的上限秒数(默认 600)"},
	},
	"upload": {
		{names: []string{"file"}, dest: "file", metavar: "FILE", help: "要上传的本地文件"},
		{names: []string{"--filename"}, dest: "filename", takesValue: true, metavar: "FILENAME",
			help: "服务端保存成什么名字(默认用本地文件名)"},
	},
	"uploads": {{names: []string{"--delete"}, dest: "delete", takesValue: true, metavar: "DELETE",
		help: "要删除的暂存文件名"}},
	"state": {
		{names: []string{"--max-elements"}, dest: "max_elements", takesValue: true, metavar: "MAX_ELEMENTS",
			help: "内联元素条数上限"},
		{names: []string{"--full"}, dest: "full", help: "连元素清单与结构化文本一起打印"},
		{names: []string{"--text-only"}, dest: "text_only",
			help: "仅输出脱敏后的结构化文本，不重复列出元素"},
		{names: []string{"--viewport-expansion"}, dest: "viewport_expansion", takesValue: true,
			metavar: "VIEWPORT_EXPANSION", help: "扩展快照视口像素；-1 纳入全部元素"},
		{names: []string{"--include-frames"}, dest: "include_frames", help: "纳入跨域 iframe"},
	},
	"js": {
		{names: []string{"script"}, dest: "script", metavar: "SCRIPT",
			help: "脚本内容,或 @脚本.js,或 - 读标准输入"},
		{names: []string{"--var"}, dest: "var", takesValue: true, repeatable: true,
			help: "注入 {{变量}},写成 k=v,可重复"},
		{names: []string{"--retry-on-spurious", "--retry-spurious"}, dest: "retry_on_spurious",
			help: "脚本只读、重发无害时打开:页面上报 Object doesn't exist 这类伪故障时由服务端自动重发"},
	},
	"selftest": {{names: []string{"--browser"}, dest: "browser", takesValue: true, metavar: "BROWSER",
		help: "自检时用哪个浏览器(默认服务配置)"}},
	"server": {
		{names: []string{"action"}, dest: "action", metavar: "ACTION",
			help: "init / build / start / stop / restart / status / logs"},
		{names: []string{"--repo-dir"}, dest: "repo_dir", takesValue: true, metavar: "REPO_DIR",
			help: "仓库根目录(默认读 ~/.dsb/config.json,或从当前目录向上探测)"},
		{names: []string{"--jar"}, dest: "jar", takesValue: true, metavar: "JAR",
			help: "后端 jar 的绝对路径(默认按优先级自动找)"},
		{names: []string{"--engine"}, dest: "engine", takesValue: true, metavar: "ENGINE",
			help: "浏览器引擎 auto/chromium/chrome/edge/firefox(默认用服务端配置)"},
		{names: []string{"--keep-browser"}, dest: "keep_browser",
			help: "stop 时只结束进程,不让服务先关掉共享浏览器"},
		{names: []string{"--lines"}, dest: "lines", takesValue: true, metavar: "LINES",
			help: "logs 打印的尾部行数(默认 40)"},
		{names: []string{"--note"}, dest: "note", takesValue: true, metavar: "NOTE",
			help: "target add 时给这个目标加一句备注"},
	},
}

// commandsWithOutputSwitches 与 Python 版的循环一致。
var commandsWithOutputSwitches = map[string]bool{
	"health": true, "methods": true, "config": true, "tasks": true,
	"start": true, "close": true, "state": true, "run": true, "js": true,
}

// requiredPositionals 是各子命令必填的位置参数。
var requiredPositionals = map[string][]string{
	"run":    {"method"},
	"job":    {"job_id"},
	"upload": {"file"},
	"js":     {"script"},
	"server": {"action"},
}

// Args 是解析结果。指针字段的 nil 表示「命令行没给」,留给环境变量或内置默认值接管。
type Args struct {
	Command     string
	Positionals []string

	BaseURL       *string
	Host          *string
	Port          *int
	Use           *string
	ID            *string
	Timeout       float64
	Session       *string
	RecordDir     *string
	NoRecord      bool
	NoAutoStart   bool
	JSON          bool
	Select        *string
	SelectLenient bool
	Compact       bool
	ResponseMode  *string
	Diagnostics   bool
	Index         *int
	Verbose       bool

	Filter            *string
	Run               *string
	Var               []string
	Browser           *string
	Headful           bool
	Params            *string
	Param             []string
	RetrySpur         bool
	Method            *string
	Script            *string
	Out               *string
	Grep              *string
	File              *string
	KeepGoing         bool
	StopExpect        bool
	AsyncMode         bool
	Wait              bool
	Poll              *float64
	WaitTimeout       *float64
	MaxDurationMs     *int
	JobID             *string
	Filename          *string
	Delete            *string
	MaxElements       *int
	Full              bool
	TextOnly          bool
	ViewportExpansion *int
	IncludeFrames     bool

	// server 子命令
	Action      *string
	RepoDir     *string
	Jar         *string
	Engine      *string
	KeepBrowser bool
	Lines       *int
	Note        *string
}

// specsFor 拼出某个子命令认识的**全部**选项:通用 + 专属 + (按需)输出开关。
// 解析时与打印帮助时都用它,免得两处漏挂不一致。
func specsFor(command string) []argSpec {
	specs := append([]argSpec{}, commonSpecs()...)
	specs = append(specs, subcommandSpecs[command]...)
	if commandsWithOutputSwitches[command] {
		specs = append(specs, outputSwitches()...)
	}
	return specs
}

// specIndex 是「选项拼写 → spec」的查找表,外加前缀匹配所需的全部拼写。
type specIndex struct {
	byName map[string]*argSpec
	names  []string
}

func newSpecIndex(specs []argSpec) *specIndex {
	index := &specIndex{byName: map[string]*argSpec{}}
	for position := range specs {
		spec := &specs[position]
		for _, name := range spec.names {
			if strings.HasPrefix(name, "-") {
				index.byName[name] = spec
				index.names = append(index.names, name)
			}
		}
	}
	sort.Strings(index.names)
	return index
}

// lookup 先精确命中,再按 argparse 的规则做唯一前缀匹配。
func (index *specIndex) lookup(name string) (*argSpec, error) {
	if spec, ok := index.byName[name]; ok {
		return spec, nil
	}
	if !strings.HasPrefix(name, "--") {
		return nil, nil
	}
	matches := map[string]*argSpec{}
	for _, candidate := range index.names {
		if strings.HasPrefix(candidate, name) {
			matches[candidate] = index.byName[candidate]
		}
	}
	switch len(matches) {
	case 0:
		return nil, nil
	case 1:
		for _, spec := range matches {
			return spec, nil
		}
	}
	spellings := make([]string, 0, len(matches))
	for spelling := range matches {
		spellings = append(spellings, spelling)
	}
	sort.Strings(spellings)
	return nil, usageErrorf("ambiguous option: %s could match %s", name, strings.Join(spellings, ", "))
}

// isNegativeNumber 判断一个记号是不是负数(与 argparse 的 _negative_number_matcher 同口径)。
//
// 有它才能让 `--viewport-expansion -1` 里的 -1 被当成值。判据就是
// `^-[0-9]+$` 或 `^-[0-9]*\.[0-9]+$`。
//
// 以前写得更松(以 - 开头、至少一个数字、其余只允许数字或点、+- 只允许在开头),
// 于是 `-1-2`、`-+1`、`-1.` 都被当成负数 —— 那些其实是**写错的选项**,
// 该按用法错报出来,而不是被悄悄吞成一个值。
func isNegativeNumber(token string) bool {
	body, ok := strings.CutPrefix(token, "-")
	if !ok || body == "" {
		return false
	}
	digits := func(text string) bool {
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
	if digits(body) {
		return true
	}
	integer, fraction, found := strings.Cut(body, ".")
	if !found || !digits(fraction) {
		return false
	}
	return integer == "" || digits(integer)
}

// looksLikeOption 判断一个记号是不是「看起来像选项」——
// 决定一个等值的选项后面跟的这个记号会不会被当成它的值。
func looksLikeOption(token string) bool {
	if len(token) < 2 || token[0] != '-' {
		return false
	}
	return !isNegativeNumber(token)
}

// parseArgs 解析命令行。返回 UsageError 表示用法错(退出码 3)。
func parseArgs(argv []string) (*Args, error) {
	args := &Args{Timeout: DefaultTimeout}
	common := newSpecIndex(commonSpecs())
	values := map[string]any{} // dest → 值(字符串形式,后面再转类型)
	flags := map[string]bool{} // dest → 开关
	repeated := map[string][]string{}

	var subIndex *specIndex
	positionals := []string{}
	noMoreOptions := false

	// take 记录一个选项的值。
	take := func(spec *argSpec, raw string) error {
		if spec.repeatable {
			repeated[spec.dest] = append(repeated[spec.dest], raw)
			return nil
		}
		values[spec.dest] = raw
		return nil
	}

	for position := 0; position < len(argv); position++ {
		token := argv[position]

		if !noMoreOptions && token == "--" {
			noMoreOptions = true
			continue
		}

		if !noMoreOptions && strings.HasPrefix(token, "--") && len(token) > 2 {
			name := token
			inline := ""
			hasInline := false
			if cut := strings.Index(token, "="); cut >= 0 {
				name, inline, hasInline = token[:cut], token[cut+1:], true
			}
			if name == "--help" {
				return nil, &helpRequested{Command: args.Command}
			}
			if name == "--version" {
				return nil, &versionRequested{}
			}
			spec, err := common.lookup(name)
			if err != nil {
				return nil, err
			}
			if spec == nil && subIndex != nil {
				spec, err = subIndex.lookup(name)
				if err != nil {
					return nil, err
				}
			}
			if spec == nil && args.Command == "" {
				// 子命令还没出现,但可能就是子命令自己的选项写在子命令前面?argparse 不认这种,
				// 所以这里也报「无法识别的参数」。
				spec = nil
			}
			if spec == nil {
				return nil, usageErrorf("unrecognized arguments: %s", token)
			}
			if !spec.takesValue {
				if hasInline {
					return nil, usageErrorf("argument %s: ignored explicit argument %s", name, pyRepr(inline))
				}
				flags[spec.dest] = true
				continue
			}
			if hasInline {
				if err := take(spec, inline); err != nil {
					return nil, err
				}
				continue
			}
			next := position + 1
			if next >= len(argv) || looksLikeOption(argv[next]) {
				return nil, usageErrorf("argument %s: expected one argument", name)
			}
			position = next
			if err := take(spec, argv[next]); err != nil {
				return nil, err
			}
			continue
		}

		if !noMoreOptions && strings.HasPrefix(token, "-") && len(token) > 1 && !isNegativeNumber(token) {
			name := token
			attached := ""
			hasAttached := false
			// 短选项允许粘值(-purl=x);先把前两个字符当选项名试一次
			if len(token) > 2 {
				name, attached, hasAttached = token[:2], token[2:], true
			}
			if name == "-h" {
				return nil, &helpRequested{Command: args.Command}
			}
			spec, err := common.lookup(name)
			if err != nil {
				return nil, err
			}
			if spec == nil && subIndex != nil {
				spec, err = subIndex.lookup(name)
				if err != nil {
					return nil, err
				}
			}
			if spec == nil {
				return nil, usageErrorf("unrecognized arguments: %s", token)
			}
			if !spec.takesValue {
				if hasAttached {
					return nil, usageErrorf("unrecognized arguments: %s", token)
				}
				flags[spec.dest] = true
				continue
			}
			if hasAttached {
				if err := take(spec, attached); err != nil {
					return nil, err
				}
				continue
			}
			next := position + 1
			if next >= len(argv) || looksLikeOption(argv[next]) {
				return nil, usageErrorf("argument %s: expected one argument", name)
			}
			position = next
			if err := take(spec, argv[next]); err != nil {
				return nil, err
			}
			continue
		}

		// 非选项记号:先看是不是子命令
		if args.Command == "" && isCommandName(token) {
			args.Command = token
			subIndex = newSpecIndex(specsFor(token))
			continue
		}
		positionals = append(positionals, token)
	}

	if args.Command == "" {
		if len(positionals) > 0 {
			return nil, usageErrorf("argument command: invalid choice: %s (choose from %s)",
				pyRepr(positionals[0]), quotedList(commandNames))
		}
		return nil, usageErrorf("the following arguments are required: command")
	}

	// 位置参数按子命令的定义分配
	if err := assignPositionals(args, positionals); err != nil {
		return nil, err
	}
	if err := assignOptions(args, values, flags, repeated); err != nil {
		return nil, err
	}
	return args, nil
}

// variadicCommands 允许「比声明的还多」的位置参数,多出来的原样收进 args.Positionals。
// server 用得上:`server target add <名字>` 有三个位置参数,但只有第一个(action)是声明过的。
var variadicCommands = map[string]bool{"server": true}

// assignPositionals 把裸记号分给子命令的位置参数(顺序即定义顺序)。
func assignPositionals(args *Args, positionals []string) error {
	names := []string{}
	for _, spec := range subcommandSpecs[args.Command] {
		if !strings.HasPrefix(spec.names[0], "-") {
			names = append(names, spec.names[0])
		}
	}
	if len(positionals) > len(names) && !variadicCommands[args.Command] {
		return usageErrorf("unrecognized arguments: %s", strings.Join(positionals[len(names):], " "))
	}
	assigned := map[string]string{}
	for index, name := range names {
		if index < len(positionals) {
			assigned[name] = positionals[index]
		}
	}
	missing := []string{}
	for _, name := range requiredPositionals[args.Command] {
		if _, ok := assigned[name]; !ok {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return usageErrorf("the following arguments are required: %s", strings.Join(missing, ", "))
	}
	args.Positionals = positionals
	args.Filter = stringOrNil(assigned, "filter")
	args.File = stringOrNil(assigned, "file")
	args.JobID = stringOrNil(assigned, "job_id")
	args.Filename = stringOrNil(assigned, "filename")
	args.Action = stringOrNil(assigned, "action")
	if _, ok := assigned["method"]; ok {
		value := assigned["method"]
		args.Method = &value
	}
	if _, ok := assigned["script"]; ok {
		value := assigned["script"]
		args.Script = &value
	}
	return nil
}

func stringOrNil(source map[string]string, key string) *string {
	if value, ok := source[key]; ok {
		return &value
	}
	return nil
}

// assignOptions 把攒下来的选项值转成 Args 字段。
func assignOptions(args *Args, values map[string]any, flags map[string]bool, repeated map[string][]string) error {
	readString := func(dest string) *string {
		if raw, ok := values[dest]; ok {
			text := raw.(string)
			return &text
		}
		return nil
	}
	readInt := func(dest string) (*int, error) {
		raw, ok := values[dest]
		if !ok {
			return nil, nil
		}
		parsed, err := strconv.Atoi(strings.TrimSpace(raw.(string)))
		if err != nil {
			return nil, usageErrorf("argument --%s: invalid int value: %s",
				strings.ReplaceAll(dest, "_", "-"), pyRepr(raw.(string)))
		}
		return &parsed, nil
	}
	readFloat := func(dest string) (*float64, error) {
		raw, ok := values[dest]
		if !ok {
			return nil, nil
		}
		parsed, err := strconv.ParseFloat(strings.TrimSpace(raw.(string)), 64)
		if err != nil {
			return nil, usageErrorf("argument --%s: invalid float value: %s",
				strings.ReplaceAll(dest, "_", "-"), pyRepr(raw.(string)))
		}
		return &parsed, nil
	}

	args.BaseURL = readString("base_url")
	args.Host = readString("host")
	args.Session = readString("session")
	args.RecordDir = readString("record_dir")
	args.ID = readString("id")
	args.Use = readString("use")
	args.Select = readString("select")
	args.SelectLenient = flags["select_lenient"]
	args.ResponseMode = readString("response_mode")
	args.Run = readString("run")
	args.Browser = readString("browser")
	args.Params = readString("params")
	args.Out = readString("out")
	args.Grep = readString("grep")
	args.Delete = readString("delete")
	args.RepoDir = readString("repo_dir")
	args.Jar = readString("jar")
	args.Engine = readString("engine")
	args.Note = readString("note")

	var err error
	if args.Port, err = readInt("port"); err != nil {
		return err
	}
	if args.Index, err = readInt("index"); err != nil {
		return err
	}
	if args.MaxElements, err = readInt("max_elements"); err != nil {
		return err
	}
	if args.ViewportExpansion, err = readInt("viewport_expansion"); err != nil {
		return err
	}
	if args.MaxDurationMs, err = readInt("max_duration_ms"); err != nil {
		return err
	}
	if args.Lines, err = readInt("lines"); err != nil {
		return err
	}
	if args.Poll, err = readFloat("poll"); err != nil {
		return err
	}
	if args.WaitTimeout, err = readFloat("wait_timeout"); err != nil {
		return err
	}
	if raw, ok := values["timeout"]; ok {
		parsed, parseErr := strconv.ParseFloat(strings.TrimSpace(raw.(string)), 64)
		if parseErr != nil {
			return usageErrorf("argument --timeout: invalid float value: %s", pyRepr(raw.(string)))
		}
		args.Timeout = parsed
	}
	args.Var = repeated["var"]
	args.Param = repeated["param"]

	args.NoRecord = flags["no_record"]
	args.NoAutoStart = flags["no_auto_start"]
	args.JSON = flags["json"]
	args.Compact = flags["compact"]
	args.Diagnostics = flags["diagnostics"]
	args.Verbose = flags["verbose"]
	args.Headful = flags["headful"]
	args.RetrySpur = flags["retry_on_spurious"]
	args.KeepGoing = flags["keep_going"]
	args.StopExpect = flags["stop_on_expect_failure"]
	args.AsyncMode = flags["async_mode"]
	args.Wait = flags["wait"]
	args.Full = flags["full"]
	args.TextOnly = flags["text_only"]
	args.IncludeFrames = flags["include_frames"]
	args.KeepBrowser = flags["keep_browser"]
	return nil
}

func isCommandName(name string) bool {
	for _, candidate := range commandNames {
		if candidate == name {
			return true
		}
	}
	return false
}

func quotedList(items []string) string {
	quoted := make([]string, 0, len(items))
	for _, item := range items {
		quoted = append(quoted, "'"+item+"'")
	}
	return strings.Join(quoted, ", ")
}

// helpRequested / versionRequested 是「打印完就退出 0」的控制流信号。
//
// helpRequested 带上已经认出来的子命令:dsb state --help 应该打 state 的帮助,
// 而不是每次都把整页全局帮助糊出来 —— 那等于没有 per-command 帮助。
type helpRequested struct{ Command string }

func (e *helpRequested) Error() string { return "help" }

type versionRequested struct{}

func (e *versionRequested) Error() string { return "version" }
