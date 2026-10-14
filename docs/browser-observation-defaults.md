# 浏览器读取默认值与任务结束行为

普通 `close` 只结束任务。最后一个任务的浏览器与数据页保留，用户可以继续查看；旧任务的弹窗、控制台、网络监听及路由被移除。下次兼容任务复用浏览器并新开页签，不接管保留页或用户后来打开的页签。

```shell
dsb --id 1001 close                 # 最后一个任务结束后保留浏览器和数据页
dsb --id 1001 close --close-browser # 显式关闭；任务已结束也可使用
```

有其他活动任务时，`--close-browser` 返回失败。更换浏览器类型、显示模式或 profile 必须先显式关闭；不会因配置不兼容自动重建。`shutdown` 和 `server stop` 也会显式关闭浏览器，不应作为任务完成后的例行步骤。

读取页面默认不画高亮、不截图，但照常生成元素索引和结构化文本。每次读取前清除旧高亮及其滚动、缩放监听器。开关互相独立：

```shell
dsb --id 1001 state
dsb --id 1001 state --highlight
dsb --id 1001 state --screenshot
dsb --id 1001 run go_to_url -p url=https://example.com --wait-until domcontentloaded
```

导航等待阶段支持 `commit`、`domcontentloaded`（默认）、`load`、`networkidle`。HTTP 参数名为 `waitUntil`，可用 `timeoutMs` 指定 1—120000 毫秒的导航超时。页面业务完成仍需读回目标数据确认。

截图 HTTP 参数为 `get_browser_state` 的 `screenshot:true`；显式截图命令仍可使用。`browser.capture.enabled` 默认 false，明确配置 true 才恢复动作自动截图。密码输入留档和现有脱敏规则未改动。

CLI 托管 profile 按服务端口隔离：10049 保留原 `.dsb-backend/profile`，其他端口为 `.dsb-backend/profile-<port>`。直接启动 Java 的 Chrome CDP 默认 profile 为 `shared-default` 或 `shared-<port>`，Edge 为 `edge` 或 `edge-<port>`。显式路径优先，同一端口重复启动仍使用原目录。

## 本地验证（2026-10-11，Asia/Shanghai）

- Go：`build` 完成全部 CLI 测试并安装，覆盖请求参数及服务目录隔离。
- Java：通过 `build` 运行 BrowserObservationDefaultsTest、BrowserChoiceTest、CapturePolicyTest、SkillDocConsistencyTest，共 38 项通过；production JAR 构建并安装成功，跳过 GPG 签名。
- 可见 Chrome 集成测试：高亮开关不影响索引；反复读取不积累高亮监听；默认无截图且有文本留档；图片未加载完时 DOMContentLoaded 可以返回；任务结束保留页面；新任务不接管保留页、用户页或弹窗；显式关闭受其他任务保护；配置变化不能销毁用户数据页。
- CLI 独立服务（10069）：使用本地 HTML，确认按索引点击后的正文为 `Clicked successfully`，普通关闭后任务数为 0 但浏览器存在，新任务获得 `about:blank`，显式关闭后浏览器消失。验证后仅停止测试服务。

证据：[完整 CLI 回执](assets/browser-observation-20261011.json)、[本地测试页面](assets/observation-fixture.html)、[显式请求的视口截图](assets/browser-observation-20261011.png)。这些证据只覆盖本地测试，不代表任何云服务业务成功。

限制：更早的扩大测试中，BrowserProfileServiceTest 有 5 项因当前 Chrome 占用检测返回 `Profile is in use` 而失败，未计入上述 38 项，不声称 Java 全套测试通过。现有业务服务及腾讯云窗口未重启；它们仍需在合适时机加载新后端。后续已定向安装本机 Codex、DSH 和 Claude 的 deepseek-browser-use 技能，保留安装前备份并校验内容哈希；其他技能未改动。新版 JAR 已放入默认发布候选目录，现有进程未重启。
