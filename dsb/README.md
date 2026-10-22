# dsb —— deepseek-browser-use 的 Go 客户端

一个静态二进制,既能当命令行用,也是这个仓库的**唯一**客户端入口。
装进 `PATH` 之后在任何目录直接敲 `dsb`,不用挑解释器、不用管工作目录。

```
dsb/
├── main.go        入口:解析命令行 → 拼客户端 → 派发子命令 → 退出码
├── args.go        命令行解析(argparse 语义的复刻)
├── endpoint.go    目标解析(多主机/多端口、具名目标)+ ~/.dsb/config.json 读写
├── client.go      HTTP 层、响应信封、留档
├── commands.go    health / run / batch / state / js … 各子命令
├── server.go      `dsb server` 子命令族(后端服务管理与目标登记)
├── backend.go     仓库定位、jar 候选与查找、构建、拉起/停止后端
├── process_windows.go / process_unix.go   脱离父进程启动、结束进程树
├── input.go       参数文件、标准输入、批量命令来源
├── jsonval.go     保序 JSON 对象与可控的编解码
├── output.go      输出策略(摘要/JSON/--out/--grep)
├── selftest.go    端到端自检
├── errors.go      用法错(3)与传输错(1)
├── dsb_test.go    纯本地自测(不连服务)
└── README.md      本文件
```

## 安装

```bash
cd dsb && go build -o "$(go env GOPATH)/bin/dsb.exe" .
```

装完在任意目录验证:

```bash
dsb --version
dsb --help
```

## 30 秒上手

宿主已绑定浏览器任务时直接使用 `dsb …`，不重复传 `--id`/`--session`。独立 CLI 从 `DSB_TASK_ID`/`DSB_SESSION` 接收上下文；`--session` 本身仅是留档名，不自动绑定任务。常规动作用 `--summary`：启动结果仅展示基本浏览器字段，兜底 JSON 预览限 1200 字符，错误消息限 400 字符，超出会提示截断。需要完整数据使用 `--json`、`--select` 或 `--out`；已执行动作通过 `last` 查记录，不重放。

```bash
dsb server start # 启动服务(连不上时会自动拉起,一般不用手敲)
dsb server status #查看状态:地址、健康、pid、全部可用 jar
dsb --port 10049 health                       # 服务在哪:命令行 > 具名目标 > 环境变量 > 配置文件 > 默认(localhost:10049)

dsb start --browser chrome                    # 默认显示浏览器窗口,看回执 engineHonored 确认引擎真的换了

dsb --id 1001 run go_to_url -p url=https://example.com   # 发一条命令

dsb --id 1001 state                           # 看页面状态(标题/URL/元素数)
dsb --id 1001 state --text-only --viewport-expansion -1  # 仅结构化文本

dsb --id 1001 run get_form_state --select data.fields    # 字段投影输出 JSON

dsb --id 1001 batch cmds.json --async --wait             # 长批次:后台跑 + 轮询,不受 HTTP 超时限制

dsb --id 1001 close                           # 关掉任务
```

**服务没起也不用管**:连不上时客户端会按配置文件自动把后端拉起来,再重试一次。

## 子命令

| 子命令 | 作用 |
| --- | --- |
| `health` / `config` / `tasks` / `methods [过滤词]` | 运维自省:健康检查、生效配置、活着的任务、命令清单 |
| `list_methods [过滤词]` | `methods` 的等价别名，支持相同的过滤与输出选项，例如 `dsb list_methods click --json` |
| `start [--browser chrome] [--headless]` | 起任务，默认显示窗口；`--headless` 显式无头；`--headful` 保留兼容，不能与 `--headless` 同用；`--browser` 支持 auto/chromium/chrome/edge/firefox |
| `close [--close-browser]` / `shutdown` | 默认结束任务，最后一个任务保留浏览器和页面；`--close-browser` 显式关闭（其他任务存在时拒绝）；`shutdown` 关闭所有任务与浏览器 |
| `run <method> [-p k=v] [--params @文件] [--wait-until STAGE]` | 发一条命令；导航等待可选 commit/domcontentloaded/load/networkidle，默认 domcontentloaded；带别名容错:`press_key` 会自动映射成 `send_keys`(`key` 与 `keys` 参数通用) |
| `batch [文件\|-] [--async] [--wait] [--keep-going] [--stop-on-expect-failure]` | 批量命令(数组 JSON,默认读标准输入) |
| `job <jobId> [--wait]` | 查/等异步批次 |
| `diagnose` / `cancel <commandId>` | 读取活动/最近命令诊断，或请求取消指定命令；不占用浏览器事件循环 |
| `recipes [--run 名字] [--var k=v]` | 列配方 / 跑配方(显式点名才跑) |
| `state [--max-elements N] [--full] [--text-only] [--highlight] [--screenshot]` | 默认不画高亮、不截图，索引仍可操作；两个开关分别显式启用。`--full` 连元素清单与结构化文本 |
| `js <脚本\|@脚本.js\|-> [--var k=v] [--retry-on-spurious]` | 执行 JS,支持 `{{变量}}` 注入 |
| `upload <文件> [--filename 名字]` / `uploads [--delete 名字]` | 文件送到服务端暂存区 / 列、删暂存文件 |
| `last` | 重放本会话最近一次的响应 |
| `server <动作>` | 管理后端服务与目标:init / build / start / stop / restart / status / logs / target(见下) |
| `selftest [--browser chrome]` | 对当前服务跑一遍端到端自检(30 项检查) |

