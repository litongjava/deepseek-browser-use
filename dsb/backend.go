package main

// 后端服务的发现与生命周期管理。
//
// 为什么客户端要管这件事:后端(java -jar)与客户端是两件事,但「先起服务再发命令」这个顺序
// 对使用者是纯负担 —— 忘了起服务时,拿到的是一句「连不上」,而真正的修法(去哪找 jar、用哪个端口、
// 什么工作目录)客户端其实全都知道。所以:
//
//   - `dsb server start|stop|status|logs` 显式管理;
//   - 普通命令(health / run / state …)连不上服务时**自动拉起并重试一次**(见 Client.Request)。
//
// 仓库位置从哪来:用户级配置文件 ~/.dsb/config.json 里记一个 repoDir。为什么不用「相对客户端自己
// 的位置」推算:dsb 装在 PATH 上(~/.local/bin、D:\dev_gopath\bin),离仓库十万八千里,推不出来。
// 配置文件只有这一处,装到别的机器、换了仓库目录,只改这一个文件。

import (
	"fmt"
	"io"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// 后端启动参数的默认值。
const (
	DefaultEngine        = ""   // 空表示用服务端自己的默认(browser.engine=chromium)
	DefaultStartupTimout = 90.0 // 秒,与 scripts/run/start-server.* 的默认一致
)

// 配置文件(~/.dsb/config.json)的读写见 endpoint.go —— 它同时管「仓库在哪」与「连哪台主机」,
// 两件事本来就该在同一份文件里。

// ------------------------------------------------------------------ 仓库定位

// resolveRepoDir 按「环境变量 > 配置文件 > 从 cwd 向上探测」找仓库根,并说明来源。
func resolveRepoDir(explicit string) (string, string, error) {
	if explicit != "" {
		return explicit, "--repo-dir", nil
	}
	if env := os.Getenv("DSB_REPO_DIR"); env != "" {
		return env, "DSB_REPO_DIR", nil
	}
	config, _, err := loadConfig()
	if err == nil && config != nil && config.RepoDir != "" {
		return config.RepoDir, "配置文件", nil
	}
	if found := detectRepoDir(); found != "" {
		return found, "自动探测", nil
	}
	return "", "", usageErrorf("找不到仓库目录:%v\n"+
		"提示:跑一次 `dsb server init --repo-dir <仓库根>`,或设 DSB_REPO_DIR 环境变量。"+
		"仓库根就是含 playwright-server/ 与 .dsb-backend/ 的那个目录", err)
}

// detectRepoDir 从当前目录向上找「含 playwright-server/ 与 .dsb-backend/ 或 dist/ 的目录」。
func detectRepoDir() string {
	directory, err := os.Getwd()
	if err != nil {
		return ""
	}
	for {
		if looksLikeRepo(directory) {
			return directory
		}
		parent := filepath.Dir(directory)
		if parent == directory {
			return ""
		}
		directory = parent
	}
}

// looksLikeRepo 判断一个目录是不是这个仓库的根。
func looksLikeRepo(directory string) bool {
	if _, err := os.Stat(filepath.Join(directory, "playwright-server")); err != nil {
		return false
	}
	for _, marker := range []string{".dsb-backend", "dist", "pom.xml"} {
		if _, err := os.Stat(filepath.Join(directory, marker)); err == nil {
			return true
		}
	}
	return false
}

// ------------------------------------------------------------------ jar 定位

// JarCandidate 是一个可以拿来启动后端的 jar,附带「它是什么、多新」的元信息。
//
// 为什么要保留元信息而不是只留路径:三处候选(release / target / dist)在排查时含义完全不同 ——
// 「跑的是 10 天前的发行包」与「跑的是今天自己构建的 target」是两回事,只报一个路径看不出来。
type JarCandidate struct {
	Path    string // jar 的绝对路径
	Source  string // releases / target / dist / explicit(配置里指定的)
	Detail  string // releases 带 commit,dist 带 playwright 版本,其余留空
	ModTime time.Time
	Size    int64
}

// collectJarCandidates 按优先级列出全部可用 jar。
//
// 顺序是「先分组、组内再排序」:
//   - 显式指定(配置里的 jar)单独一组,它一旦存在就是唯一候选;
//   - **release 排在 target 前面**:releases/<commit>/ 是「按 commit 存档、可复现」的产物,
//     而 target/ 是随便哪次本地构建的产物(可能来自半路中断的构建、也可能改了代码没提交),
//     日常启动应该优先用前者;想用最新的代码构建出来的,显式 `dsb server build` 或 --jar 指定;
//   - dist/(发行包)放最后,它通常是几周前的快照。
func collectJarCandidates(repoDir string, explicit string) []JarCandidate {
	if explicit != "" && fileExists(explicit) {
		return []JarCandidate{candidateFor(explicit, "explicit", "")}
	}

	out := []JarCandidate{}

	// 1) .dsb-backend/releases/<commit>/backend.jar —— 多个 release 时新的排前面
	releasesDir := filepath.Join(repoDir, ".dsb-backend", "releases")
	if entries, err := os.ReadDir(releasesDir); err == nil {
		releases := []JarCandidate{}
		for _, entry := range entries {
			if !entry.IsDir() {
				continue
			}
			jarPath := filepath.Join(releasesDir, entry.Name(), "backend.jar")
			if info, err := os.Stat(jarPath); err == nil {
				releases = append(releases, candidateFor(jarPath, "releases", shortCommit(entry.Name())))
				_ = info
			}
		}
		sort.Slice(releases, func(i, j int) bool {
			return releases[i].ModTime.After(releases[j].ModTime)
		})
		out = append(out, releases...)
	}

	// 2) playwright-server/target/playwright-server-*.jar —— 本地 mvn 构建的直接产物
	targetDir := filepath.Join(repoDir, "playwright-server", "target")
	for _, match := range globJars(targetDir, "playwright-server-*.jar", "sources", "javadoc") {
		out = append(out, candidateFor(match, "target", ""))
	}

	// 3) dist/*-windows-x64.jar 之类发行包 —— 取 playwright 版本号最高的那份
	distDir := filepath.Join(repoDir, "dist")
	platform := fmt.Sprintf("-%s-%s.jar", runtime.GOOS, mapArch(runtime.GOARCH))
	distMatches := globJars(distDir, "*"+platform, "sources", "javadoc")
	if len(distMatches) > 0 {
		best := preferHighestPlaywright(distMatches)
		detail := ""
		if rev := playwrightRev(best); rev > 0 {
			detail = "playwright " + playwrightVersion(best)
		}
		out = append(out, candidateFor(best, "dist", detail))
	}

	return out
}

// candidateFor 读一个 jar 的元信息。
func candidateFor(path string, source string, detail string) JarCandidate {
	candidate := JarCandidate{Path: path, Source: source, Detail: detail}
	if info, err := os.Stat(path); err == nil {
		candidate.ModTime = info.ModTime()
		candidate.Size = info.Size()
	}
	return candidate
}

// locateJar 取第一个候选;一个都没有时报用法错,并把找过的地方列出来。
func locateJar(repoDir string, explicit string) (string, error) {
	candidates := collectJarCandidates(repoDir, explicit)
	if len(candidates) > 0 {
		return candidates[0].Path, nil
	}
	return "", usageErrorf("在 %s 里找不到后端 jar,找过这些地方:\n  - %s\n"+
		"提示:跑一次 `dsb server build` 构建一份,或用 `dsb server init --jar <jar>` 指定",
		repoDir, strings.Join(triedJarLocations(repoDir, explicit), "\n  - "))
}

// triedJarLocations 列出「找过但没找到」的位置,给报错文案用。
func triedJarLocations(repoDir string, explicit string) []string {
	tried := []string{}
	if explicit != "" {
		tried = append(tried, explicit+"(配置里指定的,不存在)")
	}
	tried = append(tried,
		filepath.Join(repoDir, ".dsb-backend", "releases", "<commit>", "backend.jar")+"(没有)",
		filepath.Join(repoDir, "playwright-server", "target", "playwright-server-*.jar")+"(没有)",
		filepath.Join(repoDir, "dist", fmt.Sprintf("*-%s-%s.jar", runtime.GOOS, mapArch(runtime.GOARCH)))+"(没有)")
	return tried
}

// shortCommit 把 commit SHA 截成 8 位,够辨认又不至于把表格撑开。
func shortCommit(commit string) string {
	if len(commit) > 8 {
		return commit[:8]
	}
	return commit
}

// playwrightVersion 从文件名里抠出 playwright 的版本号字符串(1.63.0)。
func playwrightVersion(path string) string {
	name := filepath.Base(path)
	marker := "-playwright-"
	at := strings.Index(name, marker)
	if at < 0 {
		return ""
	}
	return strings.TrimSuffix(name[at+len(marker):], ".jar")
}

// mapArch 把 Go 的 GOARCH 映射成发行包命名里的架构段。
func mapArch(arch string) string {
	switch arch {
	case "amd64":
		return "x64"
	case "arm64":
		return "arm64"
	case "386":
		return "x86"
	}
	return arch
}

// globJars 在一个目录里按模式找 jar,并排除名字里带 exclude 词的(如 sources/javadoc)。
func globJars(directory string, pattern string, exclude ...string) []string {
	matches, err := filepath.Glob(filepath.Join(directory, pattern))
	if err != nil {
		return nil
	}
	out := make([]string, 0, len(matches))
	for _, match := range matches {
		info, err := os.Stat(match)
		if err != nil || info.IsDir() {
			continue
		}
		lowered := strings.ToLower(filepath.Base(match))
		skip := false
		for _, word := range exclude {
			if strings.Contains(lowered, word) {
				skip = true
				break
			}
		}
		if !skip {
			out = append(out, match)
		}
	}
	return out
}

// preferHighestPlaywright 在多个发行包里挑 playwright 版本号最高的那个;
// 文件名里带 playwright-<rev> 的比不带的新,同带时比 rev 大小。
func preferHighestPlaywright(paths []string) string {
	best := paths[0]
	bestRev := playwrightRev(best)
	for _, path := range paths[1:] {
		rev := playwrightRev(path)
		if rev > bestRev {
			best, bestRev = path, rev
		}
	}
	return best
}

// playwrightRev 从文件名里抠出 playwright 版本号;-playwright-1.63.0 → 1630,不带 → 0。
func playwrightRev(path string) int {
	name := filepath.Base(path)
	marker := "-playwright-"
	at := strings.Index(name, marker)
	if at < 0 {
		return 0
	}
	rest := strings.TrimSuffix(name[at+len(marker):], ".jar")
	digits := strings.ReplaceAll(rest, ".", "")
	rev, err := strconv.Atoi(digits)
	if err != nil {
		return 0
	}
	return rev
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}

// ------------------------------------------------------------------ 服务生命周期

// ServerLayout 汇总服务运行时的几条路径(pid 文件、日志),按端口区分,沿用现有脚本的布局。
type ServerLayout struct {
	RepoDir string
	Port    int
	Jar     string
}

func (l ServerLayout) pidFile() string {
	return filepath.Join(l.RepoDir, "logs", "server", fmt.Sprintf("server-%d.pid", l.Port))
}
func (l ServerLayout) outLog() string {
	return filepath.Join(l.RepoDir, "logs", "server", fmt.Sprintf("server-%d.out.log", l.Port))
}
func (l ServerLayout) errLog() string {
	return filepath.Join(l.RepoDir, "logs", "server", fmt.Sprintf("server-%d.err.log", l.Port))
}
func (l ServerLayout) profileDir() string { return filepath.Join(l.RepoDir, ".dsb-backend", "profile") }

// baseURL 拼出这个端口的服务地址(与客户端用的同一个)。
func (l ServerLayout) baseURL() string {
	return fmt.Sprintf("http://127.0.0.1:%d", l.Port)
}

// autoStartBackend 在「连不上服务」时按配置文件把后端拉起来(见 Client.Request)。
func (c *Client) autoStartBackend() error {
	c.autoStarted = true
	repoDir, source, err := resolveRepoDir("")
	if err != nil {
		return err
	}
	port := portFromBaseURL(c.BaseURL)
	fmt.Fprintf(os.Stderr, "服务没起,正在按%s拉起后端(仓库:%s,端口:%d)…\n", source, repoDir, port)
	// 这里的输出走 stderr,避免污染 stdout 上的 JSON / 文本结果
	quiet := &Printer{Mode: "json", Out: os.Stderr, ErrOut: os.Stderr}
	_, err = startBackend(repoDir, port, DefaultEngine, "", DefaultStartupTimout, quiet)
	return err
}

// portFromBaseURL 从 base URL 里抠出端口(自动拉起时要用同一个端口)。
func portFromBaseURL(baseURL string) int {
	parsed, err := url.Parse(baseURL)
	if err != nil {
		return DefaultPort
	}
	if parsed.Port() == "" {
		if parsed.Scheme == "https" {
			return 443
		}
		return 80
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil {
		return DefaultPort
	}
	return port
}

// probeHealth 探一次 /playwright/health,返回是否健康。
//
// 单独用一个短超时的客户端:探活不该等 300 秒,也不该触发自动拉起(它自己就是拉起流程的一部分)。
func probeHealth(baseURL string) bool {
	client, err := NewClient(ClientOptions{BaseURL: baseURL, TaskID: DefaultTaskID,
		Timeout: 2, Session: "", Record: false, AutoStart: false})
	if err != nil {
		return false
	}
	response, err := client.Health()
	return err == nil && response.Ok()
}

// findJava 找一个可用的 java 可执行文件。
func findJava() (string, error) {
	if home := os.Getenv("JAVA_HOME"); home != "" {
		candidate := filepath.Join(home, "bin", "java"+exeSuffix())
		if fileExists(candidate) {
			return candidate, nil
		}
	}
	if found, err := exec.LookPath("java"); err == nil {
		return found, nil
	}
	return "", usageErrorf("找不到 java。装一个 JDK 21+,或把 java 放进 PATH,或设 JAVA_HOME")
}

func exeSuffix() string {
	if runtime.GOOS == "windows" {
		return ".exe"
	}
	return ""
}

// describeJar 把「这份 jar 是什么」写成一行:来源、commit/版本、时间、大小。
func describeJar(repoDir string, explicit string, jarPath string) string {
	for _, candidate := range collectJarCandidates(repoDir, explicit) {
		if candidate.Path == jarPath {
			return describeCandidate(candidate)
		}
	}
	// 不在候选表里(理论上不会发生)——至少把路径报出来
	return jarPath
}

// describeCandidate 渲染一个候选的一行描述。
func describeCandidate(candidate JarCandidate) string {
	parts := []string{candidate.Source}
	if candidate.Detail != "" {
		parts = append(parts, candidate.Detail)
	}
	if !candidate.ModTime.IsZero() {
		parts = append(parts, candidate.ModTime.Format("2006-01-02 15:04"))
	}
	if candidate.Size > 0 {
		parts = append(parts, humanSize(candidate.Size))
	}
	return fmt.Sprintf("%s(%s)", candidate.Path, strings.Join(parts, ", "))
}

// humanSize 把字节数写成 MB/GB。
func humanSize(bytes int64) string {
	switch {
	case bytes >= 1<<30:
		return fmt.Sprintf("%.2f GB", float64(bytes)/float64(1<<30))
	case bytes >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(bytes)/float64(1<<20))
	case bytes >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(bytes)/float64(1<<10))
	}
	return fmt.Sprintf("%d B", bytes)
}

