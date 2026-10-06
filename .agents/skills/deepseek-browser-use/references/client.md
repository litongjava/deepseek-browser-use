# 客户端 dsb（首选）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。

> **Windows 上默认入口是 `.\client\dsb.cmd`（在仓库根目录执行），本文所有示例都按它写。**
> **不要写 `python client/dsb.py …`**：`dsb.cmd` 就是这个仓库的客户端入口，它负责找解释器、
> 把 `client/dsb.py` 连同参数交出去、并原样透传退出码；手写 `python dsb.py` 只是在包装层用不了时
> 的退路（例如 `--grep` 的正则带 `|` 被 cmd.exe 吃掉，见下文）。macOS/Linux 把前缀换成 `./client/dsb`。

## 也可以不手拼 JSON：用现成客户端（**首选**）

阅读页面首选 `dsb state --text-only`，只输出脱敏的结构化文本，不重复列出元素清单。
视口外内容用 `--viewport-expansion 1500`（`-1` 表示全部），跨域 frame 用 `--include-frames`。
需要字段筛选时用 `dsb run get_form_state --select data.fields` 或
`dsb run get_browser_state --select data.text`。`--select` 输出 JSON，支持点分隔对象键和数字数组下标
（如 `data.fields.0`），不执行表达式；缺少路径报用法错误，显式 null 保留为 null。
它只筛终端输出，日志仍记录完整脱敏响应。
**失败响应同样按路径投影**（业务退出码不变）：批量回执里只要有一步失败、整批 `ok` 就是 `false`，
但 `data.results[N]` 仍在，所以 `--select data.results.N.…` 恰好是「只看失败那一步」的正确用法。
只有路径确实不存在（例如单条命令没有 `data.results`）才退回打印整封，并在 stderr 说明是退回去了。
`--text-only` 是纯文本便利选项，与 `--json`/`--select` 同用时后两者优先。
快照不可靠或动作结果未知时，筛选模式仍在 stderr 提示，不能靠选出的一个字段判断业务完成。

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
- `--grep` 的正则**带 `|`（或其它 shell 元字符）时，走 `dsb.cmd` 会被 Windows 的批处理引号处理打断**
  （报成 `'xxx' is not recognized as an internal or external command`）。这时直接调 `python client/dsb.py …`
  绕开 `.cmd` 包装，或者把模式写简单一点（例如先 `--grep mediaCount`）。
- **落盘文件的回执里中文是乱码（`ä¾èµ–` 这种）？先怀疑你的读法，不是文件坏了。** `dsb` 一律以
  `encoding="utf-8"` 写盘，服务端也按 UTF-8 回；但 **Windows PowerShell 5.1 的 `Get-Content` 默认按 ANSI
  解码**，含中文的 UTF-8 回执就会被显示成 Latin-1 乱码 —— 而 `read` 工具或 `Get-Content -Encoding utf8`
  读同一个文件是完全正常的。先看版本（`$PSVersionTable.PSVersion`：`5.1.x` 就是老的 Windows PowerShell，
  `7.x` 才是 pwsh，7 默认 UTF-8 没有这个问题），是 5.1 就一律加 `-Encoding utf8`：

  ```powershell
  $PSVersionTable.PSVersion                                  # 5.1.x → 下面三条都必须带 -Encoding
  .\client\dsb.cmd --id 1001 run execute_js --params '@p.json' --out state.json
  Get-Content state.json -Encoding utf8 -TotalCount 20        # ✗ 不带 -Encoding 会看到乱码
  Select-String -Path state.json -Pattern '中央仓库' -Encoding utf8
  ```

  **别据乱码去报「服务端/客户端编码 bug」**：先读同一份文件的留档（`logs/agent/<会话>/*.res.json` 是
  `ensure_ascii=False` 的 UTF-8），或者干脆换成 harness 的 read 工具再判断一次。

复杂请求继续使用 `--params @文件.json` / `batch 文件.json`，不为过滤输出改用裸 HTTP 请求，
否则会丢失客户端脱敏、统一退出码和调用留档。

**这两个参数文件都认「整个请求体」**：`--params @文件.json` 里的
`{"id":1001,"method":"request_human_input","params":{…}}` 会自动只取 `params` 那一层（按 `method`
字段识别），`batch` 认纯数组 / `{"commands":[…]}` / 整个请求体三种写法。留档文件、文档示例、
别人贴过来的裸 HTTP 请求体都能原样存下来直接喂进去 —— 以前这么写会把 `id`/`method` 当命令参数发下去，
真正的参数一个都没传，报回来的是 `缺少参数 prompt` 这种指不到原因的话。

