---
name: deepseek-platform-topup-invoice
description: 使用 dsb 在 DeepSeek 开放平台查询余额、充值及申请发票；包含人工扫码支付、自定义抬头下拉框、提交前核对和充值／发票记录验证。
---

> Claude Code：`dsb-skills:deepseek-platform-topup-invoice`。

# DeepSeek 开放平台：充值 + 开发票（实操手册）

平台是 `platform.deepseek.com`（DeepSeek 开放平台，API 计费的那个，不是 chat.deepseek.com）。本文覆盖两件事：
**给账号充值**与**就充值金额开票**。两条线共用一套登录态，通常一次会话里连着做。

> **命令一律用 `dsb`（装在 `PATH` 上，任何目录直接敲）。** 服务端不用先起：连不上时 `dsb` 会自动拉起
> 后端并重试一次（也可以 `dsb server start` / `dsb server stop` 显式管理）。

## 0. 边界（先读这一节）

- **钱与发票的事只做用户明确要求的那一笔**。金额、收票邮箱、抬头、税号一律先问清楚再动手；
  不要自己"顺手"把可开票金额全部开掉——那是用户的税务决定，不是执行细节。
- **开票提交不可逆**。表单上写着两条硬规则：
  1. `Amounts for issued invoices are non-refundable.`（已开金额不退）；
  2. `Invoice information cannot be changed once submitted.`（提交后信息不可修改）。

  所以**提交前必须把填好的字段原样列给用户确认**（本文第 4.5 节给了确认清单的写法）。
- **支付与（可能的）短信验证码只能人做**：微信/支付宝扫码是人的动作，不要试图绕。
- 充值页那笔钱**只用于 API 计费**，页面上写着网页版与 App 的对话免费、不需要充值——用户如果说
  "给 chat 充值"，先把这句念给他听。

## 1. 站点特征

| 项目 | 实测值 |
| --- | --- |
| 充值页 | `https://platform.deepseek.com/top_up` |
| 用量/余额页 | `https://platform.deepseek.com/usage` |
| 账单/发票页 | `https://platform.deepseek.com/transactions`（四个页签 `Topped-up` / `Granted` / `Refunds` / `Invoices`） |
| 技术栈 | React 单页应用（类名前缀 `ds-`，自有组件库：`ds-button` / `ds-select` / `ds-modal-content` / `ds-form-item`） |
| 登录页 | 未登录访问受保护页会 302 到 `https://platform.deepseek.com/sign_in` |
| 登录方式 | 手机号 + 短信验证码 / 账号密码 / 微信扫码 三种 |
| 关键接口 | `GET /api/v0/fapiao/available_amount?income_type=TOPUP`、`GET /api/v0/fapiao/company_hint?keyword=...`、`POST /api/v0/fapiao/apply`、`GET /api/v0/fapiao/history` |

### 只查余额：页面上读，或者用 ``get_user_summary`` 接口读

用户问「我的 DeepSeek 余额还有多少」时不用做任何写操作，两条路都行：

1. **读页面**（最省事）：`/usage` 页顶部就是 `Topped-up balance`（充值余额，实测那次是 ¥105.01 CNY）与
   `Total cost`。页面上**只列充值余额**，赠送/代金部分要另看（见下）。
2. **读接口**（要精确的数字、或要把「充值 / 赠送 / 累计消费」分开时）：

   ```
   dsb --port 10049 --id <ID> js '@余额.js'      # 是子命令 js,不是 run js
   ```

   ```javascript
   async () => {
     let t = localStorage.getItem('userToken') || '';
     try { const o = JSON.parse(t); t = o.value || o.token || t; } catch (e) {}
     const r = await fetch('/api/v0/users/get_user_summary',
       {credentials: 'same-origin', headers: {Authorization: 'Bearer ' + t}});
     return await r.json();
   }
   ```

   回执里 ``normal_wallets`` 是充值余额、``bonus_wallets`` 是赠送余额、``total_costs`` 是累计消费。

