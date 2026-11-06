---
name: deepseek-browser-use
description: 只要需要使用浏览器就使用本技能和 dsb，包括加载或打开浏览器、浏览网页、读取页面、交互、截图、网页测试及可通过 CDP 连接的桌面 WebView2 测试。统一使用 dsb CLI，复用宿主浏览器绑定。
---

> Claude Code：`dsb-skills:deepseek-browser-use`。

# DeepSeek Browser Use

## 浏览器统一入口

只要使用浏览器，就使用 dsb。用户只说“加载浏览器”“打开网页”也触发本技能；不改用内置浏览器工具、浏览器 MCP 或独立 Playwright/Selenium 脚本。dsb 不可用时先查帮助和诊断，明确报告能力缺口，不静默切换工具。桌面 WebView2 见 [桌面接入与验收](references/desktop-testing.md)。

每个浏览器任务只有一个所有者，读取和截图也串行。通过消息交接任务；记录在途命令，使用有界等待，不 busy-poll。宿主 jobId、dsb jobId、浏览器 task ID、subagentId 不混用。

数字、价格、时间和表格任务同时读取 [网页取数规范](../web-data-as-text/SKILL.md)；12306 查询再读取 [铁路技能](../railway-12306-ticket/SKILL.md)。站点操作按需加载对应技能。

超时、取消、同名按钮、业务断言、上传和敏感留档按 [命令可靠性](references/command-reliability.md) 执行：卡住时用 `diagnose`，按 commandId 取消；动作结果未知时先观察。普通 `run` 可带 `--expect` 和 `--readback`，含凭据的脚本加 `--sensitive`。

## 使用 dsb

`dsb` 已在 PATH 上，直接调用；不要用 curl 或 Invoke-WebRequest 手拼请求。默认服务地址为 `http://localhost:10049`，本机服务未启动时普通命令会自动启动后端并重试一次。远端目标用 `--host` / `--port` 或已登记的 `--use` 指定。

复用宿主 session 已绑定的浏览器任务，命令中无需重复指定 `--id`/`--session`，不要另建任务覆盖绑定。独立 Go CLI 从 `DSB_TASK_ID`/`DSB_SESSION` 读取上下文；仅 `--session` 不会建立浏览器绑定。无绑定的独立终端才需要初始化任务上下文，不能把默认任务当成当前会话。

~~~powershell
dsb list_methods                  # 与 dsb methods 等价
dsb list_methods click            # 按名称过滤
dsb run --help                    # 不确定参数先查对应子命令帮助
dsb start --browser chrome --summary
dsb run go_to_url -p 'url=https://example.com' --summary
dsb state --text-only
dsb js '@query.js' --select data.result
dsb batch commands.json --async --wait --out result.json --summary
~~~

- `list_methods` / `methods` 查询当前服务的能力，不按文档中的固定方法数量判断。开发后更新本机后端用 `dsb server build`、`dsb server restart --when-idle`：有活动任务时普通 `restart`/`stop` 会拒绝执行，`--when-idle` 等任务结束，`--force` 才会中断它们。`dsb server status` 显示运行中的 jar 和是否需要重启。
- `start` 默认显示窗口；需要无头模式时才用 `--headless`。回读 `data.browser.headless` 和实际浏览器字段确认。报 `BROWSER_CONFIG_CONFLICT` 或 `DISPLAY_UNAVAILABLE` 时什么都没启动：按回执的 `hint` 处理，不要关闭别人的浏览器。要让用户在 VNC 里看到窗口，换一个任务 ID 后 `start --display :1` 即可，不用换端口：服务里已有无头浏览器时，会在同一端口另起一个显示在 `:1` 上的 Chrome，两边任务互不影响。这个 Chrome 用单独的 profile，登录状态和主 profile 不共享。
- PowerShell 文件参数写成 `'@query.js'`、`--params '@params.json'`。多行 JS / 嵌套 JSON 放文件；协议示例 `{id,method,params}` 可存入参数文件交给 `dsb run`。
- CLI 默认请求精简响应；需要完整诊断时用 `--diagnostics` 或 `--response-mode full`。不能重放动作补诊断。
- 常规动作使用 `--summary`；只读回目标字段，避免反复打印整页。单行 JSON 配置不能用 `head`/`Get-Content -TotalCount` 控制大小，应解析后投影白名单字段。完整配置、规则正文、凭据不得进入对话或提交。
- `--select data.result --raw` 直接打印字符串原文（换行就是换行），读长文本不用再反转义。结构化响应用 `--out result.json` 保存（不是图片）；截图文件位置读 `data.path`。PowerShell 读取时加 `-Encoding UTF8`。`--select` 保持单路径；多字段用可重复的 `--fields 别名=路径`，详细规则见 [客户端](references/client.md)。
- 退出码：0 成功、1 传输错误、2 业务失败、3 用法错误。输出筛选失败不等于动作未执行，先查对应留档，不能为了修输出重发动作。
- `--select` / `--fields` 的业务错误摘要保留在 stderr；新建临时 profile 不代表已有登录态。显式截图保留实际视口观测及图片尺寸，桌面调整尺寸后核对它们，见 [测试与证据](references/testing-evidence.md)。