```shell
client\dsb.cmd --port 10049 --id 1001 js "@脚本.js" --retry-on-spurious   # 只读脚本:伪故障自动重发
```

`--retry-on-spurious` 对应服务端的 `retryOnSpurious`（见 `references/pitfalls.md` 第 47 条）。
**只给只读脚本加**：会点按钮、提交表单的脚本重发等于再执行一次。

手工拼 `-d '...'` 在参数带中文、引号、换行时很容易出错（PowerShell 尤其爱吃掉引号），返回体还得自己解析。
仓库里的 `dsb` 客户端把这几件事都替你办了：**子命令式传参**、**批量与异步**、**每一步的请求与响应都留档**。
凡是「发请求 → 读页面 → 再发请求」的任务，用它比手拼 JSON 少一大类无谓的失败。

四个客户端（都在仓库里，跟着仓库一起分发）：

| 客户端 | 位置 | 适合 | 例子 |
| --- | --- | --- | --- |
| `dsb.py`（Python 3，只用标准库，跨平台，也可当库 import） | 仓库根 `client/dsb.py` | 写进脚本、yaml/CI、批量、异步；**交互使用时的退路**（包装层吃参数时才直接调它） | `python client/dsb.py --port 10049 start --browser chrome` |
| `dsb`（macOS/Linux 薄包装，可执行，透传参数与退出码） | 仓库根 `client/dsb` | macOS/Linux 上少打一截前缀，直接敲就行 | `./client/dsb --port 10049 health` |
| `dsb.cmd`（Windows 薄包装，透传参数与退出码） | 仓库根 `client/dsb.cmd` | **Windows 上的默认入口**（在仓库根目录写 `.\client\dsb.cmd`） | `.\client\dsb.cmd --port 10049 health` |
| `browse.ps1` | `scripts/trace/browse.ps1` | 已有的 PowerShell 排查习惯 | `browse.ps1 -PayloadFile req.json -Session t1` |

**macOS / Linux 上用 `./client/dsb`，不必写 `python dsb.py`**：它只做两件事 —— 挑一个 Python 3（顺序 `DSB_PYTHON` > `python3` > `python`），再把同目录的 `dsb.py` 连同全部参数交出去；用 `exec` 交棒，退出码原样透传。它会解析符号链接，所以 `ln -s "$(pwd)/client/dsb" ~/.local/bin/dsb` 之后在任何目录直接敲 `dsb` 即可。Unix shell 不像 cmd/PowerShell 那样额外吃 `&`、方括号、逗号（只有自己没加引号时才会被 shell 解释）。包装自身找不到解释器或 `dsb.py` 时退出 `127`。

**Windows 上直接用它，不必写 `python dsb.py`**：`dsb.cmd` 只做两件事 —— 找 `python`（取不到就退回 `py`），
再把 `%~dp0dsb.py` 连同全部参数交出去，退出码原样 `exit /b` 透传。两点注意：

- 在 **PowerShell** 里当前目录不在 `PATH`，要写成 `.\client\dsb.cmd ...`；在 **cmd.exe** 里 `client\dsb.cmd ...` 就行。
- `dsb.cmd` 中间隔着一层 cmd.exe，参数里的 `&`、`^`、`%` 可能被提前吃掉（中文与引号不受影响，已验证）。
  遇到这种参数不要换回别的发送方式，而是**把参数从命令行挪进文件**：`--params @文件.json`、`batch cmds.json`、
  `js @脚本.js` —— 长脚本、带中文的 JSON、带引号的选择器都走这条路，连转义都不用想。
- **PowerShell 还会额外吃掉方括号与逗号**（它自己的一套参数解析），实测两种翻车：
  `-p selector=div[role=button]` → `unrecognized arguments: div[role=button]`；
  `-Dtest=A,B` → `Missing argument in parameter list`（逗号是 PowerShell 的数组运算符）。
  **凡是值里带 `[`、`]`、`,`、`"` 的参数，一律写进 `--params @文件.json`**，别在命令行里跟 shell 打架。
  CSS 属性选择器（`input[name=foo][value=bar]` 这种不带引号的写法）虽然能在命令行里活下来，
  但放进文件始终更省事。

