# 命令诊断、业务断言与稳定定位

## 超时和取消

| 参数 | 单位和范围 | 作用 |
| --- | --- | --- |
| 等待方法的 `timeoutMs` | 毫秒，大于 0、不超过 900000 | `wait_for_*` 的目标条件等待时间 |
| 等待方法的 `timeoutSeconds` | 秒，大于 0、不超过 900 | 兼容原有写法；同时传两种单位时必须相等 |
| 动作的 `timeoutMs` | 毫秒 | 该动作支持的 Playwright 等待时间，不包括所有附带观测 |
| `expect.timeoutMs` | 毫秒，0～120000 | 业务断言的轮询窗口；省略或 0 表示只检查一次 |
| `commandTimeoutMs` / `--command-timeout-ms` | 毫秒，1～900000 | 服务端工作线程排队、执行和附带观测的统一上限 |
| CLI `--timeout` | 秒 | 客户端 HTTP 等待上限，超时不会自动取消服务端工作 |

`commandTimeoutMs` 不含 HTTP 传输和已有任务互斥锁的获取时间。已由另一命令占用的任务会直接返回 `started:false`；兼容路径上的锁等待最多 5 秒。服务端默认上限由 `browser.command.timeoutMs`、`browser.command.hardTimeoutMs` 配置决定。CLI 的 HTTP 上限应留出额外余量。

```powershell
dsb --id 1001 run wait_for_text -p text=已保存 -p timeoutMs=1500 --command-timeout-ms 3000
dsb --id 1001 diagnose --out command.json
```

`diagnose` 对应 `get_diagnostics`，只读内存，不进入浏览器事件循环，因此可以在同任务长命令执行期间调用。返回 `data.active` 和 `data.last`；没有对应记录时字段可能省略。普通页面读取仍需串行。

从 `data.active.commandId` 取命令 ID，然后取消这一条命令：

```powershell
$diagnostic = Get-Content command.json -Raw -Encoding UTF8 | ConvertFrom-Json
dsb --id 1001 cancel $diagnostic.data.active.commandId
dsb --id 1001 diagnose
```

`cancel` 对应 `cancel_command`，参数为 `commandId`。任务 ID、commandId、异步批次的 jobId 是三种不同标识。错误或过期的 commandId 返回 `accepted:false`，不会取消后来启动的命令。

| 执行字段 | 判断方式 |
| --- | --- |
| `stage` | 最近阶段，如 queue、execution、dispatch、locating、expectation、readback、failure-observation |
| `dispatchStarted` | 已进入命令执行器；不证明点击、上传或业务提交已完成 |
| `actionStatus` | not-started / completed / unknown；unknown 时 `actionExecuted` 可能省略 |
| `observationCompleted` | 整条命令及所请求的观测成功返回；业务断言失败时为 false |
| `cancelRequested` | 已请求取消；与浏览器副作用是否发生无关 |
| `workerRunning` | 服务端仍有该命令的工作线程，任务继续保留给它 |
| `cancelled` | 取消已请求且工作线程已退出；不表示撤销网页中的操作 |
| `retrySafe` | 保守的执行器派发判断；仍应结合动作回执和业务状态决定下一步 |

显式取消返回 `COMMAND_CANCELLED`；服务端期限到达返回 `COMMAND_TIMEOUT`。取消会阻止尚未开始的派发及后续步骤，并请求中断工作线程。已交给浏览器的工作可能继续完成；等 `workerRunning:false` 后先观察结果，再决定后续操作，不能自动重复提交。

同步与异步批次都保留任务所有权。`cancel_job` 仍用于在异步批次步骤之间停止；取消当前在执行的命令用 `diagnose` + `cancel`。诊断只在内存中保留活动命令及最近 100 个任务的最后一条记录，重启后清空。

## 一个动作、一个业务断言、一次结果回读

普通 `run` 支持 `--expect`、`--readback`；协议中对应 `params.expect`、`params.readback`。动作只执行一次，断言只轮询只读表达式。断言支持 `equals`、`notEquals`、`min`、`max`、`contains`、`truthy`；指定一种匹配方式即可。

保存 `save.json`：

```json
{
  "role": "button",
  "name": "保存",
  "scope": "[role=dialog]",
  "exact": true,
  "timeoutMs": 2000,
  "commandTimeoutMs": 8000,
  "expect": {
    "js": "document.querySelector('#save-status').textContent",
    "equals": "已保存",
    "timeoutMs": 3000
  },
  "readback": "({status:document.querySelector('#save-status').textContent,id:document.querySelector('#record-id').textContent})"
}
```

```powershell
dsb --id 1001 run click_element_by_role --params '@save.json' --compact
# 也可把 expect 对象与只读 JS 分别放进文件
dsb --id 1001 run click_element_by_role -p role=button -p name=保存 -p 'scope=[role=dialog]' --expect '@expect.json' --readback '@readback.js'
```

只使用其中一种调用方式，不要把两行当作连续执行的步骤。

断言成功时 `businessSucceeded:true`，证据在 `expectResult.actual`；断言失败时 `ok:false`、`actionSucceeded:true`、`businessSucceeded:false`，CLI 退出码为 2。动作已完成，不要重发。`readback` 会在断言检查后执行，结果位于 `data.readback.result`；回读失败也返回失败，并保留 `actionSucceeded:true` 和 `readbackError`。没有请求断言的 `ok:true` 仍只代表工具调用成功。