// ------------------------------------------------------------------ 构建

// mavenDriverPlatform 把当前平台映射成 Playwright driver bundle 的目录名。
//
// 不传这个属性的话,shade 会把 driver-bundle 里 5 个平台的 node 全打进 jar(合计约 194MB),
// 而一份发行版只需要自己那个平台的 —— 这也是打包脚本与 plugins/backend.ps1 的做法。
func mavenDriverPlatform() string {
	switch runtime.GOOS + "/" + runtime.GOARCH {
	case "windows/amd64":
		return "win32_x64"
	case "windows/arm64":
		return "win32_arm64"
	case "darwin/amd64":
		return "mac10.14_x64"
	case "darwin/arm64":
		return "mac11-arm64"
	case "linux/amd64":
		return "linux_x64"
	case "linux/arm64":
		return "linux_arm64"
	}
	return ""
}

// findMaven 找 mvn;先 PATH,再找仓库隔壁的 .dsb-tools(plugins/backend.ps1 会往那装)。
func findMaven(repoDir string) (string, error) {
	name := "mvn"
	if runtime.GOOS == "windows" {
		if found, err := exec.LookPath("mvn.cmd"); err == nil {
			return found, nil
		}
	}
	if found, err := exec.LookPath(name); err == nil {
		return found, nil
	}
	toolsDir := filepath.Join(filepath.Dir(repoDir), ".dsb-tools")
	if entries, err := os.ReadDir(toolsDir); err == nil {
		for _, entry := range entries {
			if !entry.IsDir() || !strings.HasPrefix(entry.Name(), "apache-maven-") {
				continue
			}
			candidate := filepath.Join(toolsDir, entry.Name(), "bin", "mvn"+exeSuffix())
			if fileExists(candidate) {
				return candidate, nil
			}
		}
	}
	return "", usageErrorf("找不到 mvn。把 Maven 放进 PATH,或改用 --jar 指定现成的 jar")
}