`dsb` 的要点（完整用法与退出码见 `client/README.md`）：

```shell
# 通用选项放子命令前后都行；退出码 0 成功 / 1 传输错 / 2 业务失败 / 3 用法错
# Windows（本机首选）：.\client\dsb.cmd ...    macOS/Linux：把前缀换成 ./client/dsb
.\client\dsb.cmd --port 10049 health
.\client\dsb.cmd --port 10049 --id 1001 start --browser chrome --headful
.\client\dsb.cmd --port 10049 --id 1001 run go_to_url -p url=https://example.com
.\client\dsb.cmd --port 10049 --id 1001 state --full          # 标题/URL/元素/结构化文本
.\client\dsb.cmd --port 10049 --id 1001 js @脚本.js --var who=dsb   # 支持 {{变量}} 注入
.\client\dsb.cmd --port 10049 --id 1001 batch cmds.json --async --wait   # 长批次不受 HTTP 超时限制
.\client\dsb.cmd --port 10049 --id 1001 recipes --run close-all-modals
.\client\dsb.cmd --port 10049 upload 图样.jpg                 # 送文件到服务端暂存区
.\client\dsb.cmd --port 10049 last                            # 重放最近一次响应
```

用它还有两个直接好处：**`steps.log` 一行一次调用**（时间、序号、任务 ID、方法、成败、耗时、摘要），第几步开始
不对一眼就能看出来；**退出码把「服务没起」与「业务失败」分开**（`1` 与 `2`），写脚本时不用去解析 `msg` 猜。
只有包装层本身用不了时（cmd.exe 吃掉 `&`/`^`/`%`、`|` 等参数）才临时退回 `python client/dsb.py`，
其余参数与用法完全一致 —— 这是例外，不是默认写法。

三个容易用错的地方：

- **`--summary`（`--compact` 是同一个开关）只管本地输出**，与服务端协议里的 `responseMode:"compact"`
  （响应精简模式）不是一回事；后者要用 `--response-mode compact` 传（**信封级字段**，不是 `params` 里的）。
  摘要为空时（`get_tabs`/`get_console_logs`/`get_dialog` 这类没有可摘要字段的方法）dsb 会自动退回打印一行
  JSON —— 静默只回一句 `get_tabs OK 21ms` 等于把答案吞了。
- **默认脱敏不会掩掉「下一步还要回填的凭据」**：`requestId`、`jobId` 与 `hr-<n>-<雪花号>` 原样保留，
  其余（手机号、证件号、邮箱、长号码）照旧打码。理由很实际：把要回填的 ID 掩成 `***` 之后，
  `submit_human_input` / `get_response_body(requestId=…)` 就没法用了，比泄露它更糟。
- **多行脚本不要写在命令行里**：经 cmd/PowerShell 传参会只剩第一行。用 `js @脚本.js`、`--params @文件.json`
  或 `batch cmds.json`。
- **`js` / `batch` / `state` 这些是子命令，不是 `run` 的方法名**。写成 `dsb run js @脚本.js` 只会得到一句
  `用法错:unrecognized arguments: @脚本.js`（真正的错在「`js` 不该跟在 `run` 后面」）。
  客户端现在会补一句对症提示，但正确写法是：

  ```shell
  dsb --port 10049 --id 1001 js @脚本.js        # 对：子命令直接写
  dsb --port 10049 --id 1001 run get_title      # 对：run 只用于服务端方法
  ```

不确定服务端现在是什么状态（引擎、profile 目录、命令数、配方数）时，先跑一次自检：

```shell
.\client\dsb.cmd --port 10049 selftest --browser chrome    # macOS/Linux 换成 ./client/dsb
```


### 取证告警与输出兼容

``state --text-only``的stdout仍是纯页面文本；截图失败/熔断、观测不完整、索引不可用的提示输出到stderr。``state --select data.text``保留同样告警且stdout仍是合法JSON。``start --select data.browser.engine``不会再混入profile说明行。

```powershell
.\client\dsb.cmd start --browser chrome --headful --out start.json
.\client\dsb.cmd health --out health.json
.\client\dsb.cmd state --out state.json
```

``--out``保存完整脱敏响应，不受``--select``投影影响；业务失败退出码仍为2。截图降级与业务断言分开登记，详见 [testing-evidence.md](testing-evidence.md)。
