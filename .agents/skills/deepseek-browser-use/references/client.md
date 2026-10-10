# 客户端 dsb（首选）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

> **`dsb` 是装在 `PATH` 上的一个 Go 二进制，在任何目录直接敲就行**（源码在仓库 `dsb/`，用法见 `dsb/README.md`）。
> 不需要写 `python`、不需要 `.\client\dsb.cmd`、也不需要把 cwd 切到仓库根。
> **不要手写 `curl`/`Invoke-WebRequest` 往 `POST /playwright/command` 拼 JSON**。

## 也可以不手拼 JSON：用现成客户端（**首选**）

阅读页面首选 `dsb state --text-only`，只输出结构化文本，不重复列出元素清单。
视口外内容用 `--viewport-expansion 1500`（`-1` 表示全部），跨域 frame 用 `--include-frames`。
需要字段筛选时用 `dsb run get_form_state --select data.fields` 或
`dsb run get_browser_state --select data.text`。`--select` 输出 JSON，支持点分隔对象键和数字数组下标
（如 `data.fields.0`），不执行表达式；缺少路径报用法错误，显式 null 保留为 null。
它只筛终端输出，日志仍记录完整响应。
**失败响应同样按路径投影**（业务退出码不变）：批量回执里只要有一步失败、整批 `ok` 就是 `false`，
但 `data.results[N]` 仍在，所以 `--select data.results.N.…` 恰好是「只看失败那一步」的正确用法。
**路径不存在时：stdout 打 `null`、退出码 `3`（用法错）。** 以前是「退回打印整封 + 退出码仍是 0」——
按路径取值的调用方会拿到形状完全不同的东西，却看不到任何失败信号，对脚本和智能体是最坏的一种失败。
要旧行为请加 `--select-lenient`（退回整封、退出码不变），它会在 stderr 说明是退回去了。
`--text-only` 是纯文本便利选项，与 `--json`/`--select` 同用时后两者优先。
快照不可靠或动作结果未知时，筛选模式仍在 stderr 提示，不能靠选出的一个字段判断业务完成。

### 输出失败与动作执行分开处理

`dsb methods` 是客户端子命令，`dsb run list_methods` 调用协议方法。任务 ID 必须为数字。`--select` 是单路径，不是逗号分隔的多字段查询。需要多个字段时保存完整 JSON 再在读取程序中投影。

字段不存在只能在收到回执后判断，因此退出码 3 不等于动作没有执行。优先读取当前会话留档、`dsb last` 或新页面状态，不要重新点击、提交或导航来修复显示问题。`--out` 会保存完整响应且优先于 `--select`；不要用该组合测试路径是否存在。

PowerShell 的 `> 文件` 与客户端 `--out` 不是一回事：Windows PowerShell 的文本重定向可能生成 UTF-16 文件，`2>&1` 还会混入错误输出。若宿主 read 工具将文件判为二进制，不要绕过它读取；优先读取客户端自身保存的 UTF-8 留档。必要时转换已有文件编码，不能为了重新取得文本而重放写操作。

### 回执太大：`--out` 落盘、`--grep` 只看命中行

`run`、`js`、`start`、`close`、`state`、`health`、`methods`、`config`、`tasks` 统一提供两个输出开关（只影响**怎么打印**，不改变发出去的请求，也不会多一次往返）：

```bash
# 完整 JSON 写进文件，stdout 只留一行「已写入 …（N 字符，M 行）」
dsb --id 1001 run get_browser_state --out state.json

# 只打匹配该正则的行，末尾附一行「共 N 行，命中 M 行」
dsb --id 1001 run get_browser_state --grep mediaCount
```

- 读页面时最常用：一条回执动辄几万字符，而你只想确认某一行的存在。**服务端侧的等价能力是 `find_text`**
  （它连文本都不用传回来），这里两个开关是给"回执已经拿到、只想看其中几行"准备的。
