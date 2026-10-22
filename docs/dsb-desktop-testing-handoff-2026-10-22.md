# dsb 桌面测试能力优化交接

日期：2026-10-22。状态：完成需求梳理和初步源码核查，尚未实现以下优化。

## 新会话任务

根据本文优化 deepseek-browser-use，优先完成外部 Tauri/WebView2 接入和测试环境校验，再完善点击证据、验收报告和多字段输出。先复核当前源码，复用已有能力，不把现有功能重复实现。

本文是交接与验收依据，不表示以下命令或字段已存在。接口名称均为设计建议，实施时遵循现有协议和兼容性要求。未明确的实现细节可按仓库惯例决定。

## 背景与本次真实经验

用户希望测试 Java 项目自带的 Tauri 桌面前端，验证 Codex 配额查询曾出现的 `Failed to fetch quota / Management API error (500): Service unavailable`。

本次验证实际分为两部分：

1. dsb 操作 Chrome 中的 Java 项目 Vite 预览，检查配额展示、主题和插件导航。此环境使用 mock，只能证明相关前端行为。
2. 另写 Playwright CDP 脚本连接隔离运行的 Tauri WebView2，验证真实 Java 内核与 Codex 配额查询。确认 URL 为 `http://tauri.localhost/`，未启用 browser mock；最终 release 查询显示周额度剩余 82%，无错误。

原生测试中部分鼠标定位操作遇到问题，最终部分操作使用 DOM 点击。因此不能宣称全部验证了用户鼠标操作路径，也不能据此认定 dsb 存在点击缺陷：原生测试用的是独立脚本，尚未在 dsb 内复现。

最初选用 mock 页面不能满足桌面测试要求，这是执行范围判断问题。工具可以通过环境校验帮助避免误判，但不能替代正确选择测试对象。

本次 dsb 任务结束后已解除控制，保留共享浏览器；隔离桌面测试进程已停止。不要假定旧调试端口仍可连接。

## 当前源码已具备的能力

以下内容已做源码检查，实施前仍需确认最新版本：

- Chrome/Edge 已有 CDP 基础；主要启动流程由服务启动浏览器，再建立连接。尚未在本轮检查中确认有完整的、公开的外部 WebView2 接入入口，先全面核查再扩展。
- 点击已有命中检测、遮挡对象反馈和模式记录。
- `PlaywrightService.ActionOutcome` 已记录 `mode`、`fallbackReason`、`fallbackFrom`；不能把“增加实际点击方式字段”当成从零新增的功能。
- 已有 `expect/readback`、命令超时与取消、动作状态未知时的处理和紧凑输出。
- CLI 已有 `--summary`、`--select`、`--out` 和本地调用记录；`--select` 当前按单一路径投影。

## P0：连接已有 Tauri/WebView2

### 目标

允许 dsb 连接已开启调试端口的桌面 WebView，直接复用状态读取、定位、动作、断言和截图能力，减少单独编写 Playwright 脚本的需要。

### 设计要点

- 提供明确的外部 CDP endpoint 参数或 attach 子命令；具体名称实施时确定。
- 枚举目标并支持按 target ID 或 URL 选择，避免选到空白页、插件页或其他应用窗口。
- 返回连接模式、目标 URL、target ID，以及可确认的宿主信息；无法获取的信息明确标为未知。
- 区分“工具启动的浏览器”和“外部附着的应用”，明确生命周期所有权。
- 外部附着任务关闭时只解除控制，不关闭宿主 App，不结束其进程；不得连带关闭共享浏览器的其他任务。
- 明确支持边界：本阶段针对可通过 CDP 接入的 Windows WebView2，不宣称覆盖所有操作系统上的 Tauri WebView。
- 调试端口未启用、目标已关闭、目标多于一个时，返回可操作的诊断信息。

### 验收

- 使用隔离的 Tauri 测试构建，完成 attach → 选择目标 → 读取状态 → 交互 → 业务断言 → 截图 → detach。
- detach 后宿主 App 继续运行，其他任务仍可使用。
- 覆盖无目标、多目标、目标关闭和连接中断；不会静默改连其他页面。
- 现有 Chrome/Edge start/close 行为通过回归检查。

## P0：测试环境身份校验

### 目标

测试开始前能够证明连接的是预期应用及运行环境，避免把 mock 前端验证误报为真实桌面链路验证。

### 设计要点

- 通用层记录实际 URL、目标类型、连接方式。
- 项目自定义断言提供应用版本、mock 状态、后端地址或内核身份等信息；不要在通用工具里硬编码 Java 项目变量名。
- 环境状态使用真实、模拟、未知等明确分类；不能仅凭 URL 或存在 Tauri 标志就认定后端是真实服务。
- 允许将环境断言设为测试前置条件，失败时阻止后续动作并输出原因。
- 与已有 `expect/readback` 复用同一套断言机制，避免新增平行实现。

### 验收

- mock 页面运行“必须连接真实后端”的用例时，在交互前失败。
- 原生测试需要同时满足目标身份与项目提供的后端断言。
- 无法确认 mock 状态时显示未知，不能推断为真实。

## P1：完善点击诊断和证据

### 目标

沿用现有点击模式及降级记录，让调用者和验收报告清楚知道最终验证了哪条交互路径。

### 设计要点

- 检查各点击入口是否一致返回请求方式、实际方式、降级原因及必要的定位证据；补齐缺口而非替换已有字段。
- 诊断中按需提供目标边界框、点击坐标、命中对象和坐标系信息，尤其关注 iframe、缩放、WebView2 等情况。
- 提供严格交互选项或复用已有严格模式：要求鼠标路径时，不静默降级为 JS 后报告鼠标验收成功。
- 动作状态未知时先观察业务结果，不为了补充诊断重新派发动作。
- 独立复现本次 WebView2 点击问题，确认原因后再修复，不预设是 DPI、遮挡或 dsb 实现问题。

