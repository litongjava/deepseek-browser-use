//go:build !windows

package main

// 类 Unix 上的「脱离父进程启动」与「结束进程树」。
//
//   - 启动:setsid 新建会话,dsb 退出不会把后端一起带走(拿到自己的进程组)。
//   - 结束:java 进程组里还有浏览器子进程,只杀父会留孤儿;对**进程组**发信号
//     (负的 pid)才能整组带走,这也与 scripts/run/stop-server.sh 的做法一致。

import (
	"os"
	"os/exec"
	"syscall"
	"time"
)

// launchDetached 新建会话起一个进程,stdout/stderr 重定向到给定日志。
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
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		return nil, err
	}
	process := command.Process
	_ = command.Process.Release()
	return process, nil
}

// processAlive 判断 pid 还在不在(信号 0 只做存在性检查,不真的发信号)。
func processAlive(pid int) bool {
	err := syscall.Kill(pid, 0)
	if err == nil {
		return true
	}
	return err == syscall.EPERM // 存在但没权限
}

// killProcessTree 先给整组 SIGTERM,等一会再 SIGKILL。
func killProcessTree(pid int) error {
	// 负 pid = 发给整个进程组
	if err := syscall.Kill(-pid, syscall.SIGTERM); err != nil {
		// 进程组不存在时退回杀单个进程
		if err := syscall.Kill(pid, syscall.SIGTERM); err != nil && !errorsIsNoSuchProcess(err) {
			return err
		}
	}
	for i := 0; i < 30; i++ {
		if !processAlive(pid) {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	if err := syscall.Kill(-pid, syscall.SIGKILL); err != nil {
		if err := syscall.Kill(pid, syscall.SIGKILL); err != nil && !errorsIsNoSuchProcess(err) {
			return err
		}
	}
	return nil
}

func errorsIsNoSuchProcess(err error) bool {
	return err == syscall.ESRCH
}