通用选项(放在子命令**前面或后面都行**):`--base-url` / `--host` / `--port` / `--use` / `--id` / `--timeout` /
`--session` / `--no-record` / `--no-auto-start` / `--json` / `--compact`(`--summary`) /
`--response-mode` / `--diagnostics` / `--index`。长选项可前缀缩写(argparse 的习惯,`--retry-o` = `--retry-on-spurious`)。

### 三个容易用错的选项

普通 `run` 新增 `--expect JSON\|@文件\|-`、`--readback JS\|@文件\|-`、`--command-timeout-ms`、`--sensitive`。动作只执行一次，断言轮询具体业务条件；断言或回读失败不会重新执行动作。compact 输出包含有界的结构化结果。完整示例、各层超时和取消语义见 [命令可靠性](../.agents/skills/deepseek-browser-use/references/command-reliability.md)。

| 选项 | 它到底做什么 | 别混淆 |
| --- | --- | --- |
| `--summary`(`--compact` 是同一个开关) | **只影响本地输出**:一行摘要 + 不打 JSON。摘要为空的方法(`get_tabs`/`get_console_logs`/`get_dialog`…)会自动退回打印一行 JSON —— 静默只回一句 `get_tabs OK 21ms` 等于把答案吞了 | 它**不会**给服务端发任何精简请求 |
| `--response-mode compact` | 请求**信封**里的 `responseMode`（CLI 默认 compact；full 恢复完整响应） | 它是信封级字段,不是 `params` 里的;与上面的 `--summary` 无关 |
| `--select <路径>` | 本地按点分路径投影(如 `data.fields.0`),**失败响应同样投影** —— 批量里某一步失败时整批 `ok` 是 `false` 但 `data.results` 仍在,`--select data.results.N.…` 正是「只看失败那一步」 | 路径不存在时默认输出 `null`，成功响应转为退出码 3，业务失败仍为 2。加 `--select-lenient` 才退回整封并保留原退出码 |

`--params @文件.json` 与 `batch` 都认**整个请求体**:文件里写 `{"id":1001,"method":…,"params":{…}}` 时
按 `method` 字段识别、只取 `params` 那一层;`batch` 另认纯数组与 `{"commands":[…]}`。

### 脚本参数与输出筛选恢复

`js` 的脚本是位置参数，不支持 `--body`。较长脚本放到 UTF-8 文件中；带 BOM 的本地文件也可读取。

```
dsb js "() => document.title"
dsb js '@script.js'
dsb js '@folder with spaces/script.js'
```

PowerShell 中请给整个 `@文件` 参数加引号，包含 `@`。未引用时可能由 PowerShell 在启动 dsb 前报解析错误，CLI 无法捕获这个错误。
也可用 `dsb js -` 从标准输入读取脚本。底层方法调用仍支持 `dsb run execute_js -p "body=() => document.title"`。

- `--select data.url,data.title` 不是多字段语法，客户端会在发送请求前拒绝。选择共同父对象 `--select data` 即可。
- `--select-lenient` 保留含逗号的字面字段名及缺失字段回退行为，不会把逗号解释成多字段选择。
- 输出路径不存在发生在响应返回之后，原动作可能已经完成。**不要修改筛选参数后重新发送点击、提交等动作。**
- 已启用记录时，用相同的 `--session` 和 `--record-dir` 执行 `last`。它只读取本地响应，不重发请求。显式 `--id` 会按同编号请求文件的顶层任务 ID 筛选该目录中的最新响应；没有可确认归属的记录就报错，不回退其他任务。缺失或损坏的请求文件无法确定归属，不参与筛选；不要据此声称已覆盖所有历史操作。不传 `--id` 则保持读取目录最新响应的行为，`DSB_TASK_ID` 不隐式开启筛选。

