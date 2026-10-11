---
name: tradingview-moomoo-link
description: 用 deepseek-browser-use 在全球站 TradingView 登录并把自己的 moomoo（富途）券商账户连上交易面板的实操手册。讲清三件最容易卡住的事：登录入口藏在**匿名用户菜单**里（页面上没有独立的 Sign in 按钮），`#signin` 哈希跳转必然 ERR_ABORTED，以及 moomoo 连接面板**一个账号密码输入框都没有**——真正的登录发生在 passport.futubull.com 的 OAuth 新页签里。另附「链接全程免费」的判定依据、中国镜像站的坑、交易面板页签文本自带计数（``Orders 2``）导致按文本定位必须用完整标签，以及实时行情页上快照索引为什么曾经永久失效。
---

> Claude Code 调用名：`dsb-skills:tradingview-moomoo-link`。由 `dsb-skills` 插件提供命名空间；源技能名与目录名保持不变。

# TradingView 连接 moomoo 券商账户

一次真实任务（2026-09-27）的完整记录：登录 tradingview.com，把美国 moomoo 账户连上 TradingView 的 Trading Panel，并读出账户与持仓。用户的验收条件里有一条「如果需要花钱就算了」，所以文中把**哪一步可能涉及付费**单独写清。

> **命令一律用 `dsb`（装在 `PATH` 上，任何目录直接敲）。** 服务端不用先起：连不上时 `dsb` 会自动拉起后端并重试。

## 站点特征

- **两个站点不是一回事**：全球站 `www.tradingview.com` 才有券商接入；`cn.tradingview.com` 是面向中国大陆的
  镜像站，**交易面板里没有券商列表**。实测在浏览器里访问 `www.tradingview.com` 时会被按出口 IP 重定向到
  `cn.tradingview.com`，所以**每一步之后都要回读 URL**，发现落到镜像站就重新打开全球站。
- 登录态保存在共享 profile 里，与其他站点共用同一份（``profileSeenBefore:true``），换引擎等于换一套登录态。
- 页面是重度 SPA + 实时行情：图表页的价格文字与 class **持续变动**，这一点直接决定了能不能用索引操作（见下文「实时行情页上的索引」）。

## 一、确认服务与实例

```shell
dsb --port 10049 health
dsb --port 10049 --id 1001 start --browser chrome --headful
```

**必须 `--headful`**：登录、OAuth 授权都要人来做，无头窗口没法交接。

## 二、登录 TradingView（人工环节）

页面上**没有**独立的 `Sign in` 按钮：它藏在右上角匿名用户菜单里。

1. 打开 `https://www.tradingview.com/`；
2. 点右上角用户菜单按钮（``aria-label="Open user menu"``，未登录时它的 class 里带 ``--anonymous``）；
3. 在弹出的 ``role="menu"`` 里点第一项 `Sign in`；
4. 登录方式只有 **Facebook / Twitter / Yahoo! / Apple / LinkedIn / Email**（没有 Google）。要更多方式先点
   `Show more options`。

把浏览器窗口带到前台再请人操作：

```shell
dsb --port 10049 --id 1001 run bring_to_front --params "{}"
dsb --port 10049 --id 1001 run request_human_input --params "@human.json"
```

然后**不要靠人工答复的字段判断是否登录成功**，回读页面自己确认。登录成功的铁证是首屏元素里出现：

```text
[0]<button type='button' aria-label='Logged in as <用户名>\nActive layout: Unnamed'/>
```

## 三、连接 moomoo（人工环节）

1. 打开图表页 `https://www.tradingview.com/chart/`；
2. 点工具栏的 `Trade` 按钮（``data-qa-id="trade-button"``）；
3. 交易面板会出现券商卡片列表，按 ``aria-label`` 找到 moomoo：

   ```text
   [11]<div aria-label='Broker card - moomoo'>moomoo/>
   ```

   列表里同时还有 ``Broker card - Paper Trading``（TradingView 的模拟盘）——**它不是你连上的真实券商**，
   读账户数据时别混。
4. 点这张卡片 → 面板里出现 `Connect` 提交按钮（``name="broker-login-submit-button"``）与一个
   `Don't remember me` 复选框；
