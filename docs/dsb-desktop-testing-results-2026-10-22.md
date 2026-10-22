# dsb 桌面能力实施与验证（2026-10-22）

## 已实现

- CLI targets/attach：枚举 CDP 页面，按 target ID 或精确 URL 选择；零或多个匹配失败，不任选页面。
- 外部连接独立 driver；close（包括 close-browser）只断开连接，保留宿主；不自动补页签或认领弹窗。
- 每次外部命令前只读检查目标存活，目标断开返回 EXTERNAL_TARGET_CLOSED。
- run --precondition 复用 expect 比较器，失败在派发前停止，保留 actual 和 actionExecuted:false。
- 点击补充 requestedMode、actualMode、coordinateSystem；保留现有 mode/fallback 字段及严格 mouse/native 行为。
- report 从已有请求/响应生成 JSON 和 Markdown 索引；保留原始响应，区分命令、动作和业务状态。
- --fields alias=path 可重复；部分缺失为 null，成功响应退出 3，失败响应保留业务退出码。
- 浏览器统一使用 dsb 的规则加入仓库 AGENTS、Codex 全局 AGENTS、技能描述/入口和 CLI help。
- 通用可靠执行内容合并进主技能；维护分册 85 行缩至 18 行，保留旧路径；客户端和桌面验收按需加载。

## 实际验证

- Go：build 执行 go test ./... 与 go install，均通过。新增字段筛选、失败退出码、报告缺证据和可重复生成测试。
- Java：JDK 21；CommandTableTest、CommandReliabilityTest、ResponseFormatterTest 共 19 项通过。
- Java 全量曾运行 327 项，4 失败、19 错误、12 跳过；包含命令覆盖清单缺项及共享浏览器/profile 状态冲突。清单已修复并通过定向测试；全量不能标通过。
- build 与 dsb server build 完成；隔离端口使用本次构建，未重启用户共享服务。
- 真实 Windows Tauri/WebView2：枚举、attach、状态读取、mouse 点击 Quota Lookup、断言、截图、detach 完成。detach 后相同 target ID 仍可枚举；并存 Chrome 任务仍能读标题。
- 模拟前置表达式返回 mock、要求 real 时失败，dispatchStarted:false、actionExecuted:false。
- 无匹配目标失败；未开启端口返回连接拒绝提示。
- 停止独立测试宿主后，最终代码返回 EXTERNAL_TARGET_CLOSED 且未派发动作。测试过程发现并修复了仅依赖缓存连接状态会误报标题读取成功的问题。
- 截图已查看，展示 Quota Lookup 空态。仅证明此导航和桌面交互；没有凭据，不证明真实配额查询。环境仍标 unknown。
- 技能验证通过，全部本地 Markdown 链接存在；Codex 安装副本与本次修改文件逐项 SHA-256 一致。

## 验收边界

尚未实测多目标歧义、零 page 的 endpoint、仅关闭单目标而保持宿主运行、Edge start/close、iframe/缩放和遮挡/降级路径。原始 WebView2 点击问题未复现，不归因为 DPI 或 dsb 缺陷。

环境前置断言是逐命令选项，不是持久门禁；没有项目真实后端身份证据时保持 unknown。报告仅覆盖已有记录，Markdown 是索引，截图存在性及范围需单独核验；未记录的步骤和时间不补造。

本次定向同步 Codex 的 deepseek-browser-use；DSH/Claude 旧副本未覆盖。用户已安装桌面 App 未更新；隔离 dsb 服务与本次独立宿主均已停止。提交与推送状态以 Git 历史及远端分支为准。

证据见 [目录中的 JSON 与截图](assets/dsb-desktop-20261022/report.md)。
