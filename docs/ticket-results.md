# 票务查询结果的离线解析与校验

本工具属于应用项目：将浏览器采集结果转换为可核验的票务数据和汇总。不新增 HTTP 接口，不修改框架，不执行登录、下单或支付。Node.js 20+，无第三方依赖。离线测试不访问网络；浏览器采集步骤才访问 12306。

## 入口与快速使用

- [解析库与 JSDoc 数据模型](../scripts/tickets/results.mjs)
- [JSON CLI](../scripts/tickets/cli.mjs)，本地命令名为 `dsb-tickets`
- [12306 页面快照脚本](../scripts/js/tickets-rail-snapshot.js)
- [合成测试输入](../scripts/tickets/test/fixtures/synthetic.json)：不是实时票价，不含真实 cookie/token。
- [自动化回归测试](../scripts/tickets/test/results.test.mjs)

在仓库根目录直接运行，无需安装：

~~~powershell
node scripts/tickets/cli.mjs --help
node scripts/tickets/cli.mjs --input scripts/tickets/test/fixtures/synthetic.json --date 2026-10-11 --summary
node --test scripts/tickets/test/*.test.mjs
~~~

工具也可读取 stdin（默认或 `--input -`）。`--out` 直接写 UTF-8 JSON，无 stdout 噪声，避免 Windows PowerShell 的重定向编码差异：

~~~powershell
node scripts/tickets/cli.mjs --input scripts/tickets/test/fixtures/synthetic.json --out tmp/tickets.normalized.json
~~~

输出路径的父目录需已存在。`--summary` 仅保留 `valid/schemaVersion/queryDate/sources/summary/warnings`，不包含全量 `options`。成功退出 0；校验失败退出 1，JSON 返回稳定错误码，例如 `DATE_MISMATCH`。失败时不返回部分成功结果，不回显原始输入或秘密字段。

## 本地安装

[安装配置](../scripts/tickets/package.json) 声明 `private: true`，没有依赖和安装脚本，不发布远端 npm。项目的 [构建文件](../scripts/tickets/build.txt) 将离线测试和本地安装串联：

~~~powershell
# Run from scripts/tickets.
build
~~~

等价的本地安装命令（仓库根目录执行）：

~~~powershell
npm.cmd install --global --ignore-scripts --no-audit --no-fund ./scripts/tickets
dsb-tickets.cmd --help
dsb-tickets.cmd --input "D:/path/to/input.json" --date 2026-10-11 --summary
~~~

安装后可从任意工作目录调用；输入输出路径相对于调用时的工作目录，跨目录时建议显式传绝对路径。Windows 示例使用 .cmd 入口以兼容 PowerShell 执行策略；macOS/Linux 使用 npm、dsb-tickets。本子模块测试不需要安装，也不启动或重启服务。

## 真实浏览器 → 快照 → 离线校验

先由浏览器任务负责人在 12306 完成所需日期、站点查询，等待结果表稳定。不要让不同执行者同时操作同一任务。随后从仓库根目录运行（任务号使用已有任务）：

~~~powershell
# Use the existing browser task; do not start another service.
dsb --id 41011 js '@scripts/js/tickets-rail-snapshot.js' --out tmp/rail-snapshot.json
node scripts/tickets/cli.mjs --input tmp/rail-snapshot.json --format dsb --date 2026-10-11 --summary --out tmp/rail-summary.json
~~~

采集脚本读取表单、DOM 文本和 aria，再调用页面同源余票查询与官方站码表。导出前比较表单和前后 DOM；有变化就失败，等待稳定后重新采集。每行从 hbdata/hbid 的首段提取公开乘车日期 boardingDate，与请求日期核验；只导出日期，不导出属性剩余内容。缺少公开日期时输出 UNVERIFIED_DOM_DATE 警告，表单日期不能证明旧结果已经刷新。原始行只保留时间、站码、车次、序号、可购标志及余票列，其余列清空；不导出整行 HTML、订票密钥或 hbid。来源 URL 删除查询参数，只保留 origin/path。

独立接口请求与页面余票可能处于不同刷新时刻。二者不一致时返回 `SEAT_CONFLICT`，不覆盖、不猜测；需刷新页面再采集。若布局改变导致站点或时间提取失败，返回 `DOM_RAW_MISMATCH`。

快照依赖当前页面选择器 `#train_date/#fromStation/#toStation/#queryLeftTable`，以及各行的 `.cdz strong/.cds strong/.ls strong`。站名按站点容器顺序读取，**不依赖 start-s/end-s 样式**，支持经停站。

## 输入契约

顶层为 `{schemaVersion:1, queryDate, sources, rail?, flights?}`；日期必须显式使用 `YYYY-MM-DD`，不接受“明天”。`--date` 是调用方独立指定的目标日期，防止把其他日期的快照当成本次结果。

### 来源与字段证据

每个 `Source` 定义 `id/provider/url/capturedAt/method`。`capturedAt` 必须包含时区；URL 不允许用户名、密码、query 或 fragment。每个确定字段使用 `{sourceId,path}` 保存来源证据。金额来自另一平台时必须单独标明，不得把整行统一标成某一平台。

### 铁路输入

`RailSnapshot` 包含：

| 字段 | 含义 |
| --- | --- |
| queryDate、domDate | 同源请求日期与页面表单日期，均须等于顶层 queryDate |
| from、to | 官方表中可唯一解析的站名或 telecode；不猜测 SHG 等未知代码 |
| stationTable | 官方 station_names 赋值文本或 @ 分隔数据；仅解析文本，不执行 JS |
| rawSourceId、domSourceId、stationSourceId | 三类证据来源 |
| rawRows | 12306 管道分隔原始行，或白名单脱敏后的字符串数组 |
| domRows | rowId、number、fromName、toName、departure、arrival、duration、可选 arrivalDate、boardingDate、seats |

原始列使用 2=trainId、3=车次、6/7=站码、8/9=时间、10=历时、11=是否可买、13=列车始发日期、16/17=站序。DOM 使用 `ticket_<trainId>_<fromSequence>_<toSequence>` 对齐，而不是数组下标；重复身份、未对齐 DOM、站点或时刻冲突均拒绝。

**第 13 列是列车始发日，不一定等于乘车日**。经停站可能在次日上车，不能因此误报日期不符。到达日期用乘车日期、出发时间、历时计算，检查到达时钟；可选 arrivalDate 也要一致，支持跨年、多日历时。

席别输入为 `{code,text,aria?,headerName?}`。优先从 aria 读取实际名称及票价，保留“二等卧”等站点显示名称，不将 RW/YW 机械翻译为软卧/硬卧。无 aria 时可保留明确的 headerName，但不编造票价；原始余票存在但 DOM 缺失时，名称/票价保持 null，并给出警告。

### 航班输入

浏览器或平台适配层提供显式字段，不与特定航司接口绑定。合成输入中已有完整示例：

- 航班：number、operatingNumber、fromCode、toCode、departure、arrival、可选 durationMinutes、fieldSources、offers。
- 国内航班时间为 `YYYY-MM-DDTHH:mm:00+08:00`；到达日期必须明确，不把“00:05+1”当作同日。
- 报价：code、name、cabin、status、可选 count、amount（十进制字符串或 null）、currency、taxBasis、fieldSources。
- 每个报价分别给 name/cabin/status/amount/currency/taxBasis 的来源；LIMITED 还必须给 count 及其来源。
- 本适配器目前只处理 CNY；其他币种明确拒绝，不自行换算。
- 只观察到起价而未能证实可售状态时使用 UNKNOWN，不自动认定 AVAILABLE。
- operatingNumber 必须由采集者确认。不根据“同一时间同一路线”猜测代码共享关系；重复乘车方案/物理航班需显式合并来源，库不会偷偷丢弃重复项。

## 输出和汇总规则

1. 金额输出为 `amountMinor`（分）。通过十进制文本和 BigInt 转换，再验证安全整数范围；拒绝浮点输入、科学计数、负数及超过两位小数，避免舍入或精度丢失。
2. 状态明确分为 AVAILABLE、LIMITED、WAITLIST、SOLD_OUT、NOT_OFFERED、UNKNOWN。“--”是无此席别，空字符串是未知，“候补”不是有票。
3. `serviceId` 标识一次列车运行；`optionId` 增加乘车日、上下车站与站序。同车次不同上车站分别统计方案，而列车运行数量只计一次。
4. 按模式和站码分别统计 departures/arrivals，两侧合计均断言等于 optionCount。分站结果不是独立列车数量。
5. `lowestBuyableEconomy` 只考虑 AVAILABLE/LIMITED、有确定金额且属于 ECONOMY 的报价。铁路 canWebBuy 非 Y、候补、公务/商务舱、一等、无座和未知类别均排除。铁路这里的 ECONOMY 是普通座/卧的筛选分类，真实席别名称仍保留，不暗示卧铺与座席体验相同。
6. 最低价按模式、币种、税费口径分组，保留并列候选及字段来源。航班 INCLUDED、EXCLUDED、UNKNOWN 不混比；UNKNOWN/EXCLUDED 的 `isTotal=false`。铁路 NOT_APPLICABLE 表示展示票价不涉及航班税费分类。
7. `--format input` 只解析一层 JSON；`--format dsb` 显式读取成功回执中的 data.result，若该字段为字符串只额外解析一层。禁止反复 JSON.parse 或替换反斜杠“修复”数据；再次编码的字符串直接拒绝。
8. 校验通过意味着输入内部一致，不代表库存仍有效、不同平台已完整交叉验证、或可以保证购票成功。

## 离线回归范围

~~~powershell
node --test scripts/tickets/test/*.test.mjs
# Or run from scripts/tickets on Windows:
npm.cmd test
~~~

回归覆盖小数与安全整数边界、站码错误/同名冲突、真实席别名称、经停站 DOM、状态语义、跨日跨年、列车与方案分离、来源与税费分组、最低可买筛选、分站合计、重复 JSON、重复结果、页面/接口冲突、CLI 文件/stdin/异地 cwd/UTF-8 输出和错误码。页面脚本在 VM 中使用合成 DOM 与 fetch 桩执行，不打开浏览器、不发出网络请求。