```
dsb --session checkout --record-dir './logs/agent/checkout' --id 1001 last
dsb --session checkout --record-dir './logs/agent/checkout' --id 1001 last --select data
```

`--record-dir` 是最终目录，不再拼接 session；`DSB_RECORD_DIR` 才是默认目录计算的根目录。先不加 `--select` 查看记录响应，再选择已确认存在的路径。
原调用如果使用了 `--no-record`，就没有本次新记录；此时 `last` 可能读到旧响应，不能作为本次动作的证据。
`last` 的退出码 0 表示本地读取成功，不表示原动作成功，仍需检查记录中的 `ok`、`code` 和业务结果。

## 连哪台服务(多主机 / 多端口)

后端可以在**别的机器**上(团队共用一台跑浏览器),同一台机器上也可以**同时跑好几个实例**
(不同端口 = 不同 profile、不同登录态)。两种都用同一套开关。

```bash
dsb --host 10.0.0.5 --port 10049 health          # 直接给主机与端口
dsb --base-url http://10.0.0.5:10049 health      # 等价,写全了更直观(IPv6 用这个)
dsb --port 10050 start --headful                 # 本机第二个实例

dsb --use lab health                             # 用登记过的具名目标(见下)
```

地址的决定顺序(`--base-url` > `--use` > `--host/--port` > 环境变量 > 配置文件 > 默认 `localhost:10049`)。
`dsb server status` 会把最终用的是哪个地址、以及这个地址是从哪来的(`来源:命令行 --port` / `具名目标 lab` …)一并打出来,
免得「我以为连 A,其实连的是 B」。

### 具名目标:给主机+端口起个名字

```bash
dsb server target add lab --host 10.0.0.5 --port 10049 --note "实验机"
dsb server target add local2 --host 127.0.0.1 --port 10050
dsb server target list            # 列出全部(--json 给脚本用)
dsb server target remove lab
dsb --use lab methods             # 之后就不用敲 IP 了
```

**自动拉起只对本机目标生效。** 远端服务必须在那台机器上自己起 —— 客户端没有理由去启动别人的进程,
而且那样做只会把「地址写错了」变成「在本机起了个没用的服务,然后照样失败」。所以远端目标连不上时,
得到的是明确提示(`远端不能自动拉起`),不会傻等或误起本机服务。

## 后端服务管理(不用先起服务)

后端(`java -jar backend.jar`)与客户端是两件事,但「先起服务再发命令」这个顺序不该由使用者记着。

```bash
dsb server init --repo-dir D:\code\project\project-browser-use\deepseek-browser-use   # 记下仓库在哪(一次性)
dsb server build          # 构建当前代码的后端 jar,归档进 releases/<commit>/
dsb server start          # 起服务(已在跑就复用)
dsb server status         # 服务地址、健康与否、pid、仓库、全部可用 jar、日志
dsb server logs --lines 80  # 看日志尾部(排查启动失败最常用)
dsb server stop           # 停服务(先让服务关掉浏览器,再按进程结束)
dsb server restart        # 重启(想用上新构建的 jar 就得这一步)
```

> `server start` / `stop` / `status` 也认 `--port`,所以同一台机器上的多个实例可以逐个管理:
> `dsb --port 10050 server stop`。

### 仓库位置从哪来

按优先级:**`--repo-dir`** > **`DSB_REPO_DIR` 环境变量** > **`~/.dsb/config.json`** > **从当前目录向上探测**。
（仓库位置只对本机目标有意义 —— 远端主机上的 jar 在远端。）

`~/.dsb/config.json` 同时管「仓库在哪」与「默认连哪台主机」:

```json
{
  "repoDir": "D:\\code\\project\\project-browser-use\\deepseek-browser-use",
  "host": "127.0.0.1",
  "port": 10049,
  "targets": {
    "lab":    { "host": "10.0.0.5", "port": 10049, "note": "实验机" },
    "local2": { "host": "127.0.0.1", "port": 10050, "repoDir": "…" }
  }
}
```

为什么要有这个文件:`dsb` 装在 `PATH` 上(`~/.local/bin`、`D:\dev_gopath\bin`),离仓库十万八千里,
推不出仓库在哪。装到别的机器、换了仓库目录、多了一台跑浏览器的机器,都只改这一个文件。

