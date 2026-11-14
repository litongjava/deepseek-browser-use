---
name: deepseek-browser-use
description: 只要需要使用浏览器就使用本技能和 dsb CLI：打开或加载浏览器、浏览网页、读取页面、点击填表、截图、网页测试、CDP 桌面 WebView2 测试，以及 12306、企业微信、B 站/视频号投稿、Cloudflare、GitHub 等站点任务。复用宿主浏览器绑定；环境缺失时按参考文档自行安装配置。
---

# DeepSeek Browser Use

用 `dsb` 命令操作浏览器。开源地址：https://github.com/litongjava/deepseek-browser-use

## 基本规则

- 凡是用浏览器都用 dsb，不改用内置浏览器工具、浏览器 MCP 或自写 Playwright/Selenium 脚本。dsb 出错先查 `--help`、`dsb diagnose`、`dsb server logs`，明确报告能力缺口，不静默换工具。
- `dsb` 不存在或 `dsb health` 失败：读 [环境配置](references/setup.md) 自行安装、构建并启动后端。
- 宿主已绑定浏览器任务时直接 `dsb …`，不重复传 `--id`/`--session`，不另建任务。独立终端用 `--id <数字>` 或 `DSB_TASK_ID`；`--session` 只是留档名，不建立绑定。
- 一个浏览器任务只有一个所有者，读、写、截图都串行。交接时给出任务 ID、目标 URL、已确认的业务事实、证据路径和在途 commandId/jobId；对方明确停手后再接管。
- 不确定方法或参数先查，不要猜：`dsb list_methods <关键词>`、`dsb run --help`、`dsb run list_methods -p details=true -p filter=<方法> --json`。

## 核心循环

```bash
dsb start                                   # 默认有头；--headless 无头；--display :1 显示到 VNC
dsb run go_to_url -p 'url=https://example.com' --summary
dsb state --text-only                       # 页签 + 带 [index] 的页面文本
dsb run click_element_by_index -p index=12 --summary
dsb run input_text -p index=5 -p 'text=hello' --summary
dsb run wait_for_text -p 'text=提交成功' -p timeout=10000
dsb js '@query.js' --select data.result --raw
dsb close                                   # 解除任务控制，浏览器和页面保留给用户
```

1. **观察**：导航或点击后重新 `dsb state`。页签块 `current tab is: N` 为 1 基，`switch_tab` 的 `pageIndex` 为 0 基。`indicesUsable:false` 或 `snapshotConsistent:false` 时不要用该次索引。
2. **执行**：优先用限定范围的 role/name 或唯一选择器；用索引时带当前 snapshotVersion。多个同名元素报 `AMBIGUOUS_LOCATOR` 时读 candidates 缩小范围。
3. **等待**：用有界的 `wait_for_text`、`wait_for_function`、`wait_for_response`、`wait_for_stable`；`contentReady:false` 表示主内容仍空，不算加载完成。
4. **核验**：表单用 `get_form_state`；最终回读业务后置条件（订单状态、稿件列表、申请记录）。`ok`、`changed`、`effective`、DOM diff 都不能单独证明业务成功。

`actionStatus:unknown`、`probeTrustworthy:false`、`observationComplete:false` 或超时时，先观察业务结果，绝不自动补点。点击、提交、支付、发消息不能自动重发。

## 输出与文件

- 常规动作加 `--summary`；只取需要的字段：`--select data.text --raw`，多字段用可重复的 `--fields 别名=路径`。大结果 `--out result.json` 存盘后再解析，不要整页反复打印。
- 多行 JS 和嵌套 JSON 放文件：`dsb js '@query.js'`、`--params '@params.json'`（PowerShell 必须加引号，读文件加 `-Encoding UTF8`）。
- 退出码：0 成功、1 传输错误、2 业务失败、3 用法错误。筛选失败不等于动作没执行，用 `dsb last` 读已记录的响应，不要为了补输出重发动作。
- 页面文本与显式截图存于服务端 `data/<id>/`；截图位置读 `data.path`。默认不截图，只有二维码、验证码、图表或视觉核对才截目标元素；取数优先 DOM/接口文本。
- 上传路径属于服务端机器，跨机器先 `dsb upload`。含凭据的命令加 `--sensitive`，完整配置、凭据、token 不得进入对话或提交。

## 常见情况

| 情况 | 做法 |
| --- | --- |
| 元素在视口外没有索引 | 用选择器，或 `state --viewport-expansion -1` |
| 内容在 iframe 里 | `list_frames` → `state --include-frames`；选择器和 JS 传 `frame` |
| 找一段文本 / 读表格 | `find_text`；表格用 `list_tables` + `extract_markdown` |
| 遮挡或弹窗 | `get_modals` 看 DOM 弹窗，`get_dialog` 看原生弹窗 |
| 需要接口数据 | `wait_for_response` 等待，`get_response_body` 回看 |
| 点击应开新页签 | 加 `--expect-popup`；重试前先 `get_tabs` 或 `wait_for_new_tab` |
| 编辑器输入框（Ace、CodeMirror） | `keyboard_type -p selector=... -p text=...` |
| 页面空白或报错 | `get_page_diagnostics` |
| 命令卡住 | `dsb diagnose`，按 commandId 取消 |
| 多步计划 | `dsb batch steps.json --async --wait --out result.json`，每步可带 `expect` |
| 页面停在 Cloudflare 验证（`BOT_CHALLENGE`） | 换任务 ID 用 `start --display :1` 开有头浏览器，请用户通过，不循环刷新 |
| 需要登录、扫码、验证码、人工支付 | 保留有头浏览器，`request_human_input` 并在对话里告诉用户；完成后回读再继续 |
| `BROWSER_CONFIG_CONFLICT` / `DISPLAY_UNAVAILABLE` | 什么都没启动，按回执 `hint` 处理，不要关闭别人的浏览器 |