5. 点 `Connect` → **浏览器新开一个页签**，跳到 moomoo 官方 OAuth 授权页：

   ```text
   https://passport.futubull.com/authorize?response_type=code&client_id=ft10001&scope=tradingview
     &redirect_uri=https%3A%2F%2Fwww.tradingview.com%2Ftrading%2Foauth-redirect%2Fmoomoo%2F&state=…
   ```

6. 人在这个页签里登录 moomoo（支持 `Email/User ID`、`Phone`、`Passkey`；
   账号输入框是 ``input[name="account"]``）；
7. 授权后自动跳回 TradingView。**交易面板标题栏会变成 `moomoo`**，这一步就成了。

## 四、关于「要不要花钱」

**链接券商本身免费。** 判定依据不是猜的，是逐屏核对出来的：

- 整条路径（券商目录 → ``Broker card - moomoo`` → `Connect` → OAuth 授权）里**没有任何价格、订阅或支付元素**；
- 唯一的确认是标准风险与条款勾选：`By clicking "Connect" I confirm that I've read the warning and terms of use
  and accept all risks.`；
- 面板头部那个 `Upgrade` 是 TradingView 的**通用套餐推销按钮**，不是链接券商的门槛；
- moomoo 官方帮助中心（``topic4_513``）给的步骤里同样没有付费环节。

需要提前告诉用户的两个边界（都不属于「链接费用」，但确实可能花钱）：

- 免费版在 TradingView 上看美股实时行情是**延迟**的（图表上写着 `One update every 5 seconds`）。
  要实时行情通常得单独订阅行情数据；有 moomoo LV2 权限的账户走券商通道是另一回事。
- 出入金只能在 moomoo 端做，TradingView 不支持。

## 五、回读账户与持仓

交易面板（`aria-label='Close account manager'` 那个 `moomoo` 标题栏所在的面板）里有五个页签：

| 页签 | 内容 |
| --- | --- |
| `Positions N` | 持仓：``Symbol`` / ``Side`` / ``Qty`` / ``Avg Fill Price`` / ``Profit`` / ``Market Value`` |
| `Orders N` | 挂单：``Type`` / ``Remaining Qty`` / ``Filled Qty`` / ``Limit Price``，行内带 `Modify Order…` / `Cancel` |
| `Order history` | 历史委托 |
| `Account summary` | 资产：``Securities MV`` / ``Long Positions MV`` / ``Short Positions MV`` / ``Funds On Hold`` / ``Total Cash(USD)`` / ``Settled Cash(USD)`` |
| `Notifications log` | 通知 |

**页签文本自带计数**：实际文本是 ``Positions 4`` / ``Orders 2``，不是 `Positions` / `Orders`。
按文本定位必须给完整标签，否则报「没找到文本为「Orders」的元素」：

```shell
dsb --port 10049 --id 1001 run click_element_by_text --params "@click-orders.json"
```

```json
{"text": "Orders 2"}
```

自校验的两个恒等式（都对上才说明读到的是完整一屏，而不是中途刷新的半份数据）：

- 各行 ``Market Value`` 之和 == ``Securities MV``；
- 各行 ``Profit`` 之和 == ``Unrealized P/L``。

**面板数字来自两个不同的瞬间**：``Net Assets`` 与「证券市值 + 现金」可能差几美元（实测差 3.03），
因为面板是实时刷新的。以同一个页签里一次读到的为准，不要跨页签做加法。

## 六、实时行情页上的索引（这个站点最值得记住的一条）

图表页的价格文字每几秒刷新一次，class 也跟着变。**在旧版服务端上，这会让整份快照索引永久失效**：
只要整个文档在读取期间发生过任何变更，快照就被判作废，于是每一次按索引点击都只能拿到
「索引越界: 20，当前没有页面快照，请先调用 `get_browser_state` 获取元素索引」，只能全程退回选择器与文本定位。

现在默认判据改成了**结构变更 + 逐元素重校验**（位置型 xpath 不受文字/class 变动影响），同一页面上：

```text
snapshotConsistent          : True
indicesUsable               : True
snapshotStructuralMutations : 0
snapshotContentMutations    : 1
snapshotNote                : 读取期间页面有 1 次内容变动(文字/属性),未增删元素、且索引指向的元素逐个重校验通过,索引仍可用。
```

