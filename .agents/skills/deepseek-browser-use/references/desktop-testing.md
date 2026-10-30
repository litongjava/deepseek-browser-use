# 外部 WebView2 接入与验收

用于已开启 CDP 调试端口的 Windows WebView2（包括 Tauri），也可接入任何带 `--remote-debugging-port` 的 Chrome/Edge；不承诺 macOS/Linux 的全部 Tauri WebView。测试独立构建，不替换用户已安装 App。

## 连接与生命周期

```powershell
dsb targets --endpoint http://127.0.0.1:9222 --json
dsb attach --endpoint http://127.0.0.1:9222 --url http://tauri.localhost/ --json
dsb state --text-only
dsb close
```

endpoint 是调试地址，不是 dsb 服务端口。`targets` 每项含 `targetId`、`url`、`type`、`title`，多窗口按 title 辨认后用 `--target-id` 精确选择。零个匹配返回 `EXTERNAL_TARGET_NOT_FOUND`，多个返回 `EXTERNAL_TARGET_AMBIGUOUS`，均附 `targets` 候选和 `matched` 数；任务已存在返回 `TASK_ALREADY_EXISTS`。这些都是 `actionStatus:not-started`、`retrySafe:true`，修正选择后可直接重试。复用任务绑定，不覆盖已有任务。回执记录 mode、targetId、URL、目标类型、product、生命周期所有权；宿主及后端状态无法确认时标 unknown。改用 `--summary` 时两者只回一行，候选按 `title|url|targetId` 列出。

外部任务固定目标，不认领弹窗、不自动补页签、不允许切换或关闭页签。目标关闭或断线后需明确重新 attach。`close`（含 `--close-browser`）只断开本任务连接，保留宿主和其他任务。

## 环境校验

URL/Tauri 标志不能证明真实后端。项目提供只读断言，核验 URL、版本、mock 状态及后端身份；缺失字段和 unknown 不能当作 real。

```powershell
dsb run click_element_by_selector -p selector=#refresh -p mode=mouse --precondition '@environment.json' --expect '@business.json' --diagnostics --out refresh.json
```

`precondition` 复用 [expect 比较器](command-reliability.md)，在动作前检查；失败返回 `PRECONDITION_FAILED`、`actionExecuted:false`。每次按需传入，不是持久保证；环境仍可能在检查后变化。结果保留 `preconditionResult`、`expectResult`。表达式须只读。

## 交互与离线报告

`mode:mouse` / `mode:native` 不自动降级为 JS。auto 可降级；读取 `requestedMode`、`actualMode`（兼容 mode）、fallbackReason 和命中信息。JS 业务成功不能报告为鼠标路径成功。未知动作先回读，不重放补证据。

```powershell
dsb report --record-dir logs/agent/native-test --out report.json --markdown report.md
```

报告只读已有记录，不操作浏览器。JSON 保留响应证据、命令/动作/业务状态及成功、失败、跳过、未知计数；Markdown 是证据索引。缺响应、脱敏和未知动作不补成成功；执行前被拒绝（`ok:false`、`actionStatus:not-started`）计为失败，`actionStatus:unknown` 仍计未知，每步附 `actionStatus` 和 `errorCode`。未记录用例无法推断；截图路径不证明图片存在或完整，按 [测试与证据](testing-evidence.md) 核验。版本、时间及真实/模拟身份缺失时仍是未知。
