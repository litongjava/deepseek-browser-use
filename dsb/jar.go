package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

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
			if _, err := os.Stat(jarPath); err == nil {
				releases = append(releases, candidateFor(jarPath, "releases", shortCommit(entry.Name())))
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
