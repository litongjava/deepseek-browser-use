package main

// 目标服务(host + port)的解析与登记。
//
// 为什么要单独一层:后端服务既可能在本机、也可能在别的机器上(团队共用一台跑浏览器),
// 同一台机器上还可能同时跑好几个实例(不同端口 = 不同 profile、不同登录态)。
// 命令行写全(--base-url / --host --port)当然可以,但日常没人愿意每次敲一长串 IP;
// 所以这里提供一个「具名目标」的小登记表,让 `dsb --use lab state` 这种写法能工作。
//
// 两个约束值得先讲明白:
//
//  1. **自动拉起(把后端起起来)只对「本机」目标有意义**。远端主机上的服务得由那台机器自己起 ——
//     客户端没有理由去启动别人的进程。所以远端目标只连不发;连不上时给的是明确的提示,
//     而不是傻等着超时或去本机拉一个服务(那样只会把「地址写错了」变成「起了个没用的服务再失败」)。
//  2. **配置文件的语义变了**。以前 ~/.dsb/config.json 就一个 repoDir;现在多出 host/port
//     才让它成为一份真正的「客户端配置」。老文件(只有 repoDir)照常能读,字段缺失就用默认值。

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Target 是一个「具名目标」:后端服务在哪个主机、哪个端口,以及它的仓库在哪。
//
// RepoDir 只对本机目标有意义(自动拉起、找 jar 要用);远端目标留空。
type Target struct {
	Host    string `json:"host,omitempty"`
	Port    int    `json:"port,omitempty"`
	RepoDir string `json:"repoDir,omitempty"`
	SSH     string `json:"ssh,omitempty"` // 可选:预留,便于未来在远端拉起
	Note    string `json:"note,omitempty"`
}

// Config 是 ~/.dsb/config.json 的内容。
//
// 向后兼容:老版本只有一个 repoDir 字段,那时它既表示「仓库在哪」也表示「服务在本机」。
// 现在它仍然表示「仓库在哪」,而 host/port 缺省就是本机 10049 —— 所以老文件不用改。
type Config struct {
	// RepoDir 是默认仓库根(本机目标用)。
	RepoDir string `json:"repoDir,omitempty"`
	// Jar 可选:显式指定本机要用的后端 jar。
	Jar string `json:"jar,omitempty"`
	// Host / Port 是「没给 --host/--port/--use 时」的默认目标。
	Host string `json:"host,omitempty"`
	Port int    `json:"port,omitempty"`
	// Targets 是具名目标表:名字 → 主机/端口/仓库。
	Targets map[string]Target `json:"targets,omitempty"`
}

// configFilePath 返回配置文件的绝对路径(~/.dsb/config.json)。
func configFilePath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", usageErrorf("定位不到用户主目录:%v", err)
	}
	return filepath.Join(home, ".dsb", "config.json"), nil
}

// loadConfig 读配置文件。文件不存在时返回 (空配置, 路径, nil) —— 调用方不必区分「没有」与「空」。
func loadConfig() (*Config, string, error) {
	path, err := configFilePath()
	if err != nil {
		return nil, "", err
	}
	data, readErr := os.ReadFile(path)
	if readErr != nil {
		if os.IsNotExist(readErr) {
			return &Config{}, path, nil
		}
		return nil, path, usageErrorf("读不了配置文件 %s:%v", path, readErr)
	}
	value, parseErr := DecodeJSON(strings.TrimPrefix(string(data), "\ufeff"))
	if parseErr != nil {
		return nil, path, usageErrorf("配置文件不是合法 JSON(%s):%v", path, parseErr)
	}
	config := &Config{
		RepoDir: objStr(value, "repoDir"),
		Jar:     objStr(value, "jar"),
		Host:    objStr(value, "host"),
		Targets: map[string]Target{},
	}
	if port, ok := objGet(value, "port").(json.Number); ok {
		if parsed, convErr := strconv.Atoi(string(port)); convErr == nil {
			config.Port = parsed
		}
	}
	if targets := asObj(objGet(value, "targets")); targets != nil {
		for _, name := range targets.Keys() {
			entry, _ := targets.Get(name)
			target := Target{
				Host:    objStr(entry, "host"),
				RepoDir: objStr(entry, "repoDir"),
				SSH:     objStr(entry, "ssh"),
				Note:    objStr(entry, "note"),
			}
			if port, ok := objGet(entry, "port").(json.Number); ok {
				if parsed, convErr := strconv.Atoi(string(port)); convErr == nil {
					target.Port = parsed
				}
			}
			config.Targets[name] = target
		}
	}
	return config, path, nil
}