### jar 从哪来

按优先级:**配置文件里的 `jar`** > **`<repo>/.dsb-backend/releases/<commit>/backend.jar`**(多个取最新的)
> **`<repo>/playwright-server/target/playwright-server-*.jar`** > **`<repo>/dist/*-windows-x64.jar`**。
一个都没有时报用法错,并提示跑 `dsb server build`。

**`dsb server status` 会把全部候选都列出来**(带来源、commit / playwright 版本、时间、大小),用 `★` 标出启动时会选的那份:

```
后端 jar(按优先级,★ = 启动时会用的那份):
★ …\.dsb-backend\releases\879906f4…\backend.jar(releases, 879906f4, 2026-10-07 12:32, 51.6 MB)
  …\.dsb-backend\releases\87a5e4d7…\backend.jar(releases, 87a5e4d7, 2026-10-06 10:52, 51.6 MB)
  …\playwright-server\target\playwright-server-1.0.0.jar(target, 2026-10-07 12:32, 51.6 MB)
  …\dist\deepseek-browser-use-1.0.0-windows-x64.jar(dist, 2026-09-25 20:01, 199.5 MB)
```

为什么 `releases` 排在 `target` 前面:前者是「按 commit 存档、可复现」的产物,后者是随便哪次本地构建留下的
(可能来自半路中断的构建)。**想用最新代码跑,先 `dsb server build`** —— 它会把当前 commit 的产物归进
`releases/<commit>/`,自然就成了最新的那份。`target` 与 `dist` 是兜底,平时不会用到。

### 构建

```bash
dsb server build
```

跑的是 `mvn -B -ntp -Pproduction -pl playwright-server -am clean package -DskipTests -Ddriver.platform=<当前平台>`,
把产物归档到 `.dsb-backend/releases/<当前 commit>/backend.jar`。`-Ddriver.platform` 不能省:
不传的话 shade 会把 driver-bundle 里 5 个平台的 node 全打进 jar(约 194MB 的浪费)。

### 自动拉起

默认**开**:普通命令(`health` / `run` / `state` …)连不上**本机**服务时,客户端会按上面的配置把后端拉起来,
然后重试一次。触发时会在 stderr 打一行说明。关掉它:

```bash
dsb --no-auto-start health     # 单次
DSB_AUTO_START=0 dsb health    # 环境变量
```

只在「本机 + 连不上」(连接被拒)时触发;**服务超时、业务失败都不触发** —— 超时说明服务在跑,只是慢。

## 退出码(写脚本时最该记住的一条)

| 码 | 含义 | 典型原因 |
| --- | --- | --- |
| 0 | 成功 | `ok:true` |
| 1 | 传输/协议错 | 服务没起、端口不对、超时、响应不是合法 JSON |
| 2 | 业务失败 | 服务端回了 `ok:false`(任务不存在、找不到元素、断言没过……) |
| 3 | 用法错 | 参数写错、`-p` 少了 `=`、id 不是数字、要上传的文件不存在 |

`1` 和 `2` 必须分开:前者要去看服务,后者要看自己的命令和页面。批量里**每一步**的失败原因在
`data.results[i].error`(`{code,message,retryable,retryAfterMs}`),按 `retryable` 决定要不要退避重试。

## 每次都留档

默认(会话名 `dsb`)每次调用都会往 `logs/agent/<会话>/` 落盘:

```
logs/agent/dsb/001.req.json    请求结构（Cookie 值定向遮蔽）
logs/agent/dsb/001.res.json    响应结构（Cookie 值定向遮蔽）
logs/agent/dsb/steps.log       一行一次调用:时间 #序号 id 方法 OK/FAIL 耗时 摘要
```

`steps.log` 长得像这样,排查「第几步开始不对」先看它:

```
2026-09-24 11:24:03 #001 id=1001 execute_js OK 54ms seq=8 shot=/data/1001/8.png
2026-09-24 11:24:03 #002 id=1001 commands FAIL 71ms count=2 succeeded=2 failed=0 expectFailed=1  | 0:get_title=ok 1:execute_js=expect-fail msg=第 1 条命令 execute_js 的断言没通过：实际值 2 不满足 equals 3
```

编号接着上一轮往下排,不会覆盖旧记录;`dsb last` 直接重放最近一份响应。