**这个站点不是 cookie 鉴权，别指望 `credentials:'same-origin'` 帮你带凭据**：实测裸 `fetch` 会拿到
`{"code":40002,"msg":"Missing Token"}`，token 存在 localStorage 的 `userToken` 里（JSON，取 `.value`），
必须自己放进 `Authorization: Bearer`。另外连续快速调用同一接口可能回 `202` + 空 body（风控/限流）——
隔几秒重试，或直接以页面上的数字为准。

## 2. 开工前

### 2.1 服务

通用命令、等待、人工协助和任务收尾见 [主技能](../deepseek-browser-use/SKILL.md)。 使用 `dsb list_methods` 核实当前服务能力。

# 第一步永远是这一条:确认手上这份服务有哪些方法
dsb --port 10049 run list_methods
```

- 连 `list_methods` 都不支持 → 这就是一份落后很多的旧包，**改用与源码一致的 jar 启动**：

  ```shell
  dsb server start --jar dist\<某个 jar>    # 显式指定要用的 jar
  dsb server status                        # 确认起来了、用的是哪个 jar
  ```

- 启动脚本的 `/playwright/config` 那一步在老服务上会 404（`get_config` 不存在），**不影响启动**：
  健康检查过了就是起来了，用 `run list_methods` 复核即可。

### 2.2 登录状态

打开 `https://platform.deepseek.com/transactions`，若跳到 `/sign_in` 则请用户在有头窗口登录。登录后重新进入受保护页面并读余额或账户信息确认；不能凭 profile 存在或仅有 WAF cookie 判断已登录。本站也使用 localStorage 保存认证信息，不为判断登录导出凭据。

### 2.3 开工前必须问清的字段

| 用途 | 字段 | 说明 |
| --- | --- | --- |
| 充值 | 金额 | 预设 ¥10 / ¥20 / ¥50 / ¥100 / ¥300 / ¥500，或 Custom 自定义 |
| 充值 | 支付渠道 | 支付宝 / 微信——**直接决定人拿哪个 App 扫码，必须先问** |
| 开票 | 开票金额 | 是"这一笔"，还是页面上显示的"全部可开票金额"，差别可能很大 |
| 开票 | 收票邮箱 | 必填；电子发票 7 个工作日内发到这里 |
| 开票 | 抬头类型 | 企业 / 个人 |
| 开票 | 发票类型 | 增值税普通发票（General）/ 增值税专用发票（Special） |
| 开票 | 企业抬头 + 税号 | 抬头=（公司全称），税号=18 位统一社会信用代码 |
| 开票 | 备注 | 选填，会打印在发票备注栏 |

**顺手做一件事**：拿到"公司名 + 税号"之后做一次核对（用 ``web_search`` 查一下这个税号与公司名是否配对，
公开的企业信息库里能对上就行）。税号写错，这张发票就废了，而**提交后不能改**。

## 3. 充值

### 3.1 页面结构（实测）

`/top_up` 页从上到下：

```
[16]<div role='tab'>Online recharge/>          ← 与 Bank transfer 两个页签,默认 Online recharge
[18]<label >Amount/>
[19]<label >¥10/>       [20]<input type='radio' name='topUpAmount' value='10'/>
[21]<label >¥20/>       [22]<input ... value='20'/>
[23]<label >¥50/>       [24]<input ... value='50'/>
[25]<label >¥100/>      [26]<input ... value='100'/>
[27]<label >¥300/>      [28]<input ... value='300'/>
[29]<label >¥500/>      [30]<input ... value='500'/>
[31]<label >Custom/>    [32]<input ... value='-1'/>     ← Custom 的 value 是 -1,点它才会出现可输入框
[34]<label >Payment method/>
[35]<label />           [36]<input type='radio' name='paymentMethod' value='alipay'/>
[37]<label />           [38]<input type='radio' name='paymentMethod' value='wechat'/>
[39]<div role='button'>Next step/>
Amount goes to account 199******49
```

两处**容易踩的**：

