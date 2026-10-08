package main

import (
	"fmt"
	"os"
	"strings"
)

// subcommandMisuseHint 给「把子命令当成 run 的方法名」这种用法错补一句对症的提示。
//
// 实测踩过:照着文档敲 `dsb run js @脚本.js`(前面还带着 --port/--id),只拿到一句
// `用法错:unrecognized arguments: @脚本.js` —— 真正的原因是 js / batch / state 这些是**子命令**,
// 不是 run 的方法名,而 argparse 的通用提示完全指不到这一点,只能去翻 --help。
func subcommandMisuseHint(argv []string) string {
	bare := []string{}
	for _, item := range argv {
		if !strings.HasPrefix(item, "-") {
			bare = append(bare, item)
		}
	}
	runAt := -1
	for index, item := range bare {
		if item == "run" {
			runAt = index
			break
		}
	}
	if runAt < 0 || runAt+1 >= len(bare) {
		return ""
	}
	name := bare[runAt+1]
	if !isCommandName(name) || subcommandAlsoMethod[name] {
		return ""
	}
	return fmt.Sprintf("提示:%s 是子命令,不是 run 的方法名 —— 直接写成 `dsb %s ...`"+
		"(例如 `dsb js @脚本.js`、`dsb batch cmds.json`、`dsb state --full`);"+
		"run 只用来调服务端方法,例如 `dsb run go_to_url -p url=https://example.com`", name, name)
}

// usageHint 是每条用法错后面统一补的那句。
func usageHint() string {
	return "提示:dsb --help 看用法,dsb methods 看服务端支持的命令"
}

// printHelp 打印帮助。文案按 Python 版 --help 的结构组织。
func printHelp(command string) {
	writer := os.Stdout
	if command == "" {
		fmt.Fprintf(writer, "usage: dsb [-h] [--version] [--base-url BASE_URL] [--host HOST] [--port PORT]\n")
		fmt.Fprintf(writer, "           [--id ID] [--timeout TIMEOUT] [--session SESSION]\n")
		fmt.Fprintf(writer, "           [--record-dir RECORD_DIR] [--no-record] [--no-auto-start]\n")
		fmt.Fprintf(writer, "           [--json] [--select PATH]\n")
		fmt.Fprintf(writer, "           [--compact] [--response-mode MODE] [--diagnostics] [--index INDEX]\n")
		fmt.Fprintf(writer, "           [-v]\n")
		fmt.Fprintf(writer, "           {%s}\n           ...\n\n", strings.Join(commandNames, ","))
		fmt.Fprintf(writer, "deepseek-browser-use 命令行客户端(Go 静态二进制,装进 PATH 后任何目录可直接敲)\n\n")
		fmt.Fprintf(writer, "子命令:\n")
		for _, name := range commandNames {
			fmt.Fprintf(writer, "  %-12s %s\n", name, commandHelp[name])
		}
		fmt.Fprintf(writer, "\n通用选项(放在子命令前面或后面都行):\n")
		for _, spec := range commonSpecs() {
			fmt.Fprintf(writer, "  %-22s %s\n", strings.Join(spec.names, ", "), spec.help)
		}
		fmt.Fprintf(writer, "\n通用选项放在子命令前面或后面都行。\n")
		fmt.Fprintf(writer, "退出码:0 成功 / 1 传输或协议错 / 2 业务失败 / 3 用法错。\n")
		fmt.Fprintf(writer, "环境变量:DSB_BASE_URL、DSB_HOST、DSB_PORT、DSB_TASK_ID、DSB_SESSION、DSB_REPO_DIR、DSB_AUTO_START。\n")
		fmt.Fprintf(writer, "后端服务:用 `dsb server start|stop|status|logs|target` 管理;连不上本机服务时会自动拉起。\n")
		fmt.Fprintf(writer, "连别的主机:--host/--port 或登记一个具名目标(dsb server target add <名字> --host <主机> --port <端口>),之后 --use <名字>。\n")
		fmt.Fprintf(writer, "例子:dsb --port 10049 health | dsb start --browser firefox | "+
			"dsb run go_to_url -p url=https://example.com | dsb batch cmds.json --async --wait\n")
		return
	}
	specs := specsFor(command)
	positionalNames := []string{}
	for _, spec := range subcommandSpecs[command] {
		if !strings.HasPrefix(spec.names[0], "-") {
			positionalNames = append(positionalNames, spec.metavar)
		}
	}
	fmt.Fprintf(writer, "usage: dsb %s [-h] [选项]", command)
	if len(positionalNames) > 0 {
		fmt.Fprintf(writer, " %s", strings.Join(positionalNames, " "))
	}
	fmt.Fprintf(writer, "\n\n%s\n\n选项:\n", commandHelp[command])
	for _, spec := range specs {
		defaultValue := ""
		if spec.takesValue && spec.metavar != "" {
			defaultValue = " " + spec.metavar
		}
		fmt.Fprintf(writer, "  %-22s %s\n", strings.Join(spec.names, ", ")+defaultValue, spec.help)
	}
}
