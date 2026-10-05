# DeepSeek Harness 原生浏览器插件

`@litongjava/dsh-plugin-deepseek-browser-use` 把浏览器 HTTP 服务注册成 Harness 原生工具。浏览器动作由 TypeScript 客户端直接发送 HTTP；0.2.0 增加 Windows 后端管理，安装、Git 更新和编译阶段由随包 PowerShell 脚本执行。

```text
Harness 原生工具 / PTC → 插件会话队列 → BrowserClient → Java 浏览器服务 → Chrome / Firefox
```

## 构建与打包

要求 Node.js >= 22.19，开发适配基线为 Harness `0.2.0-rc.2`、Cordis `4.0.4`。Harness API 尚未稳定，peerDependencies 固定了已验证的版本；升级时重新编译和验证。

```powershell
cd D:\code\java\project-litongjava\deepseek-browser-use\plugins\deepseek-browser-use
npm ci --ignore-scripts --legacy-peer-deps
npm test
npm pack
```

开发安装使用 `--legacy-peer-deps`，因为只需工具运行时及其测试依赖，不需要在此目录装整套 Harness。宿主安装应使用匹配的 Harness 版本，不应借此参数绕过宿主兼容性问题。构建结果在 `dist/`，`npm pack` 会自动构建并包含清单声明的 bundle patch。

## 在 Harness 中安装

默认 bundle 已启用自动启动：激活后检查本机服务，服务未运行时检测/安装依赖、克隆、更新、编译并后台启动。已有健康服务会直接复用。远程服务或手工管理的部署请设置 `backendAutoStart:false`。

在正在使用的 Harness 会话中，让其调用 `plugin_manager`：

```json
{
  "action": "install_bundle",
  "target": "D:/code/java/project-litongjava/deepseek-browser-use/plugins/deepseek-browser-use"
}
```

应回读安装与激活状态，再调用 `dsb_backend({action:"status"})` 查看部署进度，完成后调用 `dsb_health` 和 `dsb_state`。没有 npm install 生命周期脚本；自动准备发生在启用自动启动的插件激活阶段。若是覆盖已安装的包，按 Harness 返回的 `restart-required` 状态重启。不要将“保存配置成功”视为“插件已生效”。

默认 patch：服务 `http://127.0.0.1:10049`、Chrome、有头模式、单请求超时 60 秒。服务地址指向 **Harness 后端所在机器可访问的地址**，并非 Web UI 用户电脑的 localhost。