- **两组 radio 初始一个都不选中**（`checked='false'`）。别以为"默认选了 ¥10"——不点就是没选，
  点 Next step 会卡在校验上。
- 支付方式那两个 `label` 是**空的**，看不出哪个是支付宝哪个是微信，只能靠 `input` 的 `value`
  （`alipay` / `wechat`）区分。所以定位一律用选择器，别靠文本：

  ```json
  [
    {"click_element_by_selector": {"selector": "input[name=incomeType... ]"}}
  ]
  ```

  正确的两条（实测可用）：

  ```json
  [
    {"click_element_by_selector": {"selector": "input[name=topUpAmount][value=10]"}},
    {"is_checked": {"index": 20}},
    {"click_element_by_selector": {"selector": "input[name=paymentMethod][value=wechat]"}},
    {"click_element_by_index": {"index": 39}},
    {"wait_for_stable": {"timeoutSeconds": 20}},
    {"get_browser_state": {}}
  ]
  ```

  > 索引只对**这一次快照**有效：`is_checked` 那条的 `index` 要照你自己刚取到的快照改；
  > 选择器那几条不受影响，所以优先用选择器。

### 3.2 点完 Next step 会发生什么

**不跳页**：URL 仍然是 `/top_up`，页面上多出一层对话框：

```
[?]<div role='dialog' class='ds-modal-content ds-elevated ds-modal-content--dialog'>   ← get_modals 报 kind=dialog/confidence=strict
    Scan QR code to pay ￥10
    The QR code is valid for 15 minutes. Please complete the payment soon.
    <canvas 160×160>                                                                    ← 二维码就是这一个 canvas
    Done
```

- **二维码 15 分钟有效**，所以要**先把人叫到位再生成/出示**，别生成完再去聊天里慢慢解释。
- 页脚会显示 `Amount goes to account 199******49`，可以拿来复核是不是给对了账号。
- 付完之后**同一个对话框**会变成 `Payment successful, click done to view billing.`，点 `Done` 才跳到
  `/transactions`。这句话就是"这笔记账成功了"的第一手证据。

### 3.3 支付二维码

二维码在当前页的收银台对话框中，优先单独截取并交给用户扫码。旧构建若把彩色高亮层截进二维码，先移除 `#playwright-highlight-container` 再截；当前截图接口会隐藏高亮。二维码过期后重新获取，不能凭用户扫码就认定付款成功。

### 3.4 请人支付

把二维码**同时**用两条路给人：① 图片 URL（服务器本地路径 `data/<id>/shot-N.png` 拼成
`http://localhost:10049/data/<id>/shot-N.png` 更好用，用户点开就能看）；② 让人看已经带到最前的浏览器窗口。

```
dsb --port 10049 --id <ID> run request_human_input --params '@tmp\hr-pay.json'
```

`hr-pay.json` 里传 `selector: "canvas"` 让服务端把二维码一起截下来（回执里有 `imageUrl`），
并按二维码的有效期传 `expiresAt`（绝对毫秒时刻），过期后 `get_human_input` 会直接回 `expired`，
而不是让人干等。

### 3.5 付完怎么确认

1. 对话框文字变成 `Payment successful, click done to view billing.` → 点 `Done`；
2. 落在 `/transactions`，`Topped-up` 页签第一行应当是你刚付的这笔：
   `Invoice No. / Status=Success / Amount=¥10 / Payment method=WeChat Pay / Created=...`；
3. **余额对照**（最硬的证据）：付之前记一次 `Topped-up balance`（`/usage` 页），付之后再记一次。
   实测 `¥104.08 → ¥113.95`，正好等于 `104.08 + 10 − 0.13`（中间产生的 API 消耗，
   `Total cost` 从 `¥165.91` 涨到 `¥166.04`）。**差额对得上才算充值成功**，不要只看"支付成功了"那句话。

## 4. 开发票

### 4.1 入口

`/transactions` 页顶部有四块：`Topped-up` / `Granted` / `Refunds` / `Invoices`。
**前两块是 `role='tab'`，后两块是 `role='button'`**（点它们会切到别的路由/视图）。
点 `Invoices` 之后，同页出现两个子页签：`Apply for invoice`（表单）与 `Invoice records`（记录）。

