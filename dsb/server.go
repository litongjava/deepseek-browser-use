package main

// `dsb server` 子命令族:管理后端服务的生命周期。
//
// 子命令:init / build / start / stop / restart / status / logs。
// 这一族不需要连服务也能跑(status 与 start 自己会探活),所以它们不走 Client 那套。

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// filepathAbs 是 filepath.Abs 的短别名,让 server.go 的 import 少一层。
func filepathAbs(path string) (string, error) { return filepath.Abs(path) }

// cmdServer 派发 server 的子动作。
func cmdServer(args *Args, out *Printer) (int, error) {
	action := ""
	if len(args.Positionals) > 0 {
		action = args.Positionals[0]
	}
	if action == "" {
		return 0, usageErrorf("server 需要一个动作:init / build / start / stop / restart / status / logs / target")
	}

	port := DefaultPort
	if args.Port != nil {
		port = *args.Port
	}

	switch action {
	case "init":
		return cmdServerInit(args, out)
	case "build":
		return cmdServerBuild(args, out)
	case "target":
		return cmdServerTarget(args, out)
	case "start":
		return cmdServerStart(args, out, port)
	case "stop":
		return cmdServerStop(args, out, port)
	case "restart":
		if code, err := cmdServerStop(args, out, port); err != nil || code != ExitOK {
			return code, err
		}
		return cmdServerStart(args, out, port)
	case "status":
		return cmdServerStatus(args, out, port)
	case "logs":
		return cmdServerLogs(args, out, port)
	}
	return 0, usageErrorf("server 不认识这个动作:%s(要 init / build / start / stop / restart / status / logs / target)", action)
}

// cmdServerTarget 管理「具名目标」:把一个主机+端口记个名字,之后用 `dsb --use <名字>` 就能连。
//
// 用途是「服务在别的机器上」或「本机同时跑了好几个实例(不同端口)」时,不用每次敲 IP 和端口。
func cmdServerTarget(args *Args, out *Printer) (int, error) {
	// server target <动作> [名字] —— 第一个位置参数是 target,第二个是子动作,第三个是名字
	// (args.Positionals 里已含 "target")
	rest := args.Positionals
	if len(rest) < 2 {
		return 0, usageErrorf("server target 需要一个动作:list / add / remove\n" +
			"例:dsb server target add lab --host 10.0.0.5 --port 10049")
	}
	action := rest[1]
	name := ""
	if len(rest) > 2 {
		name = rest[2]
	}

	config, path, err := loadConfig()
	if err != nil {
		return 0, err
	}
	if config.Targets == nil {
		config.Targets = map[string]Target{}
	}

	switch action {
	case "list":
		if len(config.Targets) == 0 {
			out.Line(fmt.Sprintf("还没有登记任何目标(%s)", path))
			out.Line("加一个:dsb server target add <名字> --host <主机> --port <端口>")
			return ExitOK, nil
		}
		names := make([]string, 0, len(config.Targets))
		for targetName := range config.Targets {
			names = append(names, targetName)
		}
		sort.Strings(names)

		if args.JSON {
			items := make([]Value, 0, len(names))
			for _, targetName := range names {
				items = append(items, describeTarget(targetName, config.Targets[targetName]))
			}
			out.JSON(items)
			return ExitOK, nil
		}
		out.Line(fmt.Sprintf("已登记的目标(%s):", path))
		for _, targetName := range names {
			target := config.Targets[targetName]
			host := target.Host
			if host == "" {
				host = DefaultHost
			}
			port := target.Port
			if port == 0 {
				port = DefaultPort
			}
			scope := "远端"
			if isLocalHost(host) {
				scope = "本机"
			}
			line := fmt.Sprintf("  %-12s %s:%d(%s)", targetName, host, port, scope)
			if target.RepoDir != "" {
				line += "  repo=" + target.RepoDir
			}
			if target.Note != "" {
				line += "  # " + target.Note
			}
			out.Line(line)
		}
		out.Line("用法:dsb --use <名字> <子命令>")
		return ExitOK, nil

	case "add":
		if name == "" {
			return 0, usageErrorf("要个名字:dsb server target add <名字> --host <主机> --port <端口>")
		}
		host := configDefaultHost(config)
		if args.Host != nil {
			host = *args.Host
		}
		port := configDefaultPort(config)
		if args.Port != nil {
			port = *args.Port
		}
		target := Target{Host: host, Port: port, Note: derefOr(args.Note, "")}
		// 本机目标才记仓库位置(远端仓库在本机找不到)
		if isLocalHost(host) {
			if repoDir := derefOr(args.RepoDir, ""); repoDir != "" {
				target.RepoDir = repoDir
			} else if config.RepoDir != "" {
				target.RepoDir = config.RepoDir
			}
		}
		config.Targets[name] = target
		written, err := saveConfig(config)
		if err != nil {
			return 0, err
		}
		out.Line(fmt.Sprintf("已登记目标 %s = %s(%s)", name,
			baseURLFor(target.Host, target.Port), scopeOf(target.Host)))
		out.Line(fmt.Sprintf("  写入 %s", written))
		out.Line(fmt.Sprintf("  用法:dsb --use %s health", name))
		return ExitOK, nil

	case "remove":
		if name == "" {
			return 0, usageErrorf("要个名字:dsb server target remove <名字>")
		}
		if _, ok := config.Targets[name]; !ok {
			return 0, unknownTargetError(name, config, path)
		}
		delete(config.Targets, name)
		if _, err := saveConfig(config); err != nil {
			return 0, err
		}
		out.Line(fmt.Sprintf("已删除目标 %s", name))
		return ExitOK, nil
	}
	return 0, usageErrorf("server target 不认识这个动作:%s(要 list / add / remove)", action)
}

