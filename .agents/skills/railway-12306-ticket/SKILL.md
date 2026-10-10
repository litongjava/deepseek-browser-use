---
name: railway-12306-ticket
description: 中国铁路 12306（kyfw.12306.cn）网页版买票的实操手册：登录态到底怎么判断、车票查询的页面与接口两条路、下单确认页与确认弹窗的控件、一天一单未支付的限制、网页版中转换乘不可用时怎么办。包含实际购票流程与公开查询校验案例：站码、日期、乘车方案、票价、候补和字段来源。
---

# 12306 网页版买票（kyfw.12306.cn）

一次真实实操（2026-09-25 武汉站 → 民权北站，两程中转）攒下来的经验。按「必须这么做」写，不按命令顺序罗列。

## 一、站点特征（先知道这几条，能省掉一半排查）

| 特征 | 后果 |
| --- | --- |
| 登录态是 **session cookie**（浏览器进程一结束就没了） | 重启服务/浏览器 = 要人工重新登录一次。持久 cookie 帮不上忙，托管 profile 里那份登录态对 12306 基本无用 |
| 页面**渲染出来的**「您好，某某」不可信 | 它会照常显示用户名，而服务端会话早就失效了。判断登录态只能用站点的 `checkUser`（见下） |
| 中转换乘在网页版**不可用** | `/otn/lcQuery/init` 会跳到 `/otn/view/index.html`（个人中心），`/lcquery/query*` 一律回 `url error` / `系统忙`。网页端只能**两程各买一张** |
| **有未支付订单时不允许再下新单** | 弹窗 `content_defaultwarningAlert_id`：「您还有未处理的订单，请您到[未完成订单]进行处理!」。两程中转只能一程一程来：付完第一程才能下第二程 |
| 一天内**取消 3 次**申请成功的订单，当日不能再购票（无座票取消 5 次计 1 次） | 别拿真实订单试错：提交前把车次、日期、乘车人、席别看两遍 |
| 「民权北站」与「民权站」是**两个站** | 同城站会在查询结果里一起出现，选错站等于下错车，换乘要另外坐地面交通 |

## 二、购票前：确认登录态真的有效

仅查询公开时刻、票价和余票时不要求登录，不为查询主动要求用户扫码。以下会话检查用于明确授权的购票流程。

不要看页面文案，直接问站点自己：

```json
{"method":"execute_js","params":{"body":"async () => (await fetch('/otn/login/checkUser', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:'_json_att='})).json()"}}
```

拿到 `data.flag: true` 才能继续；`false` 就是没登录（此时页面照样显示用户名，别被骗）。失效时用 `bring_to_front` 把登录页带到最前，并用 `request_human_input` 请人扫码登录 —— 登录成功不会自动更新请求状态，**要重新跑一次上面的检查**再往下走。

## 三、查车次：页面与接口两条路

**页面那条路**（要下单就得走它，因为「预订」按钮只存在于结果页）：

```json
{"method":"commands","params":{"stopOnError":false,"commands":[
  {"go_to_url":{"url":"https://kyfw.12306.cn/otn/leftTicket/init"}},
  {"wait_for_load":{"state":"domcontentloaded","timeoutSeconds":30}},
  {"execute_js":{"body":"() => { const jq=window.jQuery; jq('#fromStation').val('WHN'); jq('#fromStationText').val('武汉'); jq('#toStation').val('MIF'); jq('#toStationText').val('民权北'); jq('#train_date').val('2026-09-25'); return 'ok'; }"}},
  {"click_element_by_selector":{"selector":"#query_ticket"}},
  {"wait_for_count":{"selector":"#queryLeftTable tr[id^=ticket_]","min":1,"timeoutSeconds":30}}
]}}
```

表单字段是 `#fromStation`（隐藏，telecode）、`#fromStationText`（可见）、`#toStation` / `#toStationText`、`#train_date`，查询按钮 `#query_ticket`。**结果页必须等**（`wait_for_count`），点完立刻读会读到上一次的结果。

**接口那条路**（只想筛数据时更省事，返回同城车站合并后的结果）：

```
/otn/leftTicket/query?leftTicketDTO.train_date=2026-09-25&leftTicketDTO.from_station=WHN&leftTicketDTO.to_station=MIF&purpose_codes=ADULT
```

用 `execute_js` 同源 `fetch` 即可。实际查询端点以当前页面发出的请求为准，不轮流猜 queryA/queryG 等后缀。结果里每个元素按 `|` 分隔；先 split，再只解码协议明确编码的字段，不对整行先做 `decodeURIComponent`。常用下标仅作已知格式参考，当前格式必须与页面核对：