// buildBackend 构建一份后端 jar,并按 commit 存进 releases/,和 plugins/backend.ps1 的布局一致。
//
// 为什么要存进 releases/ 而不是只用 target/:那套布局是既有约定 —— 目录名就是 commit,
// 既方便回滚到某个已知可用的版本,也让 `server status` 能一眼看出「跑的是哪一份」。
func buildBackend(repoDir string, out *Printer) (string, error) {
	if !fileExists(filepath.Join(repoDir, "pom.xml")) {
		return "", usageErrorf("%s 不是仓库根(没有 pom.xml)", repoDir)
	}
	maven, err := findMaven(repoDir)
	if err != nil {
		return "", err
	}

	arguments := []string{"-B", "-ntp", "-Pproduction", "-pl", "playwright-server", "-am",
		"clean", "package", "-DskipTests"}
	if platform := mavenDriverPlatform(); platform != "" {
		arguments = append(arguments, "-Ddriver.platform="+platform)
	}
	out.Line(fmt.Sprintf("构建后端:%s %s", maven, strings.Join(arguments, " ")))
	out.Line("(首次构建要下依赖,可能要几分钟)")

	command := exec.Command(maven, arguments...)
	command.Dir = repoDir
	// stdout/stderr 直通:构建是个长过程,进度直接给用户看
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		return "", transportErrorf("Maven 构建失败:%v(上面是它的输出)", err)
	}

	// target/ 里挑主产物(排除 sources/javadoc)
	matches := globJars(filepath.Join(repoDir, "playwright-server", "target"),
		"playwright-server-*.jar", "sources", "javadoc")
	if len(matches) == 0 {
		return "", transportErrorf("构建好像成功了,但 target/ 里没有找到 jar")
	}
	built := matches[0]
	if len(matches) > 1 {
		built = newestCandidatePath(matches)
	}

	commit, commitErr := currentCommit(repoDir)
	if commitErr != nil {
		return "", commitErr
	}
	// 按 commit 存档:已经在就覆盖(重建同一份代码得到的东西),目录不存在就建
	release := filepath.Join(repoDir, ".dsb-backend", "releases", commit)
	if err := os.MkdirAll(release, 0o755); err != nil {
		return "", usageErrorf("建不了 release 目录 %s:%v", release, err)
	}
	artifact := filepath.Join(release, "backend.jar")
	if err := copyFile(built, artifact); err != nil {
		return "", transportErrorf("拷进 release 目录失败:%v", err)
	}
	out.Line(fmt.Sprintf("已构建并归档:%s(commit %s)", artifact, shortCommit(commit)))
	return artifact, nil
}