## 读取与交互

1. 导航后用 `get_browser_state` / `dsb state` 读页签和 `data.text`；文本中的 `[index]` 是可交互元素索引。
2. 执行动作，优先用限定 scope 的 role/name/exact 或唯一选择器；按索引时带上当前 snapshotVersion。点击、导航、异步渲染后重新取快照；`indicesUsable:false` 或 `snapshotConsistent:false` 时不使用该次索引。
3. 用有界的 `wait_for_text` / `wait_for_function` / `wait_for_response` 等待目标结果。轮询页不适合 `wait_for_idle`，可改用 `wait_for_stable` 或具体业务条件。`wait_for_stable` 返回 `contentReady:false` 表示页面虽不再变化，但主内容为空或仍在加载，不能当作已加载完；必须有内容时传 `requireContent:true`。
4. 填完表单用 `get_form_state` 核对。自定义下拉还要核对显示值、选中项和错误状态；DOM 中有值不保证已进入框架模型。
5. 回读业务后置条件，如目标订单状态、稿件列表或申请记录。`ok`、`changed`、`effective` 和 DOM diff 都不能单独证明业务成功或失败。

| 情况 | 处理 |
| --- | --- |
| 视口外元素没有索引 | 用选择器，或 `state --viewport-expansion -1` 后重新定位 |
| 顶层页面读不到内容 | `list_frames` → `state --include-frames`；索引自带 frame，选择器和 JS 按需传 `frame` |
| 只找一段文本 | `find_text`；表格用 `list_tables` + `extract_markdown` 保留行列关系 |
| 需要元素属性 | `get_interactive_map`、`get_element_attribute` 或只读 JS |
| 多个同名元素 | role 定位返回 `AMBIGUOUS_LOCATOR` 时读 candidates，缩小 scope 或明确 nth |
| 上传后表单仍空 | 核对目标 file input、`consumed` 和 `get_element_listeners`，再检查组件状态；不盲目重传 |
| 有遮挡或弹窗 | `get_modals` 查看 DOM 弹窗与 covers 候选；`get_dialog` 只看原生弹窗。covers 仅诊断，不自动关闭 |
| 需要接口响应 | `get_response_body` 回看或 `wait_for_response` 等待；`get_requests` 只有请求元数据 |
| 新页签动作报错 | 先 `get_tabs` 核对是否已创建、当前操作页是否切换；不要直接重开 |
| 点击应打开新页签 | 加 `--expect-popup`，回执的 `newTab` 给出新页签，当前页已切换。普通点击的 `changed:false` 不代表没生效，新页签可能晚到：重复点击前先 `wait_for_new_tab -p baseline=<点击前页签数>` |
| 输入框不在索引里（Ace、CodeMirror、CloudShell 等） | `keyboard_type -p selector=textarea.ace_text-input -p text=...`，用真实按键输入 |
| 页面空白或控制台显示加载失败 | `get_page_diagnostics` 汇总失败请求、HTTP 错误、控制台错误和 CSP 拦截；回执提示记录刚开启时，先 reload 再读 |

`actionStatus:unknown`、`probeTrustworthy:false` 或 `observationComplete:false` 时先观察业务结果，禁止自动补点。`--retry-on-spurious` 只用于可安全重发的只读脚本，点击、提交、支付和发送消息不能自动重发。

上传路径属于服务端机器；不同机器先用 `dsb upload`。隐藏 file input 可直接用选择器，命中多个时用 `nth` 或唯一选择器指定。参数详见 [命令分册](references/commands.md)。页签文本 `current tab is: N` 为 1 基，`switch_tab` 的 `pageIndex` 为 0 基。

## 非必要不要读图

默认从 DOM / 接口文本取数与定位，不因响应带了截图地址就打开图片。默认不画高亮、不截图，截图需显式请求。

二维码、验证码、图表、纯图片内容或必须核对视觉效果时，优先截目标元素。扫描附件优先 `download_image` 取原件，再 OCR 或读图；看不清就报告限制。业务状态仍以目标文本、记录或响应核验。

