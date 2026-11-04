package main

import "runtime/debug"

// 版本号。
//
// 与 Python 客户端保持一致:同一个服务端、同一套协议,两份客户端不该报出不同的版本号 ——
// 排查时 `dsb --version` 是第一个要问的东西。

const version = "1.0.0"

// versionText adds the commit Go stamped into the binary, so "which build is this" has a real answer.
func versionText() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return version
	}
	return versionFromSettings(info.Settings)
}

func versionFromSettings(settings []debug.BuildSetting) string {
	revision, modified := "", false
	for _, setting := range settings {
		switch setting.Key {
		case "vcs.revision":
			revision = setting.Value
		case "vcs.modified":
			modified = setting.Value == "true"
		}
	}
	if revision == "" {
		return version
	}
	if len(revision) > 8 {
		revision = revision[:8]
	}
	if modified {
		revision += "-dirty"
	}
	return version + " (" + revision + ")"
}