// newestCandidatePath 从一组路径里挑 mtime 最新的(构建会同时留下多个 jar 时用)。
func newestCandidatePath(paths []string) string {
	best := paths[0]
	var bestTime time.Time
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		if bestTime.IsZero() || info.ModTime().After(bestTime) {
			best, bestTime = path, info.ModTime()
		}
	}
	return best
}

// currentCommit 取仓库当前 HEAD 的 commit SHA;取不到就是用法错(构建产物没法归位)。
func currentCommit(repoDir string) (string, error) {
	command := exec.Command("git", "-C", repoDir, "rev-parse", "HEAD")
	output, err := command.Output()
	if err != nil {
		return "", usageErrorf("取不到当前 commit(git rev-parse HEAD 失败):%v", err)
	}
	commit := strings.TrimSpace(string(output))
	if commit == "" {
		return "", usageErrorf("git rev-parse HEAD 返回了空值")
	}
	return commit, nil
}

// copyFile 复制文件(先写临时文件再改名,避免半截文件被当成可用的 jar)。
func copyFile(source string, target string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	temporary := target + ".tmp"
	output, err := os.Create(temporary)
	if err != nil {
		return err
	}
	if _, err := io.Copy(output, input); err != nil {
		output.Close()
		os.Remove(temporary)
		return err
	}
	if err := output.Close(); err != nil {
		os.Remove(temporary)
		return err
	}
	return os.Rename(temporary, target)
}