### 4.2 表单字段（实测）

```
[4]  Invoice Rules（四条规则,必读）
[5]  Invoicing method            [7]  radio ``incomeType``=TOPUP   按充值金额
                                 [9]  radio ``incomeType``=CONSUME 按消费金额
[10] Invoiceable amount (Topped-up amount - invoiced/in-process amount)   ¥ 280
[11] Invoice amount              [13] text  ``placeholder='Enter invoice amount'``
[14] Email (for receiving e-invoice)  [16] text ``placeholder='Enter your email address'``
[17] Title type                  [19] radio ``fapiaoTitleType``=BUSSINESS   企业
                                 [21] radio ``fapiaoTitleType``=INDIVIDUAL  个人
[22] Invoice type                [24] radio ``fapiaoType``=ORDINARY  增值税普通发票
                                 [26] radio ``fapiaoType``=SPECIAL   增值税专用发票
[27] Invoice title               [30] input.ds-select__input  ← 自定义下拉,不是普通输入框!
                                 [28] <div role='button'>No title found?/>  ← 抬头联想的结果提示
[31] Taxpayer Identification Numbers  [33] text ``placeholder='Enter Taxpayer Identification Numbers'``
[34] Remarks (Optional)          [36] text ``placeholder='Please add remarks (Optional)'``
[38] <a>view sample image
     Submit                       ← 实测 y≈978,而视口只有 944 高:**在视口外,不进快照、没有索引**
```

- **`Invoiceable amount` 一栏在没选计费方式时就显示 `¥280`**，选了 `TOPUP` 之后仍然是 `¥280`
  （实测）。别把它当成"跟所选方式有关"的动态值去推导。
- **Submit 在视口外**是这一页最阴的一处：`get_browser_state` 不报错、只是"看不到按钮"，
  很容易误判成"这页没有提交按钮"。处理顺序见 4.4。

### 4.3 抬头是 `ds-select`：打字不落库，必须点下拉里的 option（实测踩过）

**症状**：`input_text_by_selector` 往 `input.ds-select__input` 里填公司名，回执 `ok:true`，
提交却卡在 "Enter the invoice title"。

**机制**：这是一个**自定义下拉**，那个 `input` 只是它的**过滤框**。正确三步：

1. 点开它：`{"click_element_by_selector": {"selector": "input.ds-select__input"}}`
2. 打字（或先打字再点开），下拉里会出现一条
   `div.ds-select-option.ds-select-option--pending`，文本就是你要的抬头；
3. **点这条 option**：`{"click_element_by_selector": {"selector": "div.ds-select-option"}}`

> **第 3 步之前要等它渲染出来**。实测把"点击 → 打字 → 立刻点 option"塞进同一个批量、中间不加等待，
> 会拿到 `ELEMENT_NOT_FOUND: 选择器 div.ds-select-option 一个都没匹配到`——不是选择器写错，
> 是下拉还没画出来。中间插一条
> `{"wait_for_element": {"selector": "div.ds-select-option", "timeoutSeconds": 8}}` 就稳了。

**落库的判据（三个一起看，缺一个都会被骗）**：

| 观察点 | 未落库（只打了字） | 已落库 |
| --- | --- | --- |
| 容器类名（``input.ds-select__input`` 的父节点） | `ds-select ds-select--filled ds-select--error ds-select--m` | `ds-select ds-select--filled ds-select--none ds-select--m` |
| 显示节点 ``<div class="ds-select__select">`` | 只有占位符 ``.ds-select__placeholder``（"Enter the invoice title"） | 有真值文本 |
| ``input.ds-select__input`` 自己的 `value` | **就是你刚打的字** | **被清空成 `""`** |

注意 ``--filled`` 是**样式变体**、一直都在，状态后缀才是 `--error` / `--none` 这对。

