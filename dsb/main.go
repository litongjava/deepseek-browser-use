package main

// 入口:解析命令行 → 拼客户端 → 跑子命令 → 用退出码说人话。
//
// 退出码是这份客户端最要紧的对外契约:
//   0 成功 / 1 传输或协议错 / 2 业务失败(服务端回了 ok:false)/ 3 用法错。
// 1 与 2 必须分开 —— 前者要去看服务,后者要看自己的命令和页面。

import (
	"fmt"
	"os"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(argv []string) int {
	args, err := parseArgs(argv)
	if err != nil {
		if help, ok := err.(*helpRequested); ok {
			// 带上子命令:dsb state --help 打 state 的用法,dsb --help 才打全局
			printHelp(help.Command)
			return ExitOK
		}
		if _, ok := err.(*versionRequested); ok {
			fmt.Printf("dsb %s\n", version)
			return ExitOK
		}
		fmt.Fprintln(os.Stderr, "用法错:"+err.Error())
		if hint := subcommandMisuseHint(argv); hint != "" {
			fmt.Fprintln(os.Stderr, hint)
		}
		fmt.Fprintln(os.Stderr, usageHint())
		return ExitUsage
	}

	if args.Select != nil {
		args.JSON = true
	}
	out := NewPrinter(args)

	// `server` 管的是后端进程本身,不需要(也不该)先建一个连服务的客户端:
	// 它的一部分职责恰恰是「服务还没起的时候把它起起来」。
	if args.Command == "server" {
		code, err := cmdServer(args, out)
		return report(code, err, out)
	}

	client, err := buildClient(args)
	if err != nil {
		out.Warn("用法错:" + err.Error())
		return ExitUsage
	}

	code, err := dispatch(client, args, out)
	return report(code, err, out)
}

// report 把「命令的返回值 + 错误」翻成退出码与 stderr 文案。
func report(code int, err error, out *Printer) int {
	if err == nil {
		// --select 的路径在这条响应里不存在:这是用法错,不是成功。
		// 以前只往 stderr 提一句、stdout 改印整封,退出码还是 0 —— 按路径取值的调用方
		// 会拿到形状完全不同的东西,却看不到任何失败信号。
		if out != nil && out.SelectMissed && code == ExitOK {
			out.Warn(fmt.Sprintf("--select %s 在这条响应里不存在;stdout 已是 null。加 --select-lenient 可退回打印完整信封",
				derefOr(out.Select, "")))
			return ExitUsage
		}
		return code
	}
	if usage, ok := asUsage(err); ok {
		out.Warn("用法错:" + usage.Message)
		return ExitUsage
	}
	if transport, ok := asTransport(err); ok {
		// 传输层问题:服务没起、地址不对、超时、响应不是 JSON
		out.Warn("ERROR " + transport.Message)
		return ExitTransport
	}
	out.Warn("ERROR " + err.Error())
	return ExitTransport
}

// dispatch 把子命令派给它自己的实现。
func dispatch(client *Client, args *Args, out *Printer) (int, error) {
	switch args.Command {
	case "health":
		return cmdHealth(client, args, out)
	case "methods":
		return cmdMethods(client, args, out)
	case "config":
		return cmdConfig(client, args, out)
	case "tasks":
		return cmdTasks(client, args, out)
	case "last":
		return cmdLast(client, args, out)
	case "recipes":
		return cmdRecipes(client, args, out)
	case "start":
		return cmdStart(client, args, out)
	case "close":
		return cmdClose(client, args, out)
	case "shutdown":
		return cmdShutdown(client, args, out)
	case "run":
		return cmdRun(client, args, out)
	case "batch":
		return cmdBatch(client, args, out)
	case "job":
		return cmdJob(client, args, out)
	case "upload":
		return cmdUpload(client, args, out)
	case "uploads":
		return cmdUploads(client, args, out)
	case "state":
		return cmdState(client, args, out)
	case "js":
		return cmdJS(client, args, out)
	case "selftest":
		return cmdSelftest(client, args, out)
	}
	return 0, usageErrorf("未知子命令:%s", args.Command)
}