- 两者可以同时用：先落盘，再从同一份文本里打命中行。
- **落盘文件的回执里中文是乱码（`ä¾èµ–` 这种）？先怀疑你的读法，不是文件坏了。** `dsb` 一律以 UTF-8
  写盘，服务端也按 UTF-8 回；但 **Windows PowerShell 5.1 的 `Get-Content` 默认按 ANSI 解码**，
  含中文的 UTF-8 回执就会被显示成 Latin-1 乱码 —— 而 `read` 工具或 `Get-Content -Encoding utf8`
  读同一个文件是完全正常的。先看版本（`$PSVersionTable.PSVersion`：`5.1.x` 就是老的 Windows PowerShell，
  `7.x` 才是 pwsh，7 默认 UTF-8 没有这个问题），是 5.1 就一律加 `-Encoding utf8`：

  ```powershell
  $PSVersionTable.PSVersion                                  # 5.1.x → 下面三条都必须带 -Encoding
  dsb --id 1001 run execute_js --params '@p.json' --out state.json
  Get-Content state.json -Encoding utf8 -TotalCount 20        # ✗ 不带 -Encoding 会看到乱码
  Select-String -Path state.json -Pattern '中央仓库' -Encoding utf8
  ```

  **别据乱码去报「服务端/客户端编码 bug」**：先读同一份文件的留档（`logs/agent/<会话>/*.res.json`，
  UTF-8），或者用 harness 的 read 工具再判断一次。

复杂请求继续使用 `--params @文件.json` / `batch 文件.json`，不为过滤输出改用裸 HTTP 请求，
否则会丢失统一退出码和调用留档。

**这两个参数文件都认「整个请求体」**：`--params @文件.json` 里的
`{"id":1001,"method":"request_human_input","params":{…}}` 会自动只取 `params` 那一层（按 `method`
字段识别），`batch` 认纯数组 / `{"commands":[…]}` / 整个请求体三种写法。留档文件、文档示例、
别人贴过来的裸 HTTP 请求体都能原样存下来直接喂进去 —— 以前这么写会把 `id`/`method` 当命令参数发下去，
真正的参数一个都没传，报回来的是 `缺少参数 prompt` 这种指不到原因的话。

```shell
dsb --port 10049 --id 1001 js "@脚本.js" --retry-on-spurious   # 只读脚本:伪故障自动重发
```

`--retry-on-spurious` 对应服务端的 `retryOnSpurious`（见 `references/pitfalls.md` 第 47 条）。
**只给只读脚本加**：会点按钮、提交表单的脚本重发等于再执行一次。

手工拼 `-d '...'` 在参数带中文、引号、换行时很容易出错（PowerShell 尤其爱吃掉引号），返回体还得自己解析。
`dsb` 把这几件事都替你办了：**子命令式传参**、**批量与异步**、**每一步的请求与响应都留档**、
**服务没起时自动拉起后端**。凡是「发请求 → 读页面 → 再发请求」的任务，用它比手拼 JSON 少一大类无谓的失败。

两个客户端（见 [protocol.md](protocol.md) 的说明）：

| 客户端 | 运行环境 | 适合 | 例子 |
| --- | --- | --- | --- |
| `dsb`（Go 二进制，装在 `PATH`） | 任意平台 | **默认入口**：写进脚本/CI、批量、异步；服务没起会自动拉起 | `dsb --port 10049 health` |
| `browse.ps1` | Windows PowerShell | 已有的 PowerShell 排查习惯 | `browse.ps1 -PayloadFile req.json -Session t1` |

安装（若 `dsb --version` 报「不认识这个命令」）：

```bash
cd dsb && go build -o "$(go env GOPATH)/bin/dsb" .    # Windows 加 .exe
```

### 服务没起会自己拉起来