// startBackend 拉起后端服务;已经在跑就直接复用。
//
// 返回 (是否复用了已有的, 错误)。
func startBackend(repoDir string, port int, engine string, jar string, timeout float64, out *Printer) (bool, error) {
	layout := ServerLayout{RepoDir: repoDir, Port: port, Jar: jar}
	baseURL := layout.baseURL()

	if probeHealth(baseURL) {
		out.Line(fmt.Sprintf("服务已在运行:%s(端口 %d)", baseURL, port))
		return true, nil
	}

	java, err := findJava()
	if err != nil {
		return false, err
	}
	jarPath, err := locateJar(repoDir, jar)
	if err != nil {
		return false, err
	}
	layout.Jar = jarPath
	// 明确说清用的是哪一份 —— 三处候选含义不同,「跑的是哪份」是排查的第一个问题
	out.Line("用的是:" + describeJar(repoDir, jar, jarPath))
	if err := os.MkdirAll(filepath.Dir(layout.pidFile()), 0o755); err != nil {
		return false, usageErrorf("建不了日志目录:%v", err)
	}
	if err := os.MkdirAll(layout.profileDir(), 0o755); err != nil {
		return false, usageErrorf("建不了 profile 目录:%v", err)
	}
	// 运行目录:放 JVM 的内部 loopback socket 与临时文件。
	// **这条必须给**:不给 -Djdk.net.unixdomain.tmpdir 时,JDK 21 建 Selector 会去连一个
	// 默认位置的 unix domain socket,在 Windows 上直接 `Invalid argument: connect` /
	// `Unable to establish loopback connection`,服务起不来(实测踩过)。
	// plugins 的 backend.ps1 也是这么做的,原因相同。
	runDir := filepath.Join(layout.RepoDir, "logs", "server", fmt.Sprintf("run-%d", port))
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		return false, usageErrorf("建不了运行目录:%v", err)
	}

	arguments := []string{
		fmt.Sprintf("-Dserver.port=%d", port),
		"-Djdk.net.unixdomain.tmpdir=" + runDir,
		"-Dbrowser.profileDir=" + layout.profileDir(),
		"-Dbrowser.chrome.cdpProfileDir=" + layout.profileDir(),
		"-Dbrowser.chrome.useUserProfile=false",
	}
	if engine != "" {
		arguments = append(arguments, "-Dbrowser.engine="+engine)
	}
	arguments = append(arguments, "-jar", jarPath)

	out.Line(fmt.Sprintf("启动后端:%s %s", java, strings.Join(arguments, " ")))
	handle, err := launchDetached(java, arguments, repoDir, layout.outLog(), layout.errLog())
	if err != nil {
		return false, transportErrorf("启动后端失败:%v", err)
	}
	if err := writeTextFile(layout.pidFile(), itoa(handle.Pid)+"\n"); err != nil {
		out.Warn(fmt.Sprintf("[warn] 写 pid 文件失败:%v", err))
	}

	// 轮询到健康为止。进程中途退出就立刻放弃(不干等满超时);超时也附上日志尾部,免得只拿到一句「没就绪」。
	deadline := time.Now().Add(time.Duration(timeout * float64(time.Second)))
	for time.Now().Before(deadline) {
		if probeHealth(baseURL) {
			out.Line(fmt.Sprintf("服务已就绪:%s(pid %d,jar %s)", baseURL, handle.Pid, jarPath))
			return false, nil
		}
		if !processAlive(handle.Pid) {
			_ = os.Remove(layout.pidFile())
			return false, transportErrorf("后端进程启动后很快退出(pid %d)。日志尾部:\n%s",
				handle.Pid, tailFile(layout.outLog(), 20))
		}
		time.Sleep(500 * time.Millisecond)
	}
	_ = os.Remove(layout.pidFile())
	return false, transportErrorf("后端在 %.0f 秒内没有就绪。日志尾部(%s):\n%s",
		timeout, layout.outLog(), tailFile(layout.outLog(), 20))
}