// saveConfig 写配置文件(目录不存在就建)。
func saveConfig(config *Config) (string, error) {
	path, err := configFilePath()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return "", usageErrorf("建不了配置目录 %s:%v", filepath.Dir(path), err)
	}
	object := NewObj()
	if config.RepoDir != "" {
		object.Set("repoDir", config.RepoDir)
	}
	if config.Jar != "" {
		object.Set("jar", config.Jar)
	}
	if config.Host != "" {
		object.Set("host", config.Host)
	}
	if config.Port != 0 {
		object.Set("port", numberFromInt(config.Port))
	}
	if len(config.Targets) > 0 {
		targets := NewObj()
		names := make([]string, 0, len(config.Targets))
		for name := range config.Targets {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			target := config.Targets[name]
			entry := NewObj()
			if target.Host != "" {
				entry.Set("host", target.Host)
			}
			if target.Port != 0 {
				entry.Set("port", numberFromInt(target.Port))
			}
			if target.RepoDir != "" {
				entry.Set("repoDir", target.RepoDir)
			}
			if target.SSH != "" {
				entry.Set("ssh", target.SSH)
			}
			if target.Note != "" {
				entry.Set("note", target.Note)
			}
			targets.Set(name, entry)
		}
		object.Set("targets", targets)
	}
	if err := writeTextFile(path, EncodeJSON(object, encIndent2)); err != nil {
		return "", usageErrorf("写不了配置文件 %s:%v", path, err)
	}
	return path, nil
}

// ------------------------------------------------------------------ 目标解析

// Endpoint 是最终要连的服务地址,外加「它是不是本机」这个关键判断。
type Endpoint struct {
	BaseURL string
	Host    string
	Port    int
	// Local 为真才允许自动拉起后端(见文件头第 1 条约束)。
	Local bool
	// Origin 说明这个地址是从哪来的(命令行 / 具名目标 / 配置文件 / 环境变量 / 默认),
	// 报错时要能一眼看出「我以为连 A,其实连的是 B」。
	Origin string
}

// isLocalHost 判断主机名是不是本机。
//
// 只有这几种才算本机:localhost、127.0.0.0/8、::1、0.0.0.0(监听通配,本地也能连)。
// 其余一律当成远端 —— 宁可少拉起一次,也不要在别人的地址上瞎连。
func isLocalHost(host string) bool {
	trimmed := strings.Trim(strings.ToLower(strings.TrimSpace(host)), "[]")
	switch trimmed {
	case "", "localhost", "127.0.0.1", "0.0.0.0", "::1", "::":
		return true
	}
	if strings.HasPrefix(trimmed, "127.") {
		return true
	}
	// 本机主机名也算本机
	if name, err := os.Hostname(); err == nil && strings.EqualFold(name, trimmed) {
		return true
	}
	return false
}

