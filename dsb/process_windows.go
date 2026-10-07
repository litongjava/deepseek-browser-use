//go:build windows

package main

// Windows 上的「脱离父进程启动」与「结束进程树」。
//
// 两件事都需要专门的系统调用,所以按平台分文件:
//   - 启动:必须真的脱离 —— IDE 里跑 dsb 时,dsb 退出不该把后端一起带走。
//     用 DETACHED_PROCESS + CREATE_NEW_PROCESS_GROUP,HideWindow 避免弹黑框。
//   - 结束:java 是父进程,它下面还挂着浏览器子进程。只杀父会留下孤儿浏览器,
//     所以整棵树一起杀(taskkill /T),这也是现有 stop 脚本的做法。

import (
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"unsafe"
)

// DETACHED_PROCESS(0x00000008) + CREATE_NEW_PROCESS_GROUP(0x00000200):
// 让后端不受父进程的控制台与信号影响,dsb 退出后它继续跑。
const creationFlags = 0x00000008 | 0x00000200

// launchDetached 脱离父进程起一个进程,stdout/stderr 重定向到给定日志。
func launchDetached(program string, args []string, workDir string, outLog string, errLog string) (*os.Process, error) {
	outFile, err := os.OpenFile(outLog, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	defer outFile.Close()
	errFile, err := os.OpenFile(errLog, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	defer errFile.Close()

	command := exec.Command(program, args...)
	command.Dir = workDir
	command.Stdout = outFile
	command.Stderr = errFile
	command.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: creationFlags,
	}
	if err := command.Start(); err != nil {
		return nil, err
	}
	// 不 Wait:它是长驻服务,由 pid 文件与 HTTP 探活来跟踪
	process := command.Process
	_ = command.Process.Release()
	return process, nil
}

// processAlive 判断 pid 还在不在。
//
// 不用 syscall.OpenProcess:标准库在 Windows 上没有导出 GetExitCodeProcess 需要的那些常量。
// 直接调 kernel32 的两个函数最省事,也不引入额外依赖。
func processAlive(pid int) bool {
	const processQueryLimitedInformation = 0x1000
	handle, _, _ := procOpenProcess.Call(processQueryLimitedInformation, 0, uintptr(uint32(pid)))
	if handle == 0 {
		return false
	}
	defer procCloseHandle.Call(handle)
	var code uint32
	if result, _, _ := procGetExitCodeProcess.Call(handle, uintptr(unsafe.Pointer(&code))); result == 0 {
		return false
	}
	const stillActive = 259
	return code == stillActive
}

var (
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	procOpenProcess        = kernel32.NewProc("OpenProcess")
	procGetExitCodeProcess = kernel32.NewProc("GetExitCodeProcess")
	procCloseHandle        = kernel32.NewProc("CloseHandle")
)

// killProcessTree 结束整棵进程树(java 下面还挂着浏览器)。
func killProcessTree(pid int) error {
	command := exec.Command("taskkill", "/PID", strconv.Itoa(pid), "/T", "/F")
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return command.Run()
}