普通命令连不上**本机**服务时，`dsb` 会按配置里的仓库位置找到后端 jar、把服务拉起来，再重试一次 —— 所以
「先把服务起起来」这一步可以不做。仓库位置记在 `~/.dsb/config.json`（`dsb server init --repo-dir <仓库根>`
写一次即可；`DSB_REPO_DIR` 环境变量优先）。显式管理：

```shell
dsb server start          # 起服务(已在跑就复用)
dsb server build          # 构建当前代码的后端 jar 并归档进 releases/<commit>/(想用最新代码时先跑这个)
dsb server status         # 服务地址、pid、仓库、可用 jar、日志
dsb server logs --lines 80  # 看日志尾部(排查启动失败最常用)
dsb server stop           # 停服务(先让服务关掉浏览器,再按进程结束)
dsb server restart        # 重启(用上新构建的 jar 要这一步)
```

**这五个子命令与其它命令共用同一套地址解析**(优先级见下)。以前它们只看命令行字面上的 `--port`,
于是在「配置里写了 10050」或用了 `--use <名字>` 时:start 起 10049、stop 停 10049、logs 读 10049,
而 `tasks`/`state` 连的是 10050 —— **两个后端、两套任务表,不需要任何竞态就能同时存在**。
现在 `--use`、环境变量、配置文件里的端口对所有子命令都生效。

`dsb server stop` 动手前会核对身份:pid 文件里记的端口与本次要停的端口不一致,或端口上应答的 pid
与文件里不一致(说明这个 pid 很可能已被系统回收),都会**拒绝动手**并说清原因 —— 而不是照着一个
数字去强杀一整棵进程树(Windows 上是 `taskkill /T /F`)。

关掉自动拉起:`dsb --no-auto-start health` 或 `DSB_AUTO_START=0`。

**只有「确定请求还没发出去」的失败才会自动拉起并重试**:连接被拒绝、主机/网络不可达、DNS 解析失败、
dial 阶段超时 —— 这些是「连都没连上」,服务端不可能收到过,重发是安全的。

而**连接被重置、读响应中断、整个请求超时一律不重发**:它们可能发生在命令**已经执行完**之后,
重发等于把 click / submit / execute_js 再做一遍(协议里没有幂等键)。这种情况的报错里会写明
「可能发生在请求已经送达之后,不自动重试」—— 看到这句别以为自动拉起坏了,它是有意不重发的。

就绪判定也收紧了:起完服务后,端口上应答的必须**就是刚拉起的那个进程**(比对健康接口自报的 pid)。
端口上如果坐着别的实例,会当场报错让你先停掉其中一个,而不是对着一个空实例发命令
(那样的表现是「任务莫名其妙消失」)。

### 服务在别的机器上，或者本机开了好几个

后端可能在别的机器(团队共用一台跑浏览器),本机也可能同时跑好几个实例(不同端口 = 不同 profile/登录态)。
两种都用同一组开关:

```shell
dsb --host 10.0.0.5 --port 10049 health        # 直接给主机与端口
dsb --port 10050 start --headful               # 本机第二个实例

dsb server target add lab --host 10.0.0.5 --port 10049   # 给主机+端口起个名字
dsb server target list                                   # 列出已登记的目标
dsb --use lab methods                                    # 之后就不用敲 IP 了
```

地址优先级:`--base-url` > `--use` > `--host/--port` > 环境变量 > 配置文件 > 默认 `localhost:10049`。
`dsb server status` 会把最终地址与它的来源一并打出来(`来源:具名目标 lab` / `命令行 --port` …),避免连错机器而不自知。

**远端目标不自动拉起**:那台机器上的服务得由它自己起(客户端没有理由去启动别人的进程;硬起只会把
「地址写错了」变成「在本机起了个没用的服务,然后照样失败」)。远端连不上时得到的是明确提示,不是傻等。

`dsb` 的要点（完整用法与退出码见 `dsb/README.md`）：