// describeTarget 把目标渲染成 JSON 对象。
func describeTarget(name string, target Target) Value {
	host := target.Host
	if host == "" {
		host = DefaultHost
	}
	port := target.Port
	if port == 0 {
		port = DefaultPort
	}
	item := ObjOf("name", name, "host", host, "port", numberFromInt(port),
		"baseUrl", baseURLFor(host, port), "local", isLocalHost(host))
	if target.RepoDir != "" {
		item.Set("repoDir", target.RepoDir)
	}
	if target.Note != "" {
		item.Set("note", target.Note)
	}
	return item
}

func scopeOf(host string) string {
	if isLocalHost(host) {
		return "本机"
	}
	return "远端"
}

// cmdServerBuild 构建当前代码的后端 jar,归档进 releases/<commit>/。
func cmdServerBuild(args *Args, out *Printer) (int, error) {
	repoDir, source, err := resolveRepoDir(derefOr(args.RepoDir, ""))
	if err != nil {
		return 0, err
	}
	out.Line(fmt.Sprintf("仓库目录:%s(来源:%s)", repoDir, source))
	artifact, err := buildBackend(repoDir, out)
	if err != nil {
		if _, ok := asTransport(err); ok {
			out.Warn("ERROR " + err.Error())
			return ExitTransport, nil
		}
		return 0, err
	}
	out.Line(fmt.Sprintf("构建完成:%s", artifact))
	out.Line("提示:服务在跑的话,`dsb server restart` 才会用上新构建的这份")
	return ExitOK, nil
}

// cmdServerInit 写配置文件,记住仓库在哪(顺带记下默认目标)。
func cmdServerInit(args *Args, out *Printer) (int, error) {
	explicit := derefOr(args.RepoDir, "")
	repoDir, source, err := resolveRepoDir(explicit)
	if err != nil {
		return 0, err
	}
	// 校验一下这个目录确实像仓库,免得写进去一个填错的路径,以后每次启动都失败
	if !looksLikeRepo(repoDir) {
		return 0, usageErrorf("%s 不像仓库根(里面没有 playwright-server/)。来源:%s", repoDir, source)
	}
	absolute, err := absPath(repoDir)
	if err != nil {
		return 0, usageErrorf("路径解析失败:%v", err)
	}

	// 保留已有配置(尤其是 targets),只改这几个字段 —— 别把用户登记过的目标冲掉
	config, path, err := loadConfig()
	if err != nil {
		return 0, err
	}
	if config.Targets == nil {
		config.Targets = map[string]Target{}
	}
	config.RepoDir = absolute
	if args.Jar != nil {
		config.Jar = *args.Jar
	}
	if args.Host != nil {
		config.Host = *args.Host
	}
	if args.Port != nil {
		config.Port = *args.Port
	}
	written, err := saveConfig(config)
	if err != nil {
		return 0, err
	}
	_ = path
	out.Line(fmt.Sprintf("已写入 %s", written))
	out.Line(fmt.Sprintf("  repoDir=%s", config.RepoDir))
	if config.Jar != "" {
		out.Line(fmt.Sprintf("  jar=%s", config.Jar))
	}
	if config.Host != "" || config.Port != 0 {
		out.Line(fmt.Sprintf("  默认目标=%s", baseURLFor(configDefaultHost(config), configDefaultPort(config))))
	}
	if source != "配置文件" {
		out.Line(fmt.Sprintf("  (仓库来源:%s)", source))
	}
	return ExitOK, nil
}

