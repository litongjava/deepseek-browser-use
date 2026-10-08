package main

import (
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

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

// probeHealth 探活:连探几次,任意一次成功即算健康。
func probeHealth(baseURL string) bool {
	_, ok := probeHealthInfo(baseURL, 0)
	return ok
}

// probeHealthInfo 探活并解析实例身份;timeout 传 0 用默认值。
//
// 单独用一个短超时的客户端:探活不该等 300 秒,也不该触发自动拉起(它自己就是拉起流程的一部分)。
func probeHealthInfo(baseURL string, timeout float64) (*HealthInfo, bool) {
	return probeHealthInfoAttempts(baseURL, timeout, healthProbeAttempts)
}

// probeHealthInfoAttempts 是探活的实体:指定次数,失败时退避递增。
func probeHealthInfoAttempts(baseURL string, timeout float64, attempts int) (*HealthInfo, bool) {
	if timeout <= 0 {
		timeout = healthProbeTimeoutSeconds
	}
	if attempts < 1 {
		attempts = 1
	}
	for attempt := 0; attempt < attempts; attempt++ {
		if attempt > 0 {
			time.Sleep(time.Duration(healthProbeBackoffMillis*attempt) * time.Millisecond)
		}
		client, err := NewClient(ClientOptions{BaseURL: baseURL, TaskID: DefaultTaskID,
			Timeout: timeout, Session: "", Record: false, AutoStart: false})
		if err != nil {
			continue
		}
		response, err := client.Health()
		if err != nil || !response.Ok() {
			continue
		}
		return healthInfoFrom(response), true
	}
	return nil, false
}

// healthInfoFrom 从健康响应里取身份字段。
func healthInfoFrom(response *Response) *HealthInfo {
	info := &HealthInfo{}
	data := asObj(response.Data())
	if data == nil {
		return info
	}
	info.Name = healthTextField(data, "name")
	info.StartedAt = healthTextField(data, "startedAt")
	info.PID = healthIntField(data, "pid")
	info.Port = healthIntField(data, "port")
	return info
}

func healthTextField(data *Obj, key string) string {
	value, ok := data.Get(key)
	if !ok || value == nil {
		return ""
	}
	return strings.TrimSpace(pyStr(value))
}

// healthIntField 走字符串再解析:jsonval 把数字保留成 json.Number,走 pyStr 更稳。
func healthIntField(data *Obj, key string) int {
	text := healthTextField(data, key)
	if text == "" {
		return 0
	}
	parsed, err := strconv.Atoi(text)
	if err != nil {
		return 0
	}
	return parsed
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
	// jar 的优先级:显式 --jar > 配置文件里的 jar > 按 mtime 猜。
	//
	// 中间这一档以前是断的:server init --jar 会把路径写进配置文件、还打印出来说记下了,
	// 但 start 从来不读它 —— 于是出现「我明明指定了 jar,跑起来的却是另一份」。
	if jar == "" {
		if config, _, configErr := loadConfig(); configErr == nil && config.Jar != "" {
			jar = config.Jar
			out.Line("jar 取自配置文件:" + jar)
		}
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
	if err := writePidRecord(layout.pidFile(), PidRecord{
		PID:       handle.Pid,
		Port:      port,
		StartedAt: time.Now().Format(time.RFC3339),
		Jar:       jarPath,
	}); err != nil {
		out.Warn(fmt.Sprintf("[warn] 写 pid 文件失败:%v", err))
	}

	// 轮询到健康为止。进程中途退出就立刻放弃(不干等满超时);超时也附上日志尾部,免得只拿到一句「没就绪」。
	deadline := time.Now().Add(time.Duration(timeout * float64(time.Second)))
	for time.Now().Before(deadline) {
		if info, healthy := probeHealthInfo(baseURL, 0); healthy {
			// 应答的必须是**我们刚拉起来的这个进程**。端口上如果还有别的实例(或它比我们抢先绑上),
			// 这里报「已就绪」会让调用方对着另一个空实例发命令,现象就是任务莫名消失。
			// 老版本服务端不上报 pid(零值)时不拦,只提示一句。
			if info.PID != 0 && info.PID != handle.Pid {
				return false, transportErrorf(
					"端口 %d 上应答的是另一个后端实例(它自报 pid %d,我们启动的是 pid %d)。"+
						"先停掉其中一个,或用 --port 指定别的端口", port, info.PID, handle.Pid)
			}
			if info.PID == 0 {
				out.Warn(fmt.Sprintf("[warn] 端口 %d 上的服务没有上报 pid(可能是旧版 jar),无法确认就是我们启动的那个", port))
			}
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

	record, err := readPidRecord(layout.pidFile())
	if err != nil {
		if probeHealth(baseURL) {
			return usageErrorf("服务在运行,但没有 pid 文件 %s —— 找不到进程,请手工结束", layout.pidFile())
		}
		out.Line("服务没在运行")
		return nil
	}
	pid := record.PID
	if !processAlive(pid) {
		out.Line(fmt.Sprintf("进程 %d 已经不在了", pid))
		_ = os.Remove(layout.pidFile())
		return nil
	}

	// 动手前先核对身份。pid 文件里只有一个数字时,下面两种错杀都拦不住 ——
	// 所以现在记的是「哪个端口上的哪个进程」,并且尽量让服务自报一次 pid:
	//   ① 这个 pid 被系统回收给了别的进程;
	//   ② 这个 pid 其实是**另一个实例**的后端(多后端时最容易发生)。
	if !pidRecordMatchesPort(record, port) {
		return usageErrorf("pid 文件 %s 记的是端口 %d 上的后端(pid %d),而这次要停的是端口 %d。"+
			"为免停错实例这里不动手:确认后对端口 %d 执行 dsb server stop --port %d",
			layout.pidFile(), record.Port, pid, port, record.Port, record.Port)
	}
	if info, healthy := probeHealthInfoAttempts(baseURL, 3, 1); healthy && info.PID != 0 && info.PID != pid {
		return usageErrorf("pid 文件里是 %d,但端口 %d 上应答的是 pid %d —— 这个 pid 很可能已被系统回收。"+
			"为免杀掉无关进程这里不动手:确认后删掉 %s 再试", pid, port, info.PID, layout.pidFile())
	} else if !healthy {
		out.Warn(fmt.Sprintf("[warn] 端口 %d 上没有响应,无法核对 pid %d 的身份,将仅按 pid 文件结束进程", port, pid))
	}

	if err := killProcessTree(pid); err != nil {
		return transportErrorf("结束进程 %d 失败:%v", pid, err)
	}
	out.Line(fmt.Sprintf("已结束进程 %d", pid))
	_ = os.Remove(layout.pidFile())
	return nil
}

// writePidRecord 写 pid 文件:第一行裸 pid,后面是 key=value 的身份信息。
func writePidRecord(path string, record PidRecord) error {
	var builder strings.Builder
	builder.WriteString(strconv.Itoa(record.PID) + "\n")
	builder.WriteString("port=" + strconv.Itoa(record.Port) + "\n")
	if record.StartedAt != "" {
		builder.WriteString("startedAt=" + record.StartedAt + "\n")
	}
	if record.Jar != "" {
		builder.WriteString("jar=" + record.Jar + "\n")
	}
	return writeTextFile(path, builder.String())
}

// readPidRecord 读 pid 文件;只有一行裸 pid 的老格式也能读。
func readPidRecord(path string) (PidRecord, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return PidRecord{}, err
	}
	record := PidRecord{}
	for index, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if index == 0 && !strings.Contains(line, "=") {
			parsed, err := strconv.Atoi(line)
			if err != nil {
				return PidRecord{}, usageErrorf("pid 文件第一行不是数字:%s", path)
			}
			record.PID = parsed
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		switch key {
		case "port":
			if parsed, err := strconv.Atoi(value); err == nil {
				record.Port = parsed
			}
		case "startedAt":
			record.StartedAt = value
		case "jar":
			record.Jar = value
		}
	}
	if record.PID == 0 {
		return PidRecord{}, usageErrorf("pid 文件里没有 pid:%s", path)
	}
	return record, nil
}

// pidRecordMatchesPort 判断 pid 文件记的是不是这个端口上的后端。
//
// 老格式(只有一行裸 pid)没有 port 字段,这时不拦 —— 只能靠服务自报的 pid 兜底。
func pidRecordMatchesPort(record PidRecord, port int) bool {
	return record.Port == 0 || record.Port == port
}

// readPid 读 pid 文件(只取 pid,给「只想知道是哪个进程」的调用方)。
func readPid(path string) (int, error) {
	record, err := readPidRecord(path)
	if err != nil {
		return 0, err
	}
	return record.PID, nil
}

// tailFile 读文件最后几行(给 server logs 与启动失败时附日志用)。
//
// 从尾部 Seek 一段读,而不是把整个日志读进内存:一个跑了很久的实例,out.log 可以到几百 MB,
// 而这里只要最后 40 行。
func tailFile(path string, lines int) string {
	const windowBytes = 256 * 1024
	handle, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer handle.Close()
	info, err := handle.Stat()
	if err != nil {
		return ""
	}
	offset := int64(0)
	if info.Size() > windowBytes {
		offset = info.Size() - windowBytes
	}
	// 0 == io.SeekStart:用字面量省一次 import
	if _, err := handle.Seek(offset, 0); err != nil {
		return ""
	}
	buffer := make([]byte, windowBytes)
	total := 0
	for total < len(buffer) {
		read, readErr := handle.Read(buffer[total:])
		total += read
		if readErr != nil {
			break
		}
	}
	text := strings.ReplaceAll(string(buffer[:total]), "\r\n", "\n")
	// 从中间截断时第一行多半是半行,丢掉它,免得日志开头出现一段莫名其妙的内容
	if offset > 0 {
		if cut := strings.Index(text, "\n"); cut >= 0 {
			text = text[cut+1:]
		}
	}
	all := strings.Split(text, "\n")
	if len(all) > lines {
		all = all[len(all)-lines:]
	}
	return strings.Join(all, "\n")
}