所以在这个站点上：

- 读到 ``indicesUsable:true`` 且 ``snapshotNote`` 说明只有内容变动时，**放心按索引操作**，不必因为
  ``snapshotContentMutations`` 不为 0 就重取快照；
- 看到 ``snapshotStructuralMutations`` 不为 0（读取期间增删了元素）时索引会作废，**等页面稳定后重取**；
- 想恢复旧的严格判据（任何变动即作废）给 `get_browser_state` 传 ``strictSnapshot:true``，用于排查。

## 七、读 K 线数据：图例是文字，时间轴是像素

**结论先给：要某只票某个周期的 OHLC / 涨跌幅 / 成交量，读图例的文本，不要读图。**
数字全在 DOM 里，一格不差；而时间轴的日期标签读不到（见下）。

图例就是图表左上角那行，选择器 ``[data-qa-id="legend"]``：

```text
C  Cerebras Systems Inc.  1D  NASDAQ  O 211.81  H 213.00  L 203.61  C 206.75  +0.34 (+0.16%)  Vol 5.64 M
```

- 默认显示**最新一根**的数值；涨跌的基准是**上一根的收盘价**，不是当天的开盘价。
- **前一交易日收盘价**可以从同一行里紧跟涨跌的买卖价倒算：`206.40 SELL / 206.50 BUY` 对应前收 206.41
  （206.75 − 0.34），算出来再用「前收 × (1 + 幅度) == 收盘」自校验一遍。
- 页面标题 `document.title` 也带一份简化版：``CBRS 206.75 ▲ +0.16%``；右侧详情面板的价格块同样是文本。
- **红绿是配色不是数据**：默认绿涨红跌（国内 A 股软件相反）。判涨跌请看「收盘 vs 前收」，
  别用影线长短或颜色去判。

**读某一根（某一天）的数值 → 用鼠标「按住」**：`mouse_move` 到那根 K 线上 → `mouse_down` → 读图例文本 →
`mouse_up`。按下期间十字线被钉死，图例切换成**光标锁住那一根**自己的 O/H/L/C/Vol。实测：

```shell
dsb --port 10049 --id 1003 run mouse_move -p x=990 -p y=280
dsb --port 10049 --id 1003 run mouse_down -p button=left
dsb --port 10049 --id 1003 js @读图例.js --retry-on-spurious   # 脚本里取 legend 的 innerText
dsb --port 10049 --id 1003 run mouse_up -p button=left          # 别忘了松手
```

漏掉 `mouse_up` 会一直按着，之后所有点击都会变成拖拽。另外**不必非按住不可**：鼠标悬停扫过去图例也会跟着变，
「按住」的价值是把读数锁住、便于截图取证。

**时间轴的日期是 canvas 像素 —— 读不到，而且 canvas 不进快照。** 刻度与日期浮标都画在 `<canvas>` 上
（`get_element_text` 拿它必然是空），canvas 又不可交互、没有索引，所以两个入口都够不着。要确认
「手上这根是哪一天」，改用页面上的文字线索去锚定：

- 详情面板里的 ``Last update at <时间>``（GMT+8）——换算到美东就能确定是哪个交易日的收盘快照；
- 页面标题与图例本身（它们只在「最新一根」时给出结束日）；
- **系统时钟**：读一次 `new Date()`，周末/节假日休市 → 最后一根就是上一个交易日。

实测踩过的具体坑：某次任务在周日进行，图上最后一根是**周五**那根；如果照着「今天涨跌」去理解，
就会把周五的涨跌说成今天的。**先确认交易日，再谈涨跌。**

**周期决定「一个图形是不是一天」**：图表上方选 ``1D`` 时，一根 K 线 = 一个交易日（影线是盘中最高/最低，
周末与节假日没有 K 线）；换成 ``5D`` / ``1M`` / ``3M`` 等就是 5 分钟 / 30 分钟 / 1 小时。别默认「一根就是一天」。

