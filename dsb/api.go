package main

// CommandAPI 是子命令实现依赖的那一层能力。
//
// 为什么用接口而不是直接依赖 *Client:Python 版的自测用 unittest.mock 把 Client 换掉,
// 只验证「发了什么参数、打出了什么、退出码是几」。Go 这边把同样的边界画成接口,测试就能
// 不连服务地跑完整条命令路径 —— 否则输出策略、告警、退出码这些最容易出错的逻辑全都测不到。

type CommandAPI interface {
	Command(method string, params Value, taskID *int, timeout float64, label string) (*Response, error)
	Batch(commands []Value, stopOnError, stopOnExpectFailure, asynchronous bool,
		maxDurationMs int, timeout float64, taskID *int) (*Response, error)
	Health() (*Response, error)
	Methods() (*Response, error)
	Config() (*Response, error)
	Tasks() (*Response, error)
	Start(browser string, headless bool, taskID *int) (*Response, error)
	Close(taskID *int) (*Response, error)
	Shutdown() (*Response, error)
	Upload(path, filename string) (*Response, error)
	Uploads() (*Response, error)
	DeleteUpload(name string) (*Response, error)
	Job(jobID string, includeResult bool) (*Response, error)
	WaitJob(jobID string, poll, timeout float64, onTick func(*Response)) (*Response, error)
}

// 编译期确认 *Client 满足接口。
var _ CommandAPI = (*Client)(nil)
