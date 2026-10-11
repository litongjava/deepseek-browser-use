# 可靠执行与任务协作

## 1. CLI 与 PowerShell：先核实，再执行

先读完整适用技能，不要只取摘要或固定字符数。命令参数不确定时用对应的 --help；协议方法名不等于 CLI 子命令。

以下命令在 PowerShell 中使用；1001 仅为示例，实际先分配未占用的数字任务 ID，并给不同执行者分配不同 --session 留档名。


```powershell
 dsb js --help
 dsb run --help
 dsb batch --help
 dsb --port 10049 methods
 dsb --port 10049 --id 1001 --session ticket-example start --browser chrome --headful
 dsb --port 10049 --id 1001 --session ticket-example run go_to_url -p 'url=https://example.com/?from=a&to=b'
 dsb --port 10049 --id 1001 --session ticket-example state --text-only
 dsb --port 10049 --id 1001 --session ticket-example js '() => ({title: document.title, url: location.href})' --out page.json
 dsb --port 10049 --id 1001 --session ticket-example js '@query.js' --out result.json
 dsb --port 10049 --id 1001 --session ticket-example run execute_js --params '@params.json' --out result.json
 dsb --port 10049 --id 1001 --session ticket-example batch commands.json --async --wait --wait-timeout 120
 ```

- js 的 SCRIPT 是位置参数，没有 --body；协议的 execute_js 才使用 params.body。
- 在 PowerShell 中给 @query.js、@params.json 加单引号，防止被当作 splatting。含 & 的 URL 整个参数加引号。嵌套 JSON/多行 JS 优先写文件；不要叠加 shell 转义。
- --select 一次只接受一个点分路径，例如 --select data.result；不能写 data.url,data.title。先看完整响应结构，再选择已有字段。需要多个字段时用 --out 保存，再按结构投影。
- JSON 解码每层一次。data.result 可能已经是对象，也可能是 JSON 字符串；根据类型判断，禁止全局替换反斜杠来“修复”JSON。
- 输出筛选失败不代表动作未执行。若仍是该次最新记录，用相同 --session 与 --record-dir 执行 dsb last --select data；已有后续调用时读取那次明确的 .res.json。不要为修输出而重做导航、点击或提交。
- 用 --out 分离 UTF-8 JSON 和诊断输出；不要从摘要中猜 JSON 起点，不用 Select-Object -First 截断正在输出的进程。

如果宿主报 spawn pwsh ENOENT：命令尚未启动，不是业务命令失败。先用文件工具确认工作目录及已知可执行文件位置，区分缺少 cwd 与缺少 shell；不要在不同参数下盲目重试，不改系统 PATH，不重启浏览器服务。只在找到具体原因后修正一次；不能执行时继续文件审阅并报告未跑测试。

## 2. 一个浏览器任务只有一个所有者

- 所有者独占该 --id 的导航、读取、截图、JS 和关闭操作。只读 state 也不能与同任务的长命令并发。
- 父任务不进入子任务浏览器“看进度”；通过宿主消息工具询问，由子任务回传日期、来源、已核实结果、阻塞项。
- 交接要明确移交 --id、--session、目标 URL、当前日期和在途命令；前任确认不再使用后，后任才接手。
- 每个执行者只关闭自己的任务；不要为排障 shutdown/restart 所有人共用的服务。

## 3. 四种标识不混用，不 busy-poll

| 返回来源 | 标识 | 收集方式 |
| --- | --- | --- |
| dsb start | 浏览器任务 id | 后续 dsb --id，不是异步作业 ID |
| dsb batch --async | dsb 的 data.jobId | dsb job（先查帮助）或 batch --async --wait |
| 宿主后台执行工具 | jobId | 宿主 job_output；不再需要时 job_kill |
| 宿主 subagent / subagent_fork 的 continuable 结果 | subagentId | send_message 与完成通知；不是 job_output 参数 |

依据实际返回的 kind 选择分支：某些子代理调用会返回 background + jobId，此时才按宿主作业处理。记录所有已启动作业的 ID 与用途。

不要 while 查询 list_agents/job_list/tasks，也不要 sleep 几分钟再查询。等待通知期间做独立工作；只有确实被依赖阻塞，才对真实 jobId 调 job_output(wait:true)。dsb 批次用有上限的 --async --wait，这是工具内部等待，不再外套轮询循环。返回前收集仍相关作业结果，取消不再需要的作业；子代理用消息协调收尾。

网页异步加载使用有上限的 wait_for_text / wait_for_function / wait_for_response。已有旧结果时，“行数大于零”不够，必须确认新请求的日期、路线与结果一致。超时不代表后台停止，更不能重发有副作用的动作。

## 4. 业务后置条件优先

ok:true 是工具层结果；changed、effective、DOM diff 只是观察线索。changed:true 可能只是时钟/动画；changed:false 可能是页面重建或观察窗口太短。两者都不能证明业务成功或失败。

执行前写清业务后置条件，例如“本次响应出发日期等于目标日期”“详情页显示目标订单及状态”。动作后有界等待该条件，再读回证据。探针不可信或动作状态未知时先观察，禁止补点、重复提交或直接换交互方式再做一次。

## 5. 简洁任务提示模板：先代表性方案，再补全量

```text
查询【出发地】到【目的地】，目标日期【YYYY-MM-DD】，时区【Asia/Shanghai】。
先加载全部适用技能。仅查询，不登录、不下单、不支付。
第一阶段：尽快给出 5–8 个已核实的代表性方案，覆盖低价、最快、上午/下午/晚间；
数据不足时少报并说明限制，不为凑数猜测。注明采集时间和搜索范围。
每个方案给班次、实际站点/机场、完整起降日期时间、席别、票价口径和余票状态。
逐字段记录来源 URL、DOM/API 证据、采集时间；其他平台的价格不能写成主源价格。
核对响应实际日期，跨日到达写 +1 及日期。税费不明写“平台起价，税费未确认”。
候补不等于有票；不同舱位、席别及税费口径分开比价。
先汇报代表性结果及已核验字段，再在用户需要或原任务要求时补全量明细；
全量尚未完成时不能宣称最低/最快是全日最优，也不能宣称已拿齐总班次。
每个浏览器 --id 只归一个执行者，父任务不进入子任务页面；进度用消息传递。
不 busy-poll、不 sleep 等子任务。输出中文简洁结论，不输出内部调试推理。
```

## 6. 文档维护与安装

技能源为仓库 .agents/skills；scripts/sync-skills.mjs 按宿主处理 frontmatter。Claude Code 安装为 dsb-skills 插件，使用 dsb-skills:{name} 调用；DSH 与 Codex 保留原有命名。旧清单管理的 Claude 扁平副本迁移到技能扫描目录外的备份位置，避免重复加载。
先读取源与安装文件并检查差异，保留双方已有改动。同步脚本当前按宿主同步全部技能，不支持单技能筛选；只改三份技能时，定向同步已审阅文件，不能运行 --all --force 覆盖其他技能。不要修改安装清单或无关安装文件来掩盖漂移。同步后比较内容哈希，确认新引用文档也存在。

文档检查不需要打开真实票务网站。CLI 示例通过 help 核对；静态检查覆盖链接、frontmatter、禁止的旧写法、日期与金额示例。只有真的运行过的检查才能写“通过”。