// cmdServerStart 起服务(已在跑就复用)。
func cmdServerStart(args *Args, out *Printer, port int) (int, error) {
	// 远端目标起不了:进程在别人机器上
	if endpoint, err := resolveEndpoint(args); err == nil && !endpoint.Local {
		return 0, usageErrorf("目标 %s 不是本机,dsb 只能在本地拉起后端。\n"+
			"提示:远端服务需要在那台机器上自己起(把 dsb 和仓库放到那边,或手工 java -jar)",
			endpoint.BaseURL)
	}

	repoDir, source, err := resolveRepoDir(derefOr(args.RepoDir, ""))
	if err != nil {
		return 0, err
	}
	out.Line(fmt.Sprintf("仓库目录:%s(来源:%s)", repoDir, source))

	// 配置文件不存在但探测到了仓库,顺手记一份:下次在别的目录也能直接用
	if config, path, err := loadConfig(); err == nil && path != "" {
		if _, statErr := os.Stat(path); os.IsNotExist(statErr) {
			config.RepoDir = repoDir
			if _, saveErr := saveConfig(config); saveErr == nil {
				out.Line(fmt.Sprintf("已记下仓库位置:%s", path))
			}
		}
	}

	timeout := DefaultStartupTimout
	if args.Timeout > 0 {
		timeout = args.Timeout
	}
	if _, err := startBackend(repoDir, port, derefOr(args.Engine, ""), derefOr(args.Jar, ""), timeout, out); err != nil {
		if _, ok := asTransport(err); ok {
			out.Warn("ERROR " + err.Error())
			return ExitTransport, nil
		}
		return 0, err
	}
	return ExitOK, nil
}

// cmdServerStop 停服务。
func cmdServerStop(args *Args, out *Printer, port int) (int, error) {
	// 远端进程停不了(和 start 一样的道理)
	if endpoint, err := resolveEndpoint(args); err == nil && !endpoint.Local {
		return 0, usageErrorf("目标 %s 不是本机,dsb 只能停本机的后端。\n"+
			"提示:远端服务需要在那台机器上停(HTTP shutdown 也可以:POST %s/playwright/command {\"method\":\"shutdown\"})",
			endpoint.BaseURL, endpoint.BaseURL)
	}
	repoDir, _, err := resolveRepoDir(derefOr(args.RepoDir, ""))
	if err != nil {
		return 0, err
	}
	if err := stopBackend(repoDir, port, args.KeepBrowser, out); err != nil {
		return 0, err
	}
	return ExitOK, nil
}