| 下标 | 含义 | 下标 | 含义 |
| --- | --- | --- | --- |
| 3 | 车次（如 `G934`） | 26 | 无座 |
| 6 / 7 | 出发站 / 到达站 telecode | 30 | 二等座 |
| 8 / 9 | 出发 / 到达时刻 | 31 | 一等座 |
| 10 | 历时 | 32 | 商务座 |
| 11 | `canWebBuy`（`Y` 表示车次支持网页预订，不代表目标席别有票） | — | — |

站码全表在同源文件 `/otn/resources/js/framework/station_name.js`，按 `@` 与 `|` 切开就能查：武汉 `WHN`、武昌 `WCN`、汉口 `HKN`、民权北 `MIF`、民权 `MQF`、商丘 `SQF`、兰考南 `LUF`。

## 四、下单：每一步的控件与「为什么点了没反应」

1. **点「预订」必须用真实鼠标事件。** 结果页那一行的按钮是
   `<a class="btn72" onclick="checkG1234('...')">预订</a>`，`execute_js` 里的 `.click()` 不是可信事件，
   弹窗会被浏览器拦掉 —— 表现是「接口回 ok、页面纹丝不动」。正解：

   ```json
   {"method":"click_element_by_selector","params":{"selector":"#ticket_57000G318405_16_17 a.btn72"}}
   ```

   行 id 形如 `ticket_<列车内部号>_<序号>`，可以先 `execute_js` 找到含目标车次的行、把 `'#' + row.id + ' a.btn72'` 拿出来再用。

2. **确认页**（`/otn/confirmPassenger/initDc`）上的控件：

   | 控件 | 选择器 | 说明 |
   | --- | --- | --- |
   | 乘车人勾选框 | `#normalPassenger_0`、`_1`… | 顺序与页面上的「乘车人」列表一致，默认全不勾 |
   | 票种 | `#ticketType_1` | 默认成人票 |
   | 席别 | `#seatType_1` | 默认就是该车次席别（二等座 `O`、一等座 `M`） |
   | 提交 | `#submitOrder_id` | 真实点击 |

3. **提交后的确认弹窗** ``content_checkticketinfo_id``（「请核对以下信息」）里，按钮是
   ``qr_submit_id``（确认）与 ``back_edit_id``（返回修改）—— **都是 `<a class="btn92s">` 这种非框架写法**。
   服务端从这一版起会把它们列进 `get_modals` 的 `buttons`，所以既能 `close_modal` 按文本点，也能直接
   `click_element_by_selector` 点 `#qr_submit_id`。选座锚点（`A`/`B`/`C`/`D`/`F` 那堆）不会被误当成按钮。

4. **订单是否真的提交成功**，看这几个请求有没有依次发出（`get_requests` 按
   `/otn/confirmPassenger/` 过滤）：`checkOrderInfo` → `getQueueCount` → `confirmSingleForQueue`，
   随后页面 URL 变成 `/otn/payOrder/init`。页面停在原处、弹窗消失但没跳转，通常是提交失败或被拦。

## 五、支付与人工接力

支付页 `/otn/payOrder/init` 上有「支付剩余时间」（约 20 分钟），**到期未支付订单会自动取消**。
付款必须人工完成：用 `bring_to_front` 把这一页带到最前，再 `request_human_input` 交办，别自己去点支付方式。

## 六、查中转方案（网页版不给查时）

网页版查不了中转，只想**看方案**可以去携程的中转列表页（不用登录也能看到车次与票价）：

```
https://trains.ctrip.com/webapp/train/list?ticketType=2&dStation=武汉&aStation=民权北&dDate=2026-09-25&day=2026-09-25
```

拿到方案后**回 12306 官方下单**（两程各买一张）：官方无服务费，且不必再登一个携程账号。携程的价格可以当参考，成交价以 12306 确认为准。

## 七、怎么确认每一步真的成了

| 这一步 | 确认方式 |
| --- | --- |
| 登录 | `checkUser` 回 `data.flag: true` |
| 查询 | 本次请求/响应日期与路线一致，再核对结果行；旧结果行数 ≥ 1 或出现车次号不能单独证明本次查询成功 |
| 勾选乘车人 | `execute_js` 读 `#normalPassenger_0.checked` 为 `true`，且 `#seatType_1` 选中项是目标席别 |
| 提交 | 上面那三个 `confirmPassenger` 请求依次出现，URL 变成 `/otn/payOrder/init` |
| 下单成功 | 支付页上出现车次、车厢、席位号与票价（例如「G934 … 二等座 11 15C号 348.0元」） |