**凭据留档脱敏**：自动 `.req.json` / `.res.json` 和 steps.log 遮蔽结构化 Cookie、password、Authorization、token、API key、secret 等值；输入命令参数和相关响应正文也会遮蔽，包括嵌套批次。该操作不修改实际请求与响应；显式 `--out` 和实时 stdout 仍保留原值。`last` 读取的是脱敏记录，不能恢复凭据。

任意 JavaScript 或文本中的秘密无法仅凭字段名识别；含凭据的调用使用 `run --sensitive`，服务端 trace 在启用脱敏时也会遮蔽该次参数和响应。实时输出、显式导出、截图、页面文本产物及历史文件仍需按内容处理。`--no-record` 仅关闭客户端留档，不会关闭服务端日志。

### 回执太大:`--out` 落盘、`--grep` 只看命中行

```bash
dsb --id 1001 run get_browser_state --out state.json   # 完整 JSON 写文件,stdout 只留一行提示
dsb --id 1001 run get_browser_state --grep mediaCount  # 只打匹配的行,附「共 N 行,命中 M 行」
```

两者可同时用。它们只改**怎么打印**,不改发出去的请求,也不会多一次往返。

## 环境变量

`DSB_BASE_URL`、`DSB_HOST`、`DSB_PORT`、`DSB_TASK_ID`、`DSB_SESSION`、`DSB_RECORD_DIR`、
`DSB_REPO_DIR`、`DSB_AUTO_START`。

## 自检

```bash
dsb --port 10049 selftest --browser chrome
```

它用自己的任务 id(`990001`,不会撞上业务任务)起一个浏览器,依次验证:健康检查、命令清单里新命令是否都在、
`start` 的 `engineHonored`、打开本地自检页、`wait_for_count`、`get_modals`/`close_modal`、点击降级到真实鼠标、
`expect` 断言能报出不一致、异步批次与 `get_job`、任务清单/配置/配方可读、`cleanup` 默认只预演。
自检页面是本地生成的 HTML,**不依赖外网**。

## 本地自测

```bash
cd dsb && go test ./...
```

不连服务:参数解析、JSON 保序与编码、编号接续、输出策略、信封级字段、jar 查找优先级、仓库定位、自动拉起的触发条件。
任务结束默认保留最后的数据页。兼容配置的下一任务复用浏览器并新开页签；配置不兼容时提示先显式关闭，不自动重建。CLI 托管 profile 按服务端口隔离：10049 保留 `.dsb-backend/profile`，其他端口用 `.dsb-backend/profile-<port>`。

### 默认精简响应与截图文件

CLI 默认请求 `responseMode:compact`。正常成功回执省略常规执行、零重试和正常截图诊断；失败、状态未知和截图降级仍保留诊断。`--diagnostics` 跳过裁剪，`--response-mode full` 请求完整响应。`--json` 只决定终端格式；`--summary` 只决定本地摘要。

`--out` 保存的是当前模式下收到的 JSON，不是图片，也不会恢复被服务端省略的字段。自动留档和 `last` 同样不能恢复这些字段；不要重放写操作来补诊断，必要时使用只读 `diagnose` 查看最近命令。

```powershell
# 复用已启动且由自己拥有的任务 ID
dsb --id 1001 --session example run screenshot --out shot-response.json
if ($LASTEXITCODE -ne 0) {
  throw 'Read the response and diagnostics before continuing'
}
$result = Get-Content 'shot-response.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$result.data.path
```

`data.path` 是服务端文件路径；远程服务使用 `data.url`，相对 URL 需按服务地址解析。不要将 JSON 输出文件命名为 `.png`。仅需路径时可在原命令使用 `--select data.path`。PowerShell 启动时若 profile 被执行策略拦截，宿主应使用 `powershell.exe -NoProfile`，无需修改系统执行策略。

## 桌面测试与多字段输出

浏览器操作统一使用 dsb，包括加载浏览器、截图和 WebView2。`targets --endpoint URL` 枚举外部 CDP 页面；`attach --endpoint URL --target-id ID`（或 `--url URL`）固定目标，`close` 仅解除外部控制。

`run --precondition @environment.json` 在动作前复用 expect 断言；失败不派发动作。`--fields alias=path` 可重复，多字段缺失填 null，成功响应退出 3，业务失败保留退出 2。不能与 --select 混用。

`report --record-dir DIR --out report.json --markdown report.md` 只读取已有记录生成报告；命令成功不代表业务成功。完整步骤与支持边界见 [桌面测试技能](../.agents/skills/deepseek-browser-use/references/desktop-testing.md)。