截图失败、熔断、全页回退与测试留证见 [测试与证据](references/testing-evidence.md)；视口截图不能当作全页证据。

## 批量与 JavaScript

批次执行前校验所有步骤的静态参数；`INVALID_PARAMS` 表示整批未执行。通过预校验不保证运行时成功，也不提供回滚。一个批次只包含一个计划段，末尾读取结果。依赖动作保持失败即停；仅对相互独立的观测按需用 `--keep-going`。长批次用 `--async --wait`，收集结果；超时不代表后台已停止。

`expect` 与动作放在同一个步骤中，判断具体业务条件：

~~~json
{"click_element_by_selector":{"selector":".dialog .confirm","mode":"mouse"},
 "expect":{"js":"document.querySelectorAll('.dialog').length","equals":0}}
~~~

`execute_js` 会等待 Promise，返回 JSON 可序列化值。页面内 fetch 前核对 origin、目标 URL 与 frame；相对 URL 基于当前文档解析，`credentials:'same-origin'` 不会切换站点。细节见 [批量与 JS](references/batch-and-js.md)。

## 人工协助与任务收尾

回执带 `data.challenge`（`BOT_CHALLENGE`）说明页面停在 Cloudflare 人机验证页，不是站点内容：无头浏览器常被拦在这里，换一个任务 ID 用 `start --display :1` 开有头浏览器，再请用户通过验证，不要反复点击或循环刷新。需要登录、扫码、验证码或人工支付时，保留有头浏览器，用 `request_human_input` 记录请求并通过对话通知用户。root、管理员等敏感账号用 `--ephemeral` 启动：临时 profile 随浏览器关闭一起删除；否则登录状态会留在 profile 里，收尾时要明确告诉用户。不能仅凭接口成功就认为用户收到通知；等待期间不刷新或继续点击验证控件。用户完成后回读页面再继续，`pending` / 超时不表示已通过。参数见 [人机协同](references/human-in-loop.md)。

默认所有任务共用浏览器和持久化 profile，通过页签隔离；每个任务只有一个所有者。切换浏览器或 profile 可能需要重新登录，不关闭其他任务排障。

完成后 `close` 解除任务控制，最后一个任务的浏览器和页面保留给用户。不要例行追加 `--close-browser`、`shutdown` 或停止服务；等待人工协助时保留任务。截图、日志与上传材料不会自动清理，按本次范围处理敏感产物。

客户端将请求发送至 `POST /playwright/command`，信封如 `{"id":1001,"method":"get_browser_state","params":{}}`。页面文本与显式截图存于 `data/<id>/`，调用留档见客户端分册。

## 按需分册

| 分册 | 内容 |
| --- | --- |
| [维护与交接](references/reliable-execution.md) | 技能定向同步、执行环境故障和交接模板 |
| [桌面接入与验收](references/desktop-testing.md) | 外部 CDP、环境前置断言、点击路径、离线报告 |
| [命令可靠性](references/command-reliability.md) | diagnose/cancel、超时边界、expect/readback、语义定位、文件选择器、凭据留档 |
| [客户端](references/client.md) | 完整 CLI、输出筛选、留档、后端管理 |
| [读页面](references/reading-pages.md) | 状态字段、索引、表格、跨域 iframe |
| [命令表](references/commands.md) | 方法参数、返回字段、站点配方与 OCR |
| [协议](references/protocol.md) | 请求响应、诊断字段、网络证据与日志 |
| [批量与 JS](references/batch-and-js.md) | commands、expect、脚本文件与变量 |
| [人机协同](references/human-in-loop.md) | 人工请求、二维码、OCR、多步交接 |
| [浏览器](references/browsers.md) | 引擎、profile、生命周期与进程排障 |
| [ZeroOmega 安装与配置](../proxy-switchyomega-setup/SKILL.md) | 下载校验、扩展加载、配置导入和自动切换 |
| [故障索引](references/pitfalls.md) | 按症状查限制与处理方法 |
| [测试与证据](references/testing-evidence.md) | 前置条件、业务断言、截图完整性 |
| [商户申请](references/payment-onboarding.md) | 多层验证、密钥上传与资料脱敏 |

## 方法参数

用 `dsb list_methods` 找方法名；必填、类型和组合要求见 [自动生成的参数表](references/parameters.md)，语义和返回值见 [命令分册](references/commands.md)。运行时精确查询：

```powershell
dsb run list_methods -p details=true -p filter=wait_for_function --json
```
