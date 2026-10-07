package main

// 两类错误:用法错(本地问题,退出码 3)与传输错(拿不到合法响应,退出码 1)。
// 业务失败(服务端回 ok:false)不抛异常,由调用方看 Response.Ok —— 这是 Python 版的原话。

import (
	"errors"
	"fmt"
)

// 退出码。
const (
	ExitOK        = 0 // 成功
	ExitTransport = 1 // 传输或协议错
	ExitBusiness  = 2 // 业务失败(服务端回了 ok:false)
	ExitUsage     = 3 // 用法错
	ExitInterrupt = 130
)

// UsageError 是用法错:参数写错了、本地文件不存在、没有可重放的记录。
//
// 这类问题与服务端无关,所以退出码单独区分(3),
// 免得脚本里把「我把命令敲错了」当成「服务端挂了」。
type UsageError struct{ Message string }

func (e *UsageError) Error() string { return e.Message }

func usageErrorf(format string, args ...any) error {
	return &UsageError{Message: fmt.Sprintf(format, args...)}
}

// TransportError 是连不上、超时、响应不是合法 JSON —— 都属于这一类。
type TransportError struct{ Message string }

func (e *TransportError) Error() string { return e.Message }

func transportErrorf(format string, args ...any) error {
	return &TransportError{Message: fmt.Sprintf(format, args...)}
}

// asUsage 判断是不是用法错。
func asUsage(err error) (*UsageError, bool) {
	var target *UsageError
	if errors.As(err, &target) {
		return target, true
	}
	return nil, false
}

// asTransport 判断是不是传输错。
func asTransport(err error) (*TransportError, bool) {
	var target *TransportError
	if errors.As(err, &target) {
		return target, true
	}
	return nil, false
}

// itoa 是 strconv.Itoa 的短别名,避免在一堆字符串拼接处反复 import strconv。
func itoa(value int) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	var buffer [20]byte
	index := len(buffer)
	for value > 0 {
		index--
		buffer[index] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		index--
		buffer[index] = '-'
	}
	return string(buffer[index:])
}