// stopBackend 停掉后端:先走 HTTP shutdown(与现有 stop 脚本同序),再按进程结束。
func stopBackend(repoDir string, port int, keepBrowser bool, out *Printer) error {
	layout := ServerLayout{RepoDir: repoDir, Port: port}
	baseURL := layout.baseURL()

	if !keepBrowser && probeHealth(baseURL) {
		// 先让服务自己关掉共享浏览器,再杀进程 —— 反过来会留下孤儿浏览器
		client, err := NewClient(ClientOptions{BaseURL: baseURL, TaskID: DefaultTaskID,
			Timeout: 15, Session: "", Record: false, AutoStart: false})
		if err == nil {
			if response, err := client.Command("shutdown", NewObj(), nil, 15, "shutdown"); err == nil && response.Ok() {
				out.Line("已让服务关掉浏览器与任务")
			} else {
				out.Warn("HTTP shutdown 没成功,继续按进程结束")
			}
		}
	}

	pid, err := readPid(layout.pidFile())
	if err != nil {
		if probeHealth(baseURL) {
			return usageErrorf("服务在运行,但没有 pid 文件 %s —— 找不到进程,请手工结束", layout.pidFile())
		}
		out.Line("服务没在运行")
		return nil
	}
	if !processAlive(pid) {
		out.Line(fmt.Sprintf("进程 %d 已经不在了", pid))
		_ = os.Remove(layout.pidFile())
		return nil
	}
	if err := killProcessTree(pid); err != nil {
		return transportErrorf("结束进程 %d 失败:%v", pid, err)
	}
	out.Line(fmt.Sprintf("已结束进程 %d", pid))
	_ = os.Remove(layout.pidFile())
	return nil
}

// readPid 读 pid 文件。
func readPid(path string) (int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil {
		return 0, usageErrorf("pid 文件内容不是数字:%s", path)
	}
	return pid, nil
}

// tailFile 读文件最后几行(给 server logs 与启动失败时附日志用)。
func tailFile(path string, lines int) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	all := strings.Split(strings.ReplaceAll(string(data), "\r\n", "\n"), "\n")
	if len(all) > lines {
		all = all[len(all)-lines:]
	}
	return strings.Join(all, "\n")
}