// cmdServerStatus 打印服务状态。
func cmdServerStatus(args *Args, out *Printer, port int) (int, error) {
	// 目标可能是远端:地址以 resolveEndpoint 为准,而 pid/jar 这类本机信息只在本地目标才有意义
	endpoint, endpointErr := resolveEndpoint(args)
	if endpointErr != nil {
		return 0, endpointErr
	}
	if endpoint.Port != 0 {
		port = endpoint.Port
	}

	repoDir, source, err := resolveRepoDir(derefOr(args.RepoDir, ""))
	if err != nil {
		// status 不该因为找不到仓库就失败:至少把「服务在不在」报出来
		repoDir, source = "", "找不到"
	}
	if !endpoint.Local {
		// 远端目标:不猜仓库、不查 pid,只报「连得上吗」
		repoDir, source = "", "远端目标不适用"
	}
	layout := ServerLayout{RepoDir: repoDir, Port: port}
	baseURL := endpoint.BaseURL
	healthy := probeHealth(baseURL)

	pid := 0
	pidAlive := false
	if repoDir != "" {
		if parsed, err := readPid(layout.pidFile()); err == nil {
			pid = parsed
			pidAlive = processAlive(parsed)
		}
	}
	// 全部候选都列出来,而不只是「当前会选中的那个」——
	// 排查时最常问的就是「为什么跑的是这份、还有没有更新的」,只报一个路径答不了。
	candidates := []JarCandidate{}
	if repoDir != "" {
		candidates = collectJarCandidates(repoDir, "")
	}
	selected := ""
	if len(candidates) > 0 {
		selected = candidates[0].Path
	}

	if args.JSON {
		result := ObjOf("baseUrl", baseURL, "port", numberFromInt(port), "healthy", healthy,
			"local", endpoint.Local, "endpointSource", endpoint.Origin)
		if repoDir != "" {
			result.Set("repoDir", repoDir)
			result.Set("repoSource", source)
			result.Set("pidFile", layout.pidFile())
			result.Set("outLog", layout.outLog())
			result.Set("errLog", layout.errLog())
			if pid != 0 {
				result.Set("pid", numberFromInt(pid))
				result.Set("pidAlive", pidAlive)
			}
			if selected != "" {
				result.Set("jar", selected)
			}
			items := make([]Value, 0, len(candidates))
			for _, candidate := range candidates {
				item := ObjOf("path", candidate.Path, "source", candidate.Source,
					"selected", candidate.Path == selected)
				if candidate.Detail != "" {
					item.Set("detail", candidate.Detail)
				}
				if !candidate.ModTime.IsZero() {
					item.Set("modTime", candidate.ModTime.Format(time.RFC3339))
				}
				items = append(items, item)
			}
			result.Set("candidates", items)
		}
		out.JSON(result)
		return ExitOK, nil
	}

	health := "没起"
	if healthy {
		health = "健康"
	}
	scope := "本机"
	if !endpoint.Local {
		scope = "远端"
	}
	out.Line(fmt.Sprintf("服务地址:%s(%s,%s;来源:%s)", baseURL, health, scope, endpoint.Origin))
	if !endpoint.Local {
		// 远端目标:pid / jar / 日志都在那台机器上,这里只说清「连得上吗」,不假装知道更多
		out.Line("远端目标:进程、jar、日志都在那台机器上,这里看不到")
		if !healthy {
			out.Line("连不上:确认那台机器上服务起了、端口开着、防火墙放行(远端不能自动拉起)")
		}
		return ExitOK, nil
	}
	if repoDir == "" {
		out.Line("仓库目录:找不到(跑 `dsb server init --repo-dir <仓库根>` 记一下)")
		return ExitOK, nil
	}
	out.Line(fmt.Sprintf("仓库目录:%s(来源:%s)", repoDir, source))
	if pid != 0 {
		state := "已退出"
		if pidAlive {
			state = "在运行"
		}
		out.Line(fmt.Sprintf("进程:%d(%s)", pid, state))
	} else {
		out.Line("进程:没有 pid 文件")
	}
	if len(candidates) == 0 {
		out.Line("后端 jar:没找到(跑 `dsb server build` 构建一份)")
	} else {
		out.Line("后端 jar(按优先级,★ = 启动时会用的那份):")
		for _, candidate := range candidates {
			mark := "  "
			if candidate.Path == selected {
				mark = "★ "
			}
			out.Line(mark + describeCandidate(candidate))
		}
	}
	out.Line(fmt.Sprintf("日志:%s", layout.outLog()))
	return ExitOK, nil
}

// cmdServerLogs 打印该端口日志的尾部。
func cmdServerLogs(args *Args, out *Printer, port int) (int, error) {
	repoDir, _, err := resolveRepoDir(derefOr(args.RepoDir, ""))
	if err != nil {
		return 0, err
	}
	lines := 40
	if args.Lines != nil {
		lines = *args.Lines
	}
	layout := ServerLayout{RepoDir: repoDir, Port: port}
	out.Log(fmt.Sprintf("==== %s ====", layout.outLog()))
	if text := tailFile(layout.outLog(), lines); text != "" {
		out.Log(text)
	} else {
		out.Log("(空)")
	}
	if errorText := tailFile(layout.errLog(), lines); errorText != "" {
		out.Log(fmt.Sprintf("==== %s ====", layout.errLog()))
		out.Log(errorText)
	}
	return ExitOK, nil
}

// absPath 取绝对路径。
func absPath(path string) (string, error) {
	if strings.HasPrefix(path, "~") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		path = home + strings.TrimPrefix(path, "~")
	}
	return filepathAbs(path)
}
