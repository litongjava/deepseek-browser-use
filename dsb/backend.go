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
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
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

// 探活的次数、单次超时与退避。
//
// 以前只探一次、超时硬编码 2 秒:一个正在跑长命令或正在 GC 的后端,只要应答慢过 2 秒
// 就会被判成「服务没起」,然后往**同一个端口**再拉一个 JVM —— 调用方手里那份内存里的
// 任务表就此作废,而它只会看到后续命令报「没有找到对应的浏览器实例」。
const (
	healthProbeAttempts       = 3
	healthProbeTimeoutSeconds = 5.0
	healthProbeBackoffMillis  = 400
)

// HealthInfo 是 /playwright/health 回的实例身份。
//
// 老版本服务端没有除 name 以外的字段,取不到就是零值 —— 调用方必须容忍零值(见 startBackend)。
type HealthInfo struct {
	Name      string
	PID       int
	Port      int
	StartedAt string
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

// PidRecord 是 pid 文件的内容:不止一个数字。
//
// 只记 pid 的话,这个 pid 被系统回收给别的进程之后,dsb server stop / restart 会照着
// 这个数字强杀一整棵无辜的进程树(Windows 上是 taskkill /T /F)。多记两行,就能在动手前核对身份。
//
// 第一行仍是裸 pid,老版本的 readPid 与外部脚本照旧能读。
type PidRecord struct {
	PID       int
	Port      int
	StartedAt string
	Jar       string
}