## 收尾

完成后 `dsb close`。不要例行 `--close-browser`、`shutdown` 或停止服务；等待人工时保留任务。敏感账号用 `start --ephemeral`（临时 profile，关闭即删）；否则登录状态会留在 profile，收尾时告诉用户。

底层协议：客户端发送 `POST /playwright/command`，信封为 `{"id":1001,"method":"get_browser_state","params":{}}`。

## 按需读取

先判断任务需要什么，只读对应文件。

**通用参考**（`references/`）

| 文件 | 何时读 |
| --- | --- |
| [setup.md](references/setup.md) | 安装 dsb、构建启动后端、更新、Linux 无显示器、远端实例 |
| [client.md](references/client.md) | CLI 全部子命令、输出筛选、留档、后端管理 |
| [parameters.md](references/parameters.md) | 全部方法的参数、类型与必填项（自动生成） |
| [commands.md](references/commands.md) | 方法语义、返回字段、站点配方、OCR |
| [reading-pages.md](references/reading-pages.md) | 状态字段、索引失效、表格、跨域 iframe |
| [command-reliability.md](references/command-reliability.md) | 超时与取消、expect/readback、语义定位、文件选择器、错误码 |
| [batch-and-js.md](references/batch-and-js.md) | 批量计划、`execute_js`、脚本文件与变量 |
| [human-in-loop.md](references/human-in-loop.md) | 人工协助请求、二维码、多步交接 |
| [browsers.md](references/browsers.md) | 浏览器引擎、profile、VNC 显示、生命周期 |
| [protocol.md](references/protocol.md) | HTTP 请求响应、诊断字段、网络证据 |
| [testing-evidence.md](references/testing-evidence.md) | 网页测试的断言与截图证据 |
| [desktop-testing.md](references/desktop-testing.md) | 通过 CDP 接入 WebView2/Tauri 桌面应用 |
| [web-data.md](references/web-data.md) | 价格、数量、时间、表格等取数任务（必读） |
| [pitfalls.md](references/pitfalls.md) | 按症状查已知坑 |

**站点案例**（`references/cases/`，做对应站点前读）

| 案例 | 内容 |
| --- | --- |
| [railway-12306-ticket](references/cases/railway-12306-ticket.md) | 12306 查车次、票价、余票，授权后购票 |
| [cn-gov-site-search](references/cases/cn-gov-site-search.md) | 政府网站检索公告与附件 |
| [github-issue-triage](references/cases/github-issue-triage.md) | GitHub issue 回复、贴图、关闭 |
| [cloudflare-domain-redirect](references/cases/cloudflare-domain-redirect.md) | Cloudflare 域名跳转规则 |
| [aliyun-lightweight-server](references/cases/aliyun-lightweight-server.md) | 阿里云轻量服务器选购至收银台 |
| [deepseek-platform-topup-invoice](references/cases/deepseek-platform-topup-invoice.md) | DeepSeek 开放平台充值与发票 |
| [bilibili-video-upload](references/cases/bilibili-video-upload.md) | B 站视频投稿 |
| [wechat-channels-video-upload](references/cases/wechat-channels-video-upload.md) | 微信视频号投稿 |
| [youtube-video-download](references/cases/youtube-video-download.md) | YouTube 视频下载（yt-dlp） |
| [douyin-research-and-outreach](references/cases/douyin-research-and-outreach.md) | 抖音评论调研与授权私信 |
| [kuaishou-research](references/cases/kuaishou-research.md) | 快手评论采集 |
| [weibo-demand-research](references/cases/weibo-demand-research.md) | 微博评论需求研究 |
| [tradingview-moomoo-link](references/cases/tradingview-moomoo-link.md) | TradingView 连接 moomoo 账户 |
| [cnipa-trademark-register](references/cases/cnipa-trademark-register.md) | 中国商标网文字商标申请 |
| [wecom-register-certify](references/cases/wecom-register-certify.md) | 企业微信注册与认证 |
| [wecom-profile-complete](references/cases/wecom-profile-complete.md) | 企业微信企业信息完善 |
| [wecom-mail-domain](references/cases/wecom-mail-domain.md) | 企业微信邮箱绑定自有域名 |
| [wecom-invoice-contract](references/cases/wecom-invoice-contract.md) | 企业微信发票、合同、电子签章 |
| [payment-onboarding](references/cases/payment-onboarding.md) | 商户申请与多层验证表单 |
| [proxy-switchyomega-setup](references/cases/proxy-switchyomega-setup.md) | 在 Chrome 安装 ZeroOmega 代理扩展 |