**这条最容易骗人的地方是"打个字看起来就填好了"**：未落库时 `input.value` 里躺着那段过滤文本，
`get_form_state` 会给你一个**看起来完全正常的值**，而字段其实还在 `--error`、提交时照样报"必填"。
所以**不能只看"有没有值"，要点中 option**；反过来，**落库之后**那个 input 是空的、真值只在显示节点里，
所以这时也不能只看 `input.value`（现在服务端会把它读出来并标 `valueFrom: "display"`，见下）。

**现在服务端对这件事做了两处修正**（都在真实页面上复核过）：

1. `input.value` 为空时会去组件根里找显示节点，把值报在 `value` 里并加
   **`valueFrom: "display"`**——实测落库后 `get_form_state` 给出
   `{"label":"上海某某信息科技有限公司","value":"上海某某信息科技有限公司","valueFrom":"display","invalid":false}`
   （回归用例 `BrowserObservationUpgradeTest#formStateReadsValueOfCustomSelectFromDisplayNode`）。
   看到 `valueFrom:"display"` 就说明这个值不是 input 自己的，**别拿它去跟 `document.querySelector(...).value` 比对**；
   同时它也会跳过 ``.ds-select__placeholder`` 这类占位节点，不会把占位符当成值。
2. 错误态除了外层 `form-item`，也会看**控件自己与它父节点**的类名：实测未落库时
   `ds-select--error` 挂在组件自己身上、外层 `form-item` 干干净净，老版本于是给出 `errorCount:0`
   而字段明明是红的（回归用例 `#formStateDetectsErrorClassOnTheControlItself`）。

**自证字段到底落库没有**，别只信 `get_form_state`——一次读三样：

```json
{"body": "() => { const i=document.querySelector('input.ds-select__input'); const d=document.querySelector('div.ds-select__select'); const c=i?i.parentElement:null; return {inputValue: i?i.value:null, displayText: d?d.innerText.trim():null, containerCls: c?(c.className||'').toString():null}; }", "retryOnSpurious": true}
```

落库后实测长这样：`{"inputValue":"","displayText":"<公司全称>","containerCls":"ds-select ds-select--filled ds-select--none ds-select--m"}`。

### 4.4 填 → 核对 → 提交

**填**（选择器一律走 `--params '@文件.json'`，避免 PowerShell 吃引号，见第 5.5 节）：

```json
[
  {"click_element_by_selector": {"selector": "input[name=incomeType][value=TOPUP]"}},
  {"click_element_by_selector": {"selector": "input[name=fapiaoTitleType][value=BUSSINESS]"}},
  {"click_element_by_selector": {"selector": "input[name=fapiaoType][value=ORDINARY]"}},
  {"input_text_by_selector": {"selector": "input[placeholder='Enter invoice amount']", "text": "280"}},
  {"input_text_by_selector": {"selector": "input[placeholder='Enter your email address']", "text": "<收票邮箱>"}},
  {"input_text_by_selector": {"selector": "input.ds-select__input", "text": "<公司全称>"}},
  {"click_element_by_selector": {"selector": "div.ds-select-option"}},
  {"input_text_by_selector": {"selector": "input[placeholder='Enter Taxpayer Identification Numbers']", "text": "<18 位税号>"}},
  {"get_form_state": {}}
]
```

**提交**：

1. 因为 Submit 在视口外，先把它滚进视口并**重新取一次快照**拿索引：

   ```json
   {"body": "() => { const b=[...document.querySelectorAll('div.ds-button')].find(e=>e.innerText.trim()==='Submit'); if(!b) return {found:0}; b.scrollIntoView({block:'center'}); const r=b.getBoundingClientRect(); return {found:1, y:Math.round(r.y)}; }", "retryOnSpurious": true}
   ```

   ```
   dsb --port 10049 --id <ID> run get_browser_state --params '@tmp\state-expand.json'
   ```

   （`state-expand.json` = `{"viewportExpansion": 1500, "highlight": false}`）