## 配置

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:10049` | Java 服务根地址，也支持反向代理路径前缀 |
| `browser` | `chrome` | `auto/chrome/chromium/edge/firefox` |
| `headless` | `false` | 登录、验证码人工接力适合有头模式 |
| `timeoutMs` | `60000` | 每次 HTTP 请求超时，毫秒 |
| `maxTextChars` | `24000` | 模型文本展示上限，规范 JSON 仍完整 |
| `maxResponseBytes` | `16777216` | HTTP 响应体上限，16 MiB |
| `maxUploadBytes` | `8388608` | 本机文件上传上限，8 MiB，编码为 base64 后体积增大 |
| `backendAutoStart` | bundle 为 `true`；裸 Config 为 `false` | Windows 本机自动准备并启动；远程服务关闭此项 |
| `backendAutoUpdate` | `true` | 启动停止状态的后端前检查远端，并仅执行快进更新 |
| `backendInstallDependencies` | `true` | 缺失 Git/JDK 时用 winget 安装；缺失 Maven 时安装校验过的便携包 |
| `backendRepoDir` | 有 D 盘时 `D:/project/project-litongjava/deepseek-browser-use` | 否则用户目录下 `project/project-litongjava/deepseek-browser-use` |
| `backendRepository` | `gitee` | 首选 `gitee` 或 `github`；初次克隆失败时尝试另一来源 |
| `backendStartupTimeoutMs` | `120000` | Java 进程启动后的健康等待上限，不含下载和编译时间 |

首次安装前可编辑 `cordis.patch.yml`。已安装实例通过 Harness 的配置界面/用户 patch 修改，注意替换配置时保留所需字段。不同账号隔离需要不同服务实例与 profile；本插件只管理任务所有权，共享 profile 的 Cookie 仍然共享。

## 15 个原生工具

| 工具 | 用途 |
| --- | --- |
| `dsb_health` | 健康检查，不创建浏览器任务 |
| `dsb_backend` | `status/inspect/prepare/start/update/restart`，后台管理与进度查询 |
| `dsb_methods` | 查询本插件已适配的通用命令名称，可按 `filter` 过滤 |
| `dsb_start` / `dsb_close` | 显式创建/复用或关闭本会话任务 |
| `dsb_navigate` | 打开 URL，首次使用自动创建任务 |
| `dsb_state` | 最新结构化页面文本，默认不返回重复的元素元数据 |
| `dsb_click` / `dsb_input` | 索引或 CSS 选择器二选一；选择器支持 frame/nth |
| `dsb_evaluate` | 执行页面 JavaScript，参数为 `body` |
| `dsb_command` | 通用任务命令，`method` 与 `params` |
| `dsb_batch` | 顺序执行命令，返回后台 `jobId` |
| `dsb_job` | 查询自己创建的作业，或 `cancel:true` 请求协作式取消 |
| `dsb_upload` | 把 Harness 主机的 `localPath` 文件传给浏览器服务的文件输入框 |
| `dsb_screenshot` | 默认落盘返回 URL；`view:true` 通过 Harness 附件服务返回图片 |

自然语言示例：

> 使用 dsb_ 开头的原生浏览器工具打开 https://example.com，读取页面标题与正文。先读取页面状态再点击，提交动作后回读结果。

PTC 模式下工具同样可用，例如：

```javascript
await tools.dsb_navigate({ url: "https://example.com" });
const state = await tools.dsb_state({});
console.log(state);
```

工具业务返回以 `ok` 为准。传输、参数校验和生命周期错误会成为 Harness 工具错误；`ok:false` 的服务业务响应保留完整 JSON，便于读取部分结果。

## 状态与边界

- 自动依赖安装目前支持 Windows x64，使用系统的 Windows PowerShell 与 winget。Git 为 `Git.Git`，JDK 为 `Microsoft.OpenJDK.21`；已存在的 Java 21+ JDK、Git、Maven优先复用。Maven 缺失时下载 Apache `3.9.16` 并验证 SHA-512，放入仓库父目录的 `.dsb-tools`，不修改全局 PATH。需要系统提权但无法静默安装时，返回错误和日志，用户可手工安装后重试。
- 源码更新只接受已知 HTTPS origin、干净工作区和没有本地领先提交的分支，不 reset、不 stash。默认启动前更新；运行中的服务不自动中断。`update` 可预先拉取并编译，`restart` 仅在进程身份和端口归属均匹配、活动任务为零时切换。没有设置周期轮询更新。
- 源码生产构建为 `mvn -B -ntp -Pproduction -pl playwright-server -am clean package -DskipTests -Ddriver.platform=win32_x64`，默认跳过 Java 测试。JAR 按 commit 保存到 `.dsb-backend/releases/`，旧进程使用的文件不会被覆盖。代码更新不自动更新插件 npm 包本身。
- 后端为长驻独立进程，关闭会话或卸载插件只清理浏览器任务，不结束后端 Java 进程。管理操作日志在 `~/.deepseek-browser-use/operations/`，Java 日志、PID/随机实例标识和 profile 在克隆目录的 `.dsb-backend/`。下载/编译操作独立于单次工具等待，取消等待不会中止共享安装；准备失败需显式重试，普通动作不会循环安装。
- 目前源码编译不下载内嵌 Chromium；默认使用本机 Chrome。首次运行需有可用的 Chrome，其他引擎按浏览器服务文档安装配置。

- 按活跃 Agent 对象持有随机安全整数 task ID，跨轮次复用；重新加载/恢复会话会建立新的任务，不从日志重建浏览器页面。显式 close 后可以再次自动 start。
- 同一会话的 HTTP 操作串行执行。异步批次期间，仅允许查询/取消本会话的作业；读到终态后才接受下一条普通操作。关闭会话时先取消并等待已知批次结束，再关闭自己任务。
- 超时、断线或取消不会自动重试。页面操作可能已经执行；先回读结果。取消 HTTP 等待不能撤销服务端已接收的动作。
- 批次提交响应丢失、无法取得 `jobId` 时冻结该会话的浏览器操作；清理也不会关闭可能正在工作的任务。管理员按错误中的 task ID，在服务端 `list_jobs` 查到 `browserId` 对应作业，查询/取消并等待结束，最后关闭任务；之后新建 Harness 会话使用插件。
- 不暴露全局 `shutdown`、清理、跨会话作业管理、嵌套批次和配方运行。通用命令的实际集合在 `src/commands.ts`，与 Java 命令表有一致性测试。它是显式适配快照，不是在线发现；服务增加命令后应更新插件。
- `view:true` 需要附件服务、可验证的模型路由和声明支持 image 的模型；默认截图 URL 不表示模型已经看到了图片。通用 screenshot 命令不会自动转换附件，视觉读取请用专用工具。
- Java 服务仍记录原有 trace；Harness 记录工具调用。插件不额外写 `logs/agent`，也不复刻 Python 客户端的脱敏规则；页面文本和工具参数会进入 Harness 日志，应使用宿主已有的访问控制与脱敏配置。
- 第一版采用独立 `dsb_*` 工具，不占用 Harness 的独占 `browserUse` provider 槽，不替换已有 Playwright MCP 工具。使用时明确选用 `dsb_*`，避免一项任务混用多个浏览器后端。
- `execute_js`、Cookie、上传等能力仍是可信本地自动化能力；任务绑定不是多租户安全边界。远程服务的鉴权和 TLS 由部署层负责，本包没有新增服务端认证协议。

## 验证

`npm test` 验证 HTTP 错误、取消、会话队列、作业归属、文件传输、附件适配、输出截断与真实 Harness ToolRuntime 注册/派发。

对运行中的浏览器服务进行联调（只关闭测试自己的任务）：

```powershell
$env:DSB_BASE_URL = 'http://127.0.0.1:10049'
npm run smoke
```

此 smoke 会在 Harness 主机起本地测试页面，浏览器服务也须能访问这台主机的 loopback；测试远程部署应使用可访问的测试站点调整脚本。

开发者也可以在仓库根目录生成 Java classpath，再用隔离服务验证：

```powershell
mvn -q -o -pl playwright-server compile dependency:build-classpath '-Dmdep.outputFile=target/plugin-smoke-classpath.txt'
cd plugins/deepseek-browser-use
npm run build
node scripts/isolated-smoke.mjs
```

隔离 runner 使用独立随机端口、工作目录、profile 和 JDK 临时 socket 目录，结束后关闭自己启动的服务，记录保存在仓库 `logs/plugin-smoke/`。普通插件从不关闭共享服务。

完整中文教程在文档工程 `docs/zh/65_ai-browser/29.md`、`30.md` 与 `31.md`。


## 截图预算与验收证据（2026-10-05）

这是 **DeepSeek Harness适配插件**；主项目的 `dsb.cmd` 是独立CLI入口。本插件直接调用Java服务，不启动CLI。下面的策略需要配套更新后的Java后端；更新本插件源码不会自动替换已经运行的服务。旧服务返回 `unknownParams` 时，新增参数尚未生效。

`dsb_screenshot`新增可选参数：

| 参数 | 默认 | 语义 |
|---|---|---|
| `timeoutMs` | 服务端 `browser.capture.timeoutMs`，默认8000 | 本次截图预算，1—120000毫秒；仍受服务端30秒共享重试窗口限制，区别于插件HTTP请求超时 |
| `force` | false | 熔断期间主动探测一次；不内部重试、不视口回退，失败继续冷却 |
| `fallbackToViewport` | false | 全页失败时使用剩余预算尝试视口截图；仅 `fullPage:true`有效 |

```javascript
const shot = await tools.dsb_screenshot({ fullPage: true, fallbackToViewport: true, timeoutMs: 8000 });
// ok:true 也可能仅取得视口，检查 data.fullPageCaptured / fallbackUsed / warning。
```

自动截图与手动截图共用任务级熔断。`capture_degraded`、`retryAfterMs`说明图片取证缺失及冷却时间；不要通过重复手动截图绕过熔断。`data.capture`包含实际模式、截图调用次数、耗时、失败阶段、可获取的视口尺寸和连接状态。`data.retryBudget`报告共享重试统计；服务端事件泵重试最多共用两次额外机会，插件传输层仍不自动重发未知结果的写操作。

验收记录应分开写 **前置条件、业务断言、证据完整性**。截图失败不等于业务失败；视口图不等于全页图；未登录后台不能验收具名管理员的授权只读界面。CLI的stderr提示不适用于Harness，Harness调用方应读取工具返回JSON中的诊断字段。完整模板见 [skill取证说明](../../.agents/skills/deepseek-browser-use/references/testing-evidence.md)。