## 八、只查询时的数据质量检查

1. **日期先固定**：用 Asia/Shanghai 的当前日历日解释“明天”，记录明确的 YYYY-MM-DD。核对查询表单、请求参数及响应内实际出发日期；只看 URL 不够。切换相邻日期用于刷新时，丢弃其他日期响应。跨日到达存完整日期并显示“次日”。
2. **站码先验证**：从官方 station_name.js 读取站名与 telecode，不凭拼音猜。北京 BJP、北京南 VNP、上海 SHH、上海虹桥 AOH 是核对示例，仍以当前站码表为准。HTTP 200 + 空数组不等于无票：先检查日期、预售范围、站码、方向、当前页面实际端点，再区分无班次、无票与取数失败。
3. **经停车站不能漏**：不要只用 .start-s / .end-s（始发/终到样式）取站名。经停站可能没有这些 class；优先取响应的出发/到达 telecode 并映射站名，DOM 使用该行完整站点容器和语义文本核验。无法定位时保留站码并标“站名未确认”，不要补空白或猜站。
4. **车次与乘车方案分开**：同车次在同城两个站上车是两个乘车方案，不是两列车。方案键至少含出发日期、车次或内部列车号、出发站、到达站；连接多段还要保留每段。按日期与车次号去重可报告“不同车次号数”，不是无条件等同物理列车数。例：两条 G5 分别从北京和北京南出发，计 2 个方案、1 个车次号。站点分组统计合计必须等于方案总数；未知站点单独分组，不丢记录。
5. **席别状态分开**：“有”=有票但数量未知；正整数=明确余票数；“候补”=等待兑现，不能直接买到该席别；“无”=当前无票；“--”通常为不提供/不适用，但需页面上下文确认；空字段/解析失败=未知，不能当售罄。车次的预订按钮不表示二等座有票。“最低可买价”排除候补、无票和未知状态；候补参考价另列。
6. **小数票价与币种**：优先读取当前页面席别单元格的 aria-label、展开票价或对应响应，保存原文及来源。匹配应覆盖小数，例如 /票价\s*(\d+(?:\.\d+)?)\s*元/；验证 156.5、348.0 不会被漏掉或截成整数。金额保留十进制字符串或精确十进制类型，不用 parseInt，不用空值代替零，也不要给缺价补数字。
7. **动卧名称不猜测**：RW、YW 等内部字段在不同车型可能对应不同席别，不固定翻译成“软卧/硬卧”。以该车次页面票价面板、席别表头或确认页明确名称为准；未核实则输出原始代码 + “席别名称未确认”，不能为查询去创建订单。上/中/下铺与票价范围分别保留，不能把“起价”说成任意铺位价格。
8. **来源按字段保留**：时刻、站点、席别名、价格、余票可以来自不同页面/接口，每字段保留来源 URL、采集时间、证据定位与原文。12306 余票配第三方价格时分别标注；两边车次相同不代表价格/席别/余票已全部核实。

初次答复先给 5–8 个已核实代表性方案，再按需补全量。完整提示模板见 deepseek-browser-use 的可靠执行分册。仅有局部结果时写“已查方案中最低”，不声称全日最低或全量。查询结果是快照，以实际下单时票价及席位为准。

## 九、使用已安装的采集与校验工具

在浏览器工具仓库根目录操作。先按前述步骤完成正确日期的查询，再读取结果；示例中的任务ID必须属于当前执行者，日期必须替换为本次目标日期。

```powershell
dsb --id 41011 --session rail-query js '@scripts/js/tickets-rail-snapshot.js' --out tmp/rail-snapshot.json
dsb-tickets.cmd --input tmp/rail-snapshot.json --format dsb --date 2026-10-11 --summary --out tmp/rail-summary.json
```

`dsb-tickets`是本地离线校验命令。未安装时可在仓库根用 `node scripts/tickets/cli.mjs --help` 查看等价调用，或进入 `scripts/tickets` 执行 `build build.txt` 测试并安装。PowerShell使用`.cmd`入口避免脚本执行策略差异。采集脚本只导出公开列，舍弃预订密钥；不保存cookie或整行HTML。

校验失败时检查错误代码并修正来源数据，不手工改写结果使其通过。日期错位、站码错误、页面与接口库存不一致都不能当成无票。成功输出区分乘车方案数与独立列车数，并按币种、税费口径计算有明确库存的经济席别最低价。用`--out`保存UTF-8，不用重复转义修复JSON。