**换标的（切 symbol）**：直接在地址栏拼 ``?symbol=`` 不可靠（实测报 `ERR_ABORTED` 且不一定真的换过去）。
稳妥两步：点图表左上角的 symbol 按钮（``title="Symbol search"``，实测中心坐标约 `(68, 19)`，
同名按钮有多份、其中一份是隐藏副本）→ 在 ``[data-qa-id="symbol-search-input"]`` 里输入代码 →
**点结果里的 ``<代码> (US:<代码>)`` 那一行**。

- 这个输入框很吃 focus：先 `click` 它再送字。整段文本可以直接用
  `send_keys -p keys=CRCL`（回执 ``data.mode:"type"`` 即逐字符打字）；
- **别用 `send_keys Enter` 提交**（实测搜索框不认，Enter 不是它的提交方式）；
- 输入值没进框架时，用 `execute_js` 走 native setter 再派发 `input` 事件也能把结果刷出来：
  `Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(i,'CRCL'); i.dispatchEvent(new Event('input',{bubbles:true}))`。

## 八、观测陷阱

- **`#signin` 这类只改哈希的导航必然失败**：`go_to_url` 目标写 `https://www.tradingview.com/#signin` 会回
  `net::ERR_ABORTED`。直接用登录页地址 `https://www.tradingview.com/accounts/signin/`。
- **`go_to_url` 报失败但页面确实跳过去了**：实测 `https://www.tradingview.com/chart/` 报
  ``ERR_ABORTED``，地址栏其实已经到位。别重发，先 `get_page_snapshot` 读 URL 核对。
- **别去 moomoo 连接面板里找账号密码输入框**：那里**只有一个复选框**，整页 `input` 数量为 1。
  看到「没有输入框」不要以为是页面没加载完 —— 登录在 OAuth 新页签里。
- **面板里的数字不构成「已连接」的证据**：图表上出现买/卖报价、``Trade`` 按钮变成选中态都只是 UI 状态。
  判定是否连上要看账户管理面板标题是不是 `moomoo`、以及 ``Positions``/``Account summary`` 有没有真数据。
- ``get_modals`` 在交易面板上会返回 ``count`` 很大但 ``countBlocking:0``，其中多数是启发式命中的浮层，
  不是真弹窗。别照着它去 `close_modal`。
- 这个站点**没有跨域 iframe**（`list_frames` 只有主 frame），所以不需要 `includeFrames`；交易面板就在顶层文档里。
- `execute_js` 在这个页面上经常撞 ``SPURIOUS_DISPATCH``（`Object doesn't exist: response@…`）。**实测连点击类命令也会撞**：
  同一个 ``click_element_by_selector`` 一会儿成功、一会儿回 `[SPURIOUS_DISPATCH]`，而页面完全正常。三步处置：
  ① 先用 `get_page_snapshot` / `get_browser_state` 确认上一次到底生效没有；② 确认没生效就带 `--retry-on-spurious`
  重发（`dsb run` 与 `dsb js` 都有这个开关）；③ 还不行就退回**按坐标点** —— `execute_js` 取
  `getBoundingClientRect()` 算中心坐标，`mouse_move` + `mouse_click x y`（不依赖 DOM 节点句柄，实测最稳）。
- **别迷信「重取快照就能拿到索引」**：这个站点上同一批命令里，`get_browser_state` 报索引可用、
  下一条按索引的命令却回「当前没有页面快照」，说明页面在读取期间发生了结构变更。动作类命令优先用
  选择器 / 文本 / 坐标，索引只当快照刚取到、立刻就用时的快路径。

## 九、每一步怎么确认真成了

| 步骤 | 回读什么 |
| --- | --- |
| 登录 TradingView | 首屏出现 ``aria-label='Logged in as <用户名>…'`` |
| 没落到镜像站 | `get_page_snapshot` 的 ``url`` 主机是 `www.tradingview.com` |
| 券商列表里有 moomoo | 交易面板出现 ``aria-label='Broker card - moomoo'`` |
| OAuth 跳转成功 | `get_tabs` 里出现 ``passport.futubull.com/authorize`` 新页签 |
| 账户已连上 | 面板标题栏文本为 `moomoo`，``Positions``/``Account summary`` 有真数据 |
| 读到的持仓是完整一屏 | 市值之和 == ``Securities MV``，盈亏之和 == ``Unrealized P/L`` |