`expect.js` 和 `readback` 均为调用者提供的 JS，必须自己保证只读；工具不会静态证明其没有副作用。当前普通断言与回读在任务主 frame 执行，定位动作的 `frame` 不会自动传给它们。

## 语义定位与快照版本

`click_element_by_role` 支持 `role`、`name`、`scope`（CSS）、`frame`、`exact`、`nth`（0 基）、`mode`、`timeoutMs`。`exact` 默认 false；省略 `scope` 时从 body 的后代元素定位。

多个匹配且未传 `nth` 时返回 `AMBIGUOUS_LOCATOR`，同时给出 `matched` 与最多 5 个 `candidates`，不会任选一个点击。先读候选、缩小弹窗或表单范围；确实需要按顺序选择时才用 `nth`。没有匹配时快速返回 `ELEMENT_NOT_FOUND`；异步出现的元素先用有界等待方法等待。

`get_browser_state` 的可用快照返回 `snapshotVersion`。版本号属于当前任务实例的生命周期，任务重新创建后必须重新取快照。按索引执行时带上该值：

```powershell
dsb --id 1001 state --out state.json
$snapshot = Get-Content state.json -Raw -Encoding UTF8 | ConvertFrom-Json
dsb --id 1001 run click_element_by_index -p index=12 -p "snapshotVersion=$($snapshot.data.snapshotVersion)"
```

示例索引必须替换为刚读取的目标索引。新版快照已生成或旧快照已失效时，返回 `STALE_SNAPSHOT`、`actionExecuted:false`，要求重新读页。版本检查与既有 DOM 校验配合使用；版本号本身不表示任意动态 DOM 变化都已被检测。

## 文件选择器与表单回读

对点击后才创建、甚至未挂载到 DOM 的 file input，使用 `upload_file` 的 `trigger` 捕获真实文件选择器事件：

```json
{
  "trigger": "#choose-file",
  "path": "material.pdf",
  "timeoutMs": 5000,
  "responseUrl": "/api/materials/upload",
  "expect": {
    "js": "document.querySelector('#upload-status').textContent",
    "equals": "上传成功",
    "timeoutMs": 5000
  }
}
```

```powershell
dsb --id 1001 run upload_file --params '@upload.json'
```

`trigger` 必须只匹配一个元素，可传 `frame`；`path` 是服务器文件路径，也支持上传暂存区相对名称。触发器模式使用 `path`，不与 `index`、`selector`、`contentBase64` 混用；它不提供选择器模式的大文件 CDP 兜底。

| 字段 | 实际证明的范围 |
| --- | --- |
| `fileSelected` | 浏览器文件选择器已接收文件 |
| `inputReadback` | file input 的 connected、count、names；节点销毁后可能只有 inputReadbackError |
| `responseObserved` | 捕获到 URL 包含 responseUrl 的响应；responseUrl 应尽量精确 |
| `response` | 匹配响应的 URL、HTTP status、ok |
| `uploadCompleted` | 匹配响应接收完成且 HTTP 成功时为 true；非成功状态为 false，未确认时为 unknown |
| `frameworkAccepted` | 默认 unknown；仍须通过业务断言核验组件或服务端业务状态 |

没有匹配响应不等于上传失败；此时保留 `fileSelected` 和观察错误，先回读业务结果。HTTP 200 也不保证应用业务成功，最终以 `expect` 或独立业务记录为准。

`get_form_state` 同时返回 `domValue`、`displayValue`、`selectedOptions`、`popupId`、`validationMessage`，并保留原有 value、invalid、error。自定义 combobox 会通过 `aria-controls` / `aria-owns` 查找表单外的弹层和 `aria-selected` 选项；没有这些关联的组件需要补充只读业务检查。password 类型字段值会遮蔽。

## stdin、简洁输出和凭据留档

`--params -`、`--expect -`、`--readback -`、`js -`、`batch -` 支持 UTF-8 stdin，容忍开头 BOM。一次 `run` 只能有一个 stdin 来源，其余放文件；Windows PowerShell 应设置管道编码：

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
@'
{"body":"({message:'中文',ready:true})"}
'@ | dsb --id 1001 run execute_js --params - --compact
```

compact 摘要保留结构化 `result`、`expectResult`、`readback` 和 `execution`，每个字段超过 600 字符会标记截断；需要完整数据时用 `--select`、`--out` 或读取本次留档。

自动 CLI 留档与启用脱敏的服务端 trace 会遮蔽结构化 password、Authorization、token、API key、secret、Cookie 等字段。输入命令的 text/value/key/keys 被遮蔽，相关响应正文也不自动留存；嵌套批次同样处理。`--sensitive` 对应 `params.sensitive:true`，用于含凭据的任意 JS 等操作，遮蔽该次请求参数和响应正文：

```powershell
dsb --id 1001 run execute_js --params '@private-query.json' --sensitive
```

实时 stdout 与显式 `--out` 导出保留原值；`--sensitive` 不关闭截图、页面文本产物或其他浏览器采集。任意文本中的秘密无法仅凭字段名识别，历史文件不会追溯处理。`--no-record` 仅关闭 CLI 留档；服务端 trace 由其配置独立控制。新日志中的命令 ID、状态和阶段仍可用于排查。
