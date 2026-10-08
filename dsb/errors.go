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
//
// Cause 保留网络栈的原始错误,供「这次失败到底发生在请求发出去之前还是之后」这类判断使用:
// 只有确定没发出去的失败,才允许自动拉起后端并把请求重发一次(见 client.go 的 isPreDeliveryFailure)。
type TransportError struct {
	Message string
	Cause   error
}

func (e *TransportError) Error() string { return e.Message }

// Unwrap 让 errors.Is / errors.As 能穿透到网络栈的原始错误。
func (e *TransportError) Unwrap() error { return e.Cause }

func transportErrorf(format string, args ...any) error {
	return &TransportError{Message: fmt.Sprintf(format, args...)}
}

// transportErrorCause 与 transportErrorf 相同,但额外保留底层错误。
func transportErrorCause(cause error, format string, args ...any) error {
	return &TransportError{Message: fmt.Sprintf(format, args...), Cause: cause}
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