2. **不要用 `click_element_by_text -p text=Submit`**：这条页面上第 4 条规则写着
   "cannot be changed once **submit**ted"，而 `getByText` 传字符串是大小写不敏感的**包含匹配**，
   老版本取的是文档顺序里第一个候选 → 命中那段两千多字符的说明文字，回执 `ok:true`、页面毫无反应
   （实测踩过，详见第 5.2 节）。
3. 用滚进视口后快照里的**索引**点（`click_element_by_index`），点击**不要重发**。

**提交成功的样子**：

```
Submission successful. The e-invoice will be emailed within 7 working days if the information is correct.
PDF, OFD, and XML formats will be available for download in the email.
```

并且同页出现一行记录：`Apply date / Amount=¥280.00 / General / <抬头> / Status=Pending / [Cancel]`。

### 4.5 提交前必须让人确认

把**实际填进去的值**（从 `get_form_state` + `ds-select__select` 的文本读出来，不是你以为填的值）
列成一张表给用户，明确说清"提交后不可修改、已开金额不退"，等一句明确同意再点 Submit。
实测这一轮的表长这样：

| 字段 | 值 |
| --- | --- |
| Invoicing method | Based on topped-up amount |
| Invoice amount | ¥280 |
| Email | `<收票邮箱>` |
| Title type | Enterprise |
| Invoice type | General VAT invoice |
| Invoice title | `<公司全称>` |
| Taxpayer ID | `<18 位税号>` |
| Remarks | （空） |

> 注意 `Invoice records` 那张表**不显示税号**，UI 上事后查不到"我到底报了哪个税号"。
> 想留证据就在提交时把请求体抓下来（4.6）。

### 4.6 三重返读（这一步不能省）

开票这类操作，`ok:true` 不是结论。核对以下平台内部证据，区分申请已提交与发票已开具：

1. **页面回执**：`Submission successful...` + `Invoice records` 里那一行 `Pending`；
2. **接口请求体**（证明税号/邮箱真的发出去了——UI 里看不到税号）：

   ```
   dsb --port 10049 --id <ID> run get_requests -p filter=fapiao
   ```

   实测拿到 `POST https://platform.deepseek.com/api/v0/fapiao/apply`，请求体：

   ```json
   {"income_type":"TOPUP","amount":"280","email":"<收票邮箱>","fapiao_type":"ORDINARY",
    "title_type":"BUSSINESS","title":"<公司全称>","credit_no":"<18 位税号>","remarks":""}
   ```

3. **接口响应**：用回执里的 `requestId` 取响应体（`get_response_body` 是**回看**，跳页也读得到）：

   ```
   dsb --port 10049 --id <ID> run get_response_body -p requestId=<requestId>
   ```

   ```
   {"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":null}}    ← 成功
   ```

`Invoice records` 里的 `Operations` 有一个 `Cancel`：**出票前还能撤**，出票后就没有自助改的入口了。
收尾时把这句话告诉用户。

## 5. 该站点的通用坑

### 5.4 `wait_for_idle` 在带轮询的页面上永远等不到

实测 `/top_up` 页上 `wait_for_idle` 20 秒都没安静下来（在途请求 21 个、DOM 变更 1157 次）——
这页有轮询/动画。**改 `wait_for_stable`**（它看的是"内容变没变"，不看"忙不忙"）。

顺带一个批量里的坑：`commands` 默认 `stopOnError: true`，一条 `wait_for_idle` 超时会让**后面所有步骤都不跑**
（实测后面的 `get_browser_state` 就没执行，白等一轮）。批量里显式加 `--keep-going`（客户端）
或 `stopOnError:false`。

## 7. 脱敏约定

本文出现的账号、金额、抬头、税号、邮箱、订单号都是占位或已打码形态：账号写作 `199******49`，
抬头写作 `<公司全称>`，税号写作 `<18 位税号>`，邮箱写作 `<收票邮箱>`，订单号只在截图口径里出现。
**照抄本文时把这些换成真实值**；回执与截图落盘在 `data/<id>/` 下，里面有真实信息，排查完记得清
（`cleanup` 默认只预演，要真删得显式传 `dryRun:false`）。