### 验收

- 覆盖正常点击、被遮挡、已发生降级、结果未知等案例。
- JS 点击即使业务断言通过，报告仍明确其交互方式。
- 现有返回字段保持兼容；不会因采集证据造成重复提交。

## P1：生成结构化验收报告

### 目标

把现有调用记录、动作回执、断言和截图关联成可审阅的测试报告，减少手工整理证据。

### 建议内容

- 运行标识、时间、应用/工具版本、环境校验结果。
- 步骤、命令 ID、动作实际执行方式、业务断言及结果。
- 请求或响应证据的引用、截图和其他产物路径。
- 成功、失败、跳过、未知分别统计。
- 区分命令执行成功、页面交互成功、真实业务查询成功。

建议先提供 JSON，再生成便于阅读的 Markdown。报告生成只读取现有证据，不重新执行测试动作；日志缺失或截图失败应明确记录，不能自动补成成功。

### 验收

- 同一份记录可重复生成报告，不产生页面操作。
- mock 用例与真实原生用例有明显区别。
- 断言失败、回读失败及动作状态未知均完整保留。

## P2：多字段输出筛选

### 目标

在一次 CLI 调用中提取多个字段，例如 URL、业务结果、错误和截图路径，减少额外解析脚本。

### 设计要点与验收

- 可考虑重复 `--select` 或新增 `--fields`，确定输出结构和字段别名规则。
- 保持原有单字段模式的输出与退出码兼容。
- 明确多个字段中部分缺失时的行为，覆盖成功及失败响应。
- 筛选失败不能触发动作重放；仍可通过已有记录读取原始结果。

## 源码入口

路径相对本仓库根目录，行号可能随修改变化，优先按符号搜索。

| 文件 | 关注点 |
| --- | --- |
| `playwright-server/src/main/java/nexus/io/ai/browser/service/PlaywrightService.java` | `launchChromiumFamily`、`launchOverCdp`、共享浏览器生命周期、`ActionOutcome`、`JS_HIT_TEST`、`hitTest`、点击模式 |
| `playwright-server/src/main/java/nexus/io/ai/browser/service/ChromeLauncher.java` | 浏览器启动与调试地址 |
| `playwright-server/src/main/java/nexus/io/ai/browser/service/CdpLaunchSupport.java` | CDP 启动后的能力配置 |
| `playwright-server/src/main/java/nexus/io/ai/browser/service/CommandControl.java` | 命令状态与取消边界 |
| `dsb/args.go`、`dsb/commands.go` | 参数解析、启动与命令分派 |
| `dsb/output.go` | 投影、摘要和筛选错误 |
| `dsb/record.go` | 调用记录与报告数据来源 |
| `.agents/skills/deepseek-browser-use/` | 仓库内技能源及命令说明 |

相关现有测试：`BrowserConsistencyTest`、`BrowserObservationUpgradeTest`、`CommandReliabilityTest`、`ResponseFormatterTest`、`BrowserResponseIntegrationTest`，以及 `dsb/reliability_test.go`、`dsb/cli_recovery_test.go`、`dsb/dsb_test.go`。

## 工作约定与执行顺序

1. 阅读仓库 `AGENTS.md`、相关技能全文和 `build.txt`，检查工作区及实际安装版本。
2. 先检查是否已有外部 attach 能力，再实现 P0 的最小完整链路与测试。
3. 完成 P1 和 P2，或在存在独立交付边界时分阶段提交，准确记录未完成部分。
4. Go CLI 运行相关测试及 `go test ./...`（在 `dsb` 目录）；Java 使用 JDK 21，先运行相关测试再执行必要的整体回归。`build.txt` 中跳过测试的构建命令不能代替验收。
5. 真实浏览器和 WebView2 验收必须使用包含本次代码的服务；不能只构建而继续测试旧服务。使用隔离服务或先确认共享任务状态，避免影响其他任务。
6. 更新对应命令文档和技能源；同步技能前先检查 `scripts/sync-skills.mjs`，不强制覆盖无关技能。
7. 最终报告分别说明源码修改、构建、自动测试、浏览器测试和原生桌面验证结果；未执行项目标为未验证。

## Java 测试项目与既有交付状态

如需复用桌面测试对象，当前机器的 Java 项目在 `D:\code\java\project-litongjava\java-llm-proxy`。该路径仅为本地交接信息，不应写进对外使用文档或硬编码到 dsb。

- Java 项目改动已提交为 `4ec4abb53c462c60ad40ffeebc8d39e3c6597bef`，分支 `fix/output-config-effort`，已推送 GitHub 和 Gitee。
- 相关说明见 Java 项目的 `docs/upstream-sync-2026-10-22.md`。
- 原生测试脚本和截图曾保存在其忽略目录 `target/upstream-sync/native-review`；这些是本地产物，新会话先确认存在，不能假定已纳入版本控制。
- 用户明确要求手动更新 App。不要替换、安装或自动更新用户已安装的桌面客户端；测试使用独立构建和运行目录。
- 本文不要求再次移植上游功能，也不要求修改上游原版项目。

## 可复制到新会话的开场指令

> 请阅读 `docs/dsb-desktop-testing-handoff-2026-10-22.md`，按其中优先级实现 dsb 的桌面测试能力优化。先复核现有实现，避免重复建设。优先完成外部 Tauri/WebView2 接入、生命周期隔离和环境身份断言，再完善点击证据、验收报告与多字段输出。使用隔离测试环境，不自动更新我的已安装 App。完成后报告实际修改、测试证据及未完成项。