// resolveEndpoint 决定这次要连哪。
//
// 优先级(高 → 低):
//  1. `--base-url`(写全了,听它的)
//  2. `--use <名字>`(具名目标)
//  3. `--host` / `--port`(任给一个都算)
//  4. 环境变量 DSB_BASE_URL > DSB_HOST/DSB_PORT
//  5. 配置文件里的 host/port
//  6. 内置默认 localhost:10049
func resolveEndpoint(args *Args) (Endpoint, error) {
	config, configPath, configErr := loadConfig()
	if configErr != nil {
		// 配置文件坏了不该让所有命令都不可用:能读到的部分照用,读不到就退回默认
		fmt.Fprintln(os.Stderr, "警告:"+configErr.Error())
		config = &Config{}
	}

	// 1) 完整地址
	if args.BaseURL != nil && *args.BaseURL != "" {
		return endpointFromURL(*args.BaseURL, "命令行 --base-url")
	}
	if value := os.Getenv("DSB_BASE_URL"); value != "" {
		return endpointFromURL(value, "环境变量 DSB_BASE_URL")
	}

	// 2) 具名目标
	if args.Use != nil && *args.Use != "" {
		name := *args.Use
		target, ok := config.Targets[name]
		if !ok {
			return Endpoint{}, unknownTargetError(name, config, configPath)
		}
		host := target.Host
		if host == "" {
			host = DefaultHost
		}
		port := target.Port
		if port == 0 {
			port = DefaultPort
		}
		// 具名目标里如果记了 repoDir,覆盖默认仓库位置(方便 --use 之后直接 server start)
		if target.RepoDir != "" {
			os.Setenv("DSB_REPO_DIR", target.RepoDir)
		}
		return Endpoint{BaseURL: baseURLFor(host, port), Host: host, Port: port,
			Local: isLocalHost(host), Origin: "具名目标 " + name}, nil
	}

	// 3) 命令行 host/port
	host, port, origin := "", 0, ""
	if args.Host != nil && *args.Host != "" {
		host, origin = *args.Host, "命令行 --host"
	}
	if args.Port != nil {
		port = *args.Port
		if origin == "" {
			origin = "命令行 --port"
		}
	}
	if host != "" || port != 0 {
		if host == "" {
			host = configDefaultHost(config)
		}
		if port == 0 {
			port = configDefaultPort(config)
		}
		return Endpoint{BaseURL: baseURLFor(host, port), Host: host, Port: port,
			Local: isLocalHost(host), Origin: origin}, nil
	}

	// 4) 环境变量 host/port
	if value := os.Getenv("DSB_HOST"); value != "" {
		return Endpoint{BaseURL: baseURLFor(value, envPort(config)), Host: value, Port: envPort(config),
			Local: isLocalHost(value), Origin: "环境变量 DSB_HOST"}, nil
	}
	if value := os.Getenv("DSB_PORT"); value != "" {
		parsed, err := jsonNumberInt(value)
		if err != nil {
			return Endpoint{}, usageErrorf("DSB_PORT 必须是数字,收到的是:%s", pyRepr(value))
		}
		host := configDefaultHost(config)
		return Endpoint{BaseURL: baseURLFor(host, parsed), Host: host, Port: parsed,
			Local: isLocalHost(host), Origin: "环境变量 DSB_PORT"}, nil
	}

	// 5) 配置文件
	if config.Host != "" || config.Port != 0 {
		host := configDefaultHost(config)
		port := configDefaultPort(config)
		return Endpoint{BaseURL: baseURLFor(host, port), Host: host, Port: port,
			Local: isLocalHost(host), Origin: "配置文件"}, nil
	}

	// 6) 内置默认
	return Endpoint{BaseURL: baseURLFor(DefaultHost, DefaultPort), Host: DefaultHost,
		Port: DefaultPort, Local: true, Origin: "默认"}, nil
}

// endpointFromURL 从完整 URL 拆出主机与端口。
func endpointFromURL(raw string, origin string) (Endpoint, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return Endpoint{}, usageErrorf("地址不合法:%s(%v)", raw, err)
	}
	host := parsed.Hostname()
	if host == "" {
		return Endpoint{}, usageErrorf("地址里没有主机名:%s", raw)
	}
	port := 80
	switch {
	case parsed.Port() != "":
		parsedPort, convErr := strconv.Atoi(parsed.Port())
		if convErr != nil {
			return Endpoint{}, usageErrorf("地址里的端口不是数字:%s", raw)
		}
		port = parsedPort
	case parsed.Scheme == "https":
		port = 443
	}
	return Endpoint{BaseURL: strings.TrimRight(raw, "/"), Host: host, Port: port,
		Local: isLocalHost(host), Origin: origin}, nil
}

func configDefaultHost(config *Config) string {
	if config.Host != "" {
		return config.Host
	}
	return DefaultHost
}

func configDefaultPort(config *Config) int {
	if config.Port != 0 {
		return config.Port
	}
	return DefaultPort
}

func envPort(config *Config) int {
	if value := os.Getenv("DSB_PORT"); value != "" {
		if parsed, err := jsonNumberInt(value); err == nil {
			return parsed
		}
	}
	return configDefaultPort(config)
}

// baseURLFor 拼 base URL。IPv6 字面量要加方括号。
func baseURLFor(host string, port int) string {
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	return fmt.Sprintf("http://%s:%d", host, port)
}

// unknownTargetError 给出「名字写错了」时最有用的提示:把已登记的名字列出来。
func unknownTargetError(name string, config *Config, configPath string) error {
	names := make([]string, 0, len(config.Targets))
	for targetName := range config.Targets {
		names = append(names, targetName)
	}
	sort.Strings(names)
	if len(names) == 0 {
		return usageErrorf("没有登记过目标 %q(配置文件 %s 里一个目标都没有)\n"+
			"提示:用 `dsb server target add %s --host <主机> --port <端口>` 登记一个", name, configPath, name)
	}
	return usageErrorf("没有登记过目标 %q;已登记的有:%s\n"+
		"提示:`dsb server target list` 看全部,`dsb server target add %s --host <主机> --port <端口>` 新增",
		name, strings.Join(names, "、"), name)
}