```shell
# 通用选项放子命令前后都行；退出码 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错
dsb --port 10049 health
dsb --port 10049 --id 1001 start --browser chrome --headful
dsb --port 10049 --id 1001 run go_to_url -p url=https://example.com
dsb --port 10049 --id 1001 state --full          # 标题/URL/元素/结构化文本
dsb --port 10049 --id 1001 js @脚本.js --var who=dsb   # 支持 {{变量}} 注入
dsb --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
dsb --port 10049 --id 1001 recipes --run close-all-modals
dsb --port 10049 upload 图样.jpg                 # 送文件到服务端暂存区
dsb --port 10049 last                            # 重放最近一次响应
```

用它还有两个直接好处：**`steps.log` 一行一次调用**（时间、序号、任务 ID、方法、成败、耗时、摘要），第几步开始
不对一眼就能看出来；**退出码把「服务没起」与「业务失败」分开**（`1` 与 `2`），写脚本时不用去解析 `msg` 猜。

### 几个容易用错的地方

- **`--summary`（`--compact` 是同一个开关）只管本地输出**，与服务端协议里的 `responseMode:"compact"`
  （响应精简模式）不是一回事；后者要用 `--response-mode compact` 传（**信封级字段**，不是 `params` 里的）。
  摘要为空时（`get_tabs`/`get_console_logs`/`get_dialog` 这类没有可摘要字段的方法）dsb 会自动退回打印一行
  JSON —— 静默只回一句 `get_tabs OK 21ms` 等于把答案吞了。
- **不做脱敏**：留档与终端输出都是原文（手机号、证件号、邮箱、长号码一律原样），`requestId`/`jobId`
  这些下一步要回填的凭据自然也在。交付或共享 `logs/agent/**` 前自己过一眼。
- **多行脚本不要写在命令行里**：经 cmd/PowerShell 传参会只剩第一行。用 `js @脚本.js`、`--params @文件.json`
  或 `batch cmds.json`。
- **每个子命令都有自己的帮助**:`dsb state --help`、`dsb batch --help`、`dsb server --help` 打的是
  **那个子命令**的用法与选项,只有 `dsb --help`(或 `dsb -h`)才是全局页。以前所有 `--help` 都只打全局,
  子命令的选项得去翻文档才知道。
- **`js` / `batch` / `state` 这些是子命令，不是 `run` 的方法名**。写成 `dsb run js @脚本.js` 只会得到一句
  `用法错:unrecognized arguments: @脚本.js`（真正的错在「`js` 不该跟在 `run` 后面」）。
  客户端会补一句对症提示，但正确写法是：

  ```shell
  dsb --port 10049 --id 1001 js @脚本.js        # 对：子命令直接写
  dsb --port 10049 --id 1001 run get_title      # 对：run 只用于服务端方法
  ```

- **PowerShell 会吃掉方括号与逗号**（它自己的一套参数解析），实测两种翻车：
  `-p selector=div[role=button]` → `unrecognized arguments: div[role=button]`；
  `-Dtest=A,B` → `Missing argument in parameter list`（逗号是 PowerShell 的数组运算符）。
  **凡是值里带 `[`、`]`、`,`、`"` 的参数，一律写进 `--params @文件.json`**，别在命令行里跟 shell 打架。

不确定服务端现在是什么状态（引擎、profile 目录、命令数、配方数）时，先跑一次自检：

```shell
dsb --port 10049 selftest --browser chrome
```


### 取证告警与输出兼容

``state --text-only``的stdout仍是纯页面文本；截图失败/熔断、观测不完整、索引不可用的提示输出到stderr。``state --select data.text``保留同样告警且stdout仍是合法JSON。``start --select data.browser.engine``不会再混入profile说明行。

```powershell
dsb start --browser chrome --headful --out start.json
dsb health --out health.json
dsb state --out state.json
```

``--out``保存完整响应，不受``--select``投影影响；业务失败退出码仍为2。截图降级与业务断言分开登记，详见 [testing-evidence.md](testing-evidence.md)。