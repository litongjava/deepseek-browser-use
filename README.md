# DeepSeek Browser Use

面向 AI 智能体的有状态浏览器服务。通过 HTTP API、`dsb` 命令行或 DeepSeek Harness 原生插件，让智能体读取网页、点击按钮、填写表单，并核验操作结果。

基于 Java 21、Tio Boot 和 Playwright，支持 Chrome、Edge、Chromium 与 Playwright Firefox。浏览器默认显示窗口，方便人工完成登录、验证码等步骤；专用 profile 可保留登录态，供后续任务复用。

[快速开始](#快速开始) · [智能体接入](#智能体接入) · [CLI 文档](dsb/README.md) · [操作参考](.agents/skills/deepseek-browser-use/SKILL.md) · [视频演示](https://www.bilibili.com/video/BV1FAh262EXf/)

## 核心能力

- **结构化页面读取**：将页面转换为带元素索引的文本，支持表单状态、表格和 Markdown 提取，截图按需生成。
- **浏览器交互**：导航、点击、输入、滚动、页签切换、文件上传与下载，以及页面 JavaScript 执行。
- **会话复用**：同一服务共享浏览器和持久化 profile，任务通过页签划分，减少重复登录。
- **可验证的操作**：支持前置条件、结果断言、回读、命令诊断与取消；批量操作可作为异步作业执行。
- **过程留档**：保存页面文本、显式请求的截图及调用记录，支持精简响应和字段筛选。
- **桌面 WebView 接入**：可连接已开放 CDP 的浏览器页面或 WebView2 应用；结束外部连接任务时仅断开连接，保留宿主。

适用于后台操作、信息查询、表单处理和网页测试。仓库还提供铁路查询、企业微信、视频上传等[专题技能](.agents/skills)，以及可显式执行的[站点配方](recipes/README.md)。

## 快速开始

### 1. 准备环境

从源码启动需要：

- JDK 21+、Maven 3.8+；
- Go 1.24+，用于安装 `dsb`；
- 本机 Google Chrome，推荐作为首次运行的浏览器。

```bash
git clone https://github.com/litongjava/deepseek-browser-use.git
cd deepseek-browser-use
```

也可使用 [Gitee 镜像](https://gitee.com/ppnt/deepseek-browser-use)。

### 2. 安装 CLI 并启动服务

在仓库根目录执行：

```bash
cd dsb
go install .
cd ..
```

将 Go 的安装目录加入 `PATH`（默认是用户目录下的 `go/bin`），然后执行：

```bash
dsb --version
dsb server init --repo-dir .
dsb server build
dsb server start
dsb health
```

服务默认端口为 `10049`。`server init` 保存仓库位置，后续可在其他目录使用 CLI；本机服务不可达时，CLI 可按配置自动启动后端。远程服务需在远端单独启动。

### 3. 打开并读取网页

以下示例用于**独立 CLI 会话**，显式指定任务 ID：

```bash
dsb --id 1001 start
dsb --id 1001 run go_to_url -p url=https://example.com
dsb --id 1001 state --text-only
dsb --id 1001 state --screenshot
dsb --id 1001 close
```

`start` 默认显示窗口；需要无头模式时加 `--headless`。`state` 默认不截图，`--screenshot` 才生成图片。

`close` 结束当前任务。最后一个托管任务结束时，浏览器和页面保留；需要显式关闭浏览器时使用 `close --close-browser`，存在其他任务时会拒绝关闭。

宿主已经绑定浏览器任务时，直接使用 `dsb …`，复用现有任务，不另建任务或重复指定 `--id`、`--session`。独立 CLI 可通过 `DSB_TASK_ID` 接收任务绑定；`--session` 仅用于调用记录命名。

### 使用已打包的 JAR

平台发行包包含对应平台的 Chromium 和 Playwright driver，运行需要 JDK 21+。例如：

```bash
java -jar deepseek-browser-use-1.0.0-windows-x64.jar
```

服务仍优先选择本机 Chrome；使用内嵌 Chromium 时会解压到用户缓存目录。Linux 还需满足浏览器的系统库要求；默认有头模式需要可用的桌面显示环境。构建方式见[开发与打包](#开发与打包)。

## 智能体接入

### DeepSeek Harness 原生插件

[Harness 插件](plugins/deepseek-browser-use/README.md)提供 `dsb_*` 原生工具，在宿主进程内直接调用 HTTP 服务，管理会话任务、异步作业、文件传输和截图附件。

使用 Harness 时可优先选择该方式。安装、宿主版本要求与 Windows 后端自动准备流程见插件文档。

### Skill + CLI

适用于 DSH、Claude Code、Codex 等能执行命令行的智能体。技能源统一位于 [`.agents/skills/`](.agents/skills)，可使用同步脚本安装到对应工具：

```bash
# 预览安装内容
node scripts/sync-skills.mjs --all --dry-run

# 选择需要的工具，也可只写其中一个
node scripts/sync-skills.mjs dsh claude codex

# 检查安装状态
node scripts/sync-skills.mjs --status
```

DSH 与 Codex 保留技能原名；Claude Code 使用 `dsb-skills` 插件命名空间，例如 `/dsb-skills:deepseek-browser-use`。同步时遇到未受脚本管理的同名技能，应先检查差异。

可直接使用以下提示词：

```text
读取 deepseek-browser-use 技能，使用 dsb 完成浏览器操作。
复用宿主已绑定的任务；操作前读取页面状态，操作后核验业务结果。
遇到需要人工完成的登录或验证码时暂停并说明当前状态。
任务：打开 https://example.com，读取页面标题与正文。
```

CLI 参数、输出筛选和后端管理见 [dsb 文档](dsb/README.md)；站点任务按需加载对应专题技能。

## 工作方式与常用配置

### 浏览器与登录态

一个服务实例共享一个托管浏览器和 profile。任务的页签分开管理，但 Cookie、权限等浏览器上下文设置仍然共享，**不构成账号或租户之间的安全隔离**。

| `start --browser` | 选择方式 |
| --- | --- |
| `auto`（默认） | 优先本机 Chrome，不可用时选择 Chromium |
| `chrome` | 本机 Google Chrome |
| `edge` | 本机 Microsoft Edge |
| `chromium` | 发行包内嵌或 Playwright 管理的 Chromium |
| `firefox` | Playwright 提供的 Firefox，需准备对应浏览器资源 |

显式指定 Chrome 或 Edge 时，未安装会报错。浏览器类型、显示模式或 profile 与现有实例不兼容时，需结束任务并显式关闭浏览器后再切换。以 `start` 回执中的 `data.browser` 确认实际浏览器及 profile。

默认使用专用 profile，首次登录通常需要人工完成；后续能否继续登录取决于站点会话有效期。需要同时运行不同账号或浏览器时，使用不同服务端口和独立 profile 目录。

### 常用命令

| 命令 | 用途 |
| --- | --- |
| `dsb health` / `dsb config` | 检查服务健康与生效配置 |
| `dsb tasks` / `dsb methods` | 查看任务与支持的命令 |
| `dsb state --text-only` | 读取结构化页面文本 |
| `dsb state --screenshot` | 获取页面状态并截图 |
| `dsb js '@query.js'` | 从文件执行页面 JavaScript |
| `dsb batch commands.json --async --wait` | 执行并等待异步批次 |
| `dsb diagnose` | 查看活动或最近命令的诊断 |
| `dsb last` | 读取本地已记录的响应，不重新执行动作 |
| `dsb server status` / `dsb server logs` | 查看后端状态与日志 |
| `dsb server stop` | 停止后端服务并关闭其托管浏览器 |

常规调用可用 `--summary` 减少终端输出，用 `--select` 筛选字段，用 `--out` 保存结果。`--response-mode compact` 控制服务端响应精简，与本地 `--summary` 是不同选项。

点击前读取最新页面状态，避免使用过期索引。请求超时或取消后，动作可能已经执行；先回读业务状态，再决定下一步。命令返回 `ok:true` 不代表提交、付款等业务已经成功。

### 配置入口

浏览器配置见 [`browser.properties`](playwright-server/src/main/resources/browser.properties)，服务端口见 [`app.properties`](playwright-server/src/main/resources/app.properties)。运行时可通过 `dsb config` 查看生效值。

| 配置项 | 用途 |
| --- | --- |
| `browser.type` | 默认浏览器类型 |
| `browser.profileDir` | 显式指定托管 profile 目录 |
| `browser.viewport` | 跟随窗口或指定固定视口 |
| `browser.capture.enabled` | 是否自动截图，默认关闭 |
| `browser.trace.enabled` | 是否记录服务端调用追踪 |
| `browser.trace.redact.enabled` | 是否启用追踪日志脱敏 |
| `browser.data.retentionDays` | 页面证据保留天数，默认不自动清理 |

## HTTP API

浏览器命令统一发送到 `POST /playwright/command`：

```http
POST /playwright/command HTTP/1.1
Host: localhost:10049
Content-Type: application/json

{
  "id": 1001,
  "method": "go_to_url",
  "params": { "url": "https://example.com" }
}
```

先通过 `start` 创建任务，再使用同一 `id` 调用其他任务命令。响应通过 `ok`、`code` 和 `data` 表达执行结果；失败时读取错误信息及诊断字段。方法列表以运行中服务返回的内容为准。

| 端点 | 用途 |
| --- | --- |
| `GET /playwright/health` | 健康检查 |
| `GET /playwright/methods` | 命令清单 |
| `GET /playwright/tasks` | 任务与浏览器状态 |
| `GET /playwright/config` | 生效配置 |
| `POST /playwright/upload` | 上传文件到服务端暂存区 |
| `GET /data/**` | 获取已保存的截图和页面文本 |

完整方法、参数与返回字段见[操作参考](.agents/skills/deepseek-browser-use/SKILL.md)。

## 开发与打包

后端构建约定见 [`build.txt`](build.txt)。配置好 JDK 21 后，使用仓库构建入口：

```bash
build
```

没有安装 `build` 工具时，可执行该文件定义的 Maven 命令：

```bash
mvn clean install -DskipTests -Dgpg.skip
```

开发态运行与测试：

```bash
# 从仓库根目录运行后端测试
mvn test -Dgpg.skip

# 开发态启动
cd playwright-server
mvn spring-boot:run
```

开发态未内嵌浏览器，首次使用可能需要下载 Playwright 浏览器资源。测试范围与真实浏览器验收应分别确认。

打包平台发行版还需要 Node.js 18+ 及访问 Playwright 下载源的网络：

```bash
# 在仓库根目录执行，默认打包当前平台
node scripts/package/build-release.mjs

# 指定平台或全部平台
node scripts/package/build-release.mjs --platform=linux-x64
node scripts/package/build-release.mjs --platform=all
```

支持 Windows x64、Linux x64/arm64、macOS x64/arm64，产物输出到 `dist/`。打包默认跳过测试，使用 `--with-tests` 启用；跨平台打包不等于已在目标系统验证运行。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [CLI 手册](dsb/README.md) | 安装、子命令、配置、后端管理与退出码 |
| [Harness 插件](plugins/deepseek-browser-use/README.md) | 原生工具安装、配置与宿主兼容性 |
| [浏览器操作参考](.agents/skills/deepseek-browser-use/SKILL.md) | 能力、参数与智能体操作规则 |
| [命令可靠性](.agents/skills/deepseek-browser-use/references/command-reliability.md) | 超时、取消、定位、断言与回读 |
| [截图与测试取证](.agents/skills/deepseek-browser-use/references/testing-evidence.md) | 截图失败处理与证据要求 |
| [桌面 WebView 验收记录](docs/dsb-desktop-testing-results-2026-10-22.md) | CDP 接入能力、实际验证与边界 |
| [票务结果解析与校验](docs/ticket-results.md) | 日期、席别、金额与来源检查 |
| [站点配方](recipes/README.md) | 可复用 JSON 命令序列 |
| [技能编写约定](docs/SKILL-CONVENTIONS.md) | 专题技能的维护规范 |

主要目录：`playwright-server/` 为 Java 服务，`dsb/` 为 Go CLI，`plugins/` 为宿主插件，`.agents/skills/` 为技能，`scripts/` 为构建和运维脚本，`docs/` 为专题文档与验收记录。

## 部署与数据

服务提供脚本执行、文件上传和页面数据读取能力，本身不提供鉴权。应部署在可信环境，对外访问需由部署层提供认证与访问控制，不要直接公开 `/data/**`。

运行产物主要位于 `data/`、`logs/trace/`、`logs/agent/` 和 `upload/`。追踪日志默认脱敏，但页面文本、截图和上传文件仍可能包含敏感信息；共享前应检查，并按需配置保留期或清理。停止服务使用 `dsb server stop`。

## 作者与许可

Tong Li（李通） · [GitHub @litongjava](https://github.com/litongjava) · [litongjava@qq.com](mailto:litongjava@qq.com) · 微信 `jdk131219`

采用 [MIT License](LICENSE)。欢迎通过 [Issues](https://github.com/litongjava/deepseek-browser-use/issues)反馈问题或补充站点技能。
