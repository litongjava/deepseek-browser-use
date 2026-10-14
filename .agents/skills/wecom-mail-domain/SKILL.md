---
name: wecom-mail-domain
description: 使用 dsb 为企业微信开通免费企业邮箱并绑定自有域名，配置 MX 记录、操作跨域邮箱控制台并核验绑定结果；包含一次性 token 的备用入口。
---

> Claude Code：`dsb-skills:wecom-mail-domain`。

# 企业微信：开通免费企业邮箱并绑定自有域名（实操手册）

一句话流程：**DNS 加两条 MX → 破解 iframe 进 exmail 控制台 → 绑定域名 → 回企业微信绑企业域名 → 成员邮箱自动开通**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这条链路上「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：MX 记录加好、域名在企业微信邮箱里显示「使用中」、企业微信「企业域名」绑定成功、成员邮箱分配到位，即完成。
- **人来做的部分**：企业微信后台登录（扫码）、**DNSPod 改解析要过的微信 MFA 扫码**、以及任何需要验证码/人脸的动作。
- **不做的事**：不动与本次无关的解析记录（尤其别碰 A / CNAME，会直接影响网站）、不改已有邮箱账号、不代签任何协议。
- **要提醒用户的事**：
  - **MX 记录只影响收信，不影响网站**。但**如果这个域名本来就在别处收信（比如已有其它邮箱服务商），加腾讯的 MX 会把收信切到腾讯**，先问清楚。
  - 企业微信邮箱**基础功能免费**（官方口径：全员免费使用邮箱基础功能）。要「无限容量、更多公共邮箱、邮件自动备份、IP 限制」这类高级功能才需要付费，别默认去买。
  - 域名绑定成功后，**该域名下所有成员的邮箱会按成员名自动开通**（实测 2 个成员各得一个），这一步不需要手工建。

## 1. 开工前

### 1.1 服务与浏览器

通用命令、等待、人工协助和任务收尾见 [主技能](../deepseek-browser-use/SKILL.md)。 使用有头 Chrome，扫码期间保留任务，避免中途切换 profile。

### 1.2 开工前必须问清的字段

一次问全，避免做到一半停下来：

| 组 | 字段 |
| --- | --- |
| 域名 | 要绑的域名（如 `<域名>`）、这个域名**现在有没有在别处收信** |
| DNS | 域名在哪个解析服务商（DNSPod / 腾讯云 / 阿里云 / Cloudflare…）、**是否由你操作**、改解析要不要过 MFA |
| 邮箱 | 是否要给全员开通、是否有成员要单独分配、是否需要公共邮箱 |
| 顺序 | 确认**先加 MX 再绑域名**（反了会卡在校验） |

## 2. 第一步：加 MX 记录（在 DNS 服务商那边）

### 2.1 要加什么

企业微信/腾讯企业邮箱要求的记录固定是这两条（**主机记录留空，也就是 `@`**）：

| 记录类型 | 主机记录 | 记录值 | 优先级 | TTL |
| --- | --- | --- | --- | --- |
| MX | `@`（不填） | `mxbiz1.qq.com` | 5 | 600 |
| MX | `@`（不填） | `mxbiz2.qq.com` | 10 | 600 |

> 企业微信后台会把这两条**原样列给你**（见 3.4），以页面显示的为准。有的页面给的值带结尾的点
> （`mxbiz1.qq.com.`），那是 DNS 的绝对域名写法，**填不填点都行**。

### 2.2 DNSPod / 腾讯云解析：加记录

**入口**：`https://console.dnspod.cn/dns/list` → 点域名那一行的「解析」→ 落到
`https://console.dnspod.cn/dns/list/detail/<域名>/records`。

> **别用直连 URL 打开解析页**：实测直接 `go_to_url` 到 `console.dnspod.cn/dns/list/detail/<域名>/records`
> 只会渲染出导航壳（`#console-root` 长度 138），列表是空的。**必须从列表页点「解析」进去**，SPA 才会把数据拉起来。

**加一条记录的步骤（实测）**：点「添加记录」→ 表格里**内联展开一行**（不是弹窗）→ 依次填。

**这一行的字段定位（用 `name` 属性，最稳）**：

| 字段 | 选择器 | 说明 |
| --- | --- | --- |
| 主机记录 | `input[name=Name]` | 填 `@`（或留空，等于 `@`） |
| 记录类型 | 点显示当前类型的 `div`（如显示 `A` 的那个） | 弹出 `li` 列表，选 `MX` |
| 记录值 | `input[name=Value]` | `mxbiz1.qq.com` |
| 权重 | `input[name=Weight]` | 留空 |
| 优先级 | `input[name=MX]` | **只有选了 MX 才会出现这个输入框**，填 5 |
| TTL | `input[name=TTL]` | 默认 600，不用改 |
| 确认 | 该行内的「确认」按钮 | 提交 |

**关键点**：

- **`input[name=MX]` 是「优先级」**（属性名容易误解成记录类型）。选 A 类型时它不存在，选 MX 后才渲染。
- 主机记录旁边有快捷按钮 `www` / `@` / `mail` / `*`，**点 `@` 那个按钮比手打更省事**（按钮文本就是 `@`）。
- 记录类型下拉的选项是 `li` 元素，文本为 `A` / `CNAME` / `MX` / `TXT` / `AAAA` / `NS` / `CAA` / `SRV`；
  用 JS 找**文本完全等于 `MX`** 的最内层元素点它。
- 一行填完**必须点「确认」**，否则记录不落库。提交成功会有「添加成功」提示，并给出「预计最晚生效时间」。

**可直接复用的写法**（把每一步拆开、每步之间等一拍）：

```json
{"id":2001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"(()=>{const n=document.querySelector('input[name=Name]');if(n)n.id='dsh_name';const v=document.querySelector('input[name=Value]');if(v)v.id='dsh_value';const t=document.querySelector('input[name=TTL]');if(t)t.id='dsh_ttl';const d=[...document.querySelectorAll('div')].filter(e=>e.offsetParent!==null&&e.innerText&&e.innerText.trim()==='A'&&e.children.length===0).pop();if(d){d.id='dsh_type';d.click()}return {name:!!n,value:!!v,type:!!d}})()"}},
  {"input_text_by_selector":{"selector":"#dsh_name","text":"@"}},
  {"wait":{"seconds":3}},
  {"execute_js":{"body":"(()=>{const o=[...document.querySelectorAll('li,div,span')].filter(e=>e.offsetParent!==null&&e.innerText&&e.innerText.trim()==='MX').pop();if(!o)return {noOpt:true};o.click();return {ok:true}})()"}},
  {"wait":{"seconds":3}},
  {"execute_js":{"body":"(()=>{const row=[...document.querySelectorAll('tr')].filter(e=>e.offsetParent!==null&&e.querySelector('input[name=Name]'))[0];if(!row)return {noRow:true};const v=row.querySelector('input[name=Value]');const m=row.querySelector('input[name=MX]');if(v)v.id='dsh_value';if(m)m.id='dsh_mx';return {v:!!v,m:!!m}})()"}},
  {"input_text_by_selector":{"selector":"#dsh_value","text":"mxbiz1.qq.com"}},
  {"input_text_by_selector":{"selector":"#dsh_mx","text":"5"}},
  {"execute_js":{"body":"(()=>{const row=[...document.querySelectorAll('tr')].filter(e=>e.offsetParent!==null&&e.querySelector('input[name=Name]'))[0];const b=[...row.querySelectorAll('button')].filter(e=>e.innerText.trim()==='确认')[0];if(!b)return {noBtn:true};b.click();return {ok:true}})()"}},
  {"wait":{"seconds":8}}
]}}
```

第二条（`mxbiz2.qq.com` / 优先级 10）重复一遍即可。

### 2.3 坑：DNSPod 改解析要过微信 MFA 扫码

**实测**：点「确认」提交时，DNSPod 会弹「身份验证」浮层：

> 身份验证 —— 为了您的账号安全，请用微信扫码验证身份。 MFA 是什么？
> 切换成普通二维码
> 需要（微信：`<脱敏后的微信号>`）扫码验证身份

- 这是**真实鼠标操作才能过的**，智能体过不去，**必须交给人**。
- 用 `request_human_input`（主技能第六/九节）把二维码交给用户：

```json
{"id":2001,"method":"request_human_input","params":{
  "selector":"img[src*='genQrImg']",
  "prompt":"DNSPod 要求微信 MFA 扫码才能改解析，请用微信 <微信号> 扫码，完成后告诉我",
  "timeoutSeconds":300}}
```

- **把二维码贴进聊天框**（用户明确要求过）：回执里的 `data.imageUrl` 补上绝对地址，
  用 markdown 图片语法贴到回复里，用户就能在对话里直接扫：

  ```markdown
  ![DNSPod MFA 二维码](http://localhost:10049/data/2001/shot-1.png)
  ```

  同时仍然 `bring_to_front` 把有头窗口推到人眼前（二维码有时效，窗口里那份最新）。
  **不要**去要 `data.imageBase64`——几十 KB 且模型多半读不了图，要图就给 `data.imageUrl`（服务端默认也不回了）。

- **二维码有时效**（几分钟）。超时了要重新点一次「确认」生成新的，别拿旧码反复催用户。
- 扫码期间那个浮层里有「登录失效，请重新登录」这类提示时会**一闪而过**（是 toast，不是真失效）——
  别被它误导去重登。判断是否真失效：看页面列表还在不在、能不能读到记录，而不是看有没有出现过那句提示。
- **MFA 通过后**，之前填好的表单内容会保留，直接继续提交即可。

### 2.4 验证 MX 真的生效

**别只看 DNSPod 页面说「添加成功」**，要独立验证。多个解析器都查一遍（有些解析器缓存旧结果）：

```powershell
foreach($s in @('8.8.8.8','1.1.1.1','119.29.29.29','223.5.5.5')){
  "=== $s ==="
  Resolve-DnsName <域名> -Type MX -Server $s -ErrorAction Stop |
    Where-Object { $_.NameExchange } |
    ForEach-Object { "  $($_.NameExchange)  pref=$($_.Preference)" }
}
```

- **`119.29.29.29` 是腾讯自己的 DNS**，它能看到就说明腾讯侧没问题，最贴近企业微信校验时的视角。
- 再查一次**权威 NS**（DNSPod 的 `booking.dnspod.net` / `rum.dnspod.net`），权威有就说明记录真的落库了。
- 实测：记录加完**几分钟内**四个公共解析器就都能查到；但**企业微信页面上的「立即验证」可能要再等一会**才过。

## 3. 第二步：破解跨域 iframe，进邮箱控制台

**这一节是本文的核心。** 企业微信后台的「邮件」应用**不是企业微信自己的页面**，而是把
`exmail.qq.com` 的邮箱管理控制台套在一个**跨域 iframe** 里。

### 3.1 跨域控制台与一次性 token

邮箱管理页嵌在 `exmail.qq.com` iframe 中。顶层 DOM 读不到其内容，直接重开 iframe 的 src 又可能因一次性 token 已被消费而返回 500。

### 3.1′ 现在有两条路：直接读 iframe（正规解）还是换 token（兜底解）

**服务端已经补上 frame 支持了**，所以先试正规解：

```json
{"id":2001,"method":"list_frames","params":{}}
```
```json
{"id":2001,"method":"get_browser_state","params":{"includeFrames":true,"highlight":false}}
```

拿到之后：iframe 里的元素会以 `frameIndex > 0` 出现在**同一份索引清单**里，点它/填它**不用传 frame 参数**
（索引里已经带了 frame 信息，服务端自动路由）；要按选择器或跑 `execute_js` 时再显式传 `frame`：

```json
{"id":2001,"method":"click_element_by_selector","params":{"selector":"a[href*=domain]","frame":"exmail.qq.com"}}
{"id":2001,"method":"execute_js","params":{"frame":1,"body":"() => location.href"}}
```

**什么时候还要用下面 3.2 的换 token 法**：

- iframe 太窄/太小，把控件裁掉了，点在 iframe 里根本点不到；
- 主站有轮询会在你操作时把 iframe 状态重置回首页；
- 需要在控制台里跨页面（iframe 内导航一换文档，之前的索引就全废了，得重新 `includeFrames` 取快照）；
- 服务端版本较老、还没有 `list_frames` 这条命令时。

> 一句话：**先试 `includeFrames`，不行再换 token 顶层直连。** 下面的换 token 法依赖「主站恰好有一个可同源
> 调用的 token 接口」，**不是通用解**。

### 3.2 破解：从企业微信后台现取一个新 token

企业微信后台自己有个接口在给各个嵌入式应用发 token：

```
POST https://work.weixin.qq.com/wework_admin/apps/qykit/login/tokenAndOAuthCode?lang=zh_CN&f=json&ajax=1
Content-Type: application/x-www-form-urlencoded

type=exmail&srcUrl=exmail.qq.com%2Fmail%2Fmngpage&noopentoken=1
```

响应：

```json
{"data":{"token":"<64 位左右的 token>","auth_code":"<auth_code>"}}
```

**关键点**：这个接口与企业微信后台**同源**，所以可以在后台页面上用 `execute_js` 直接调它，
**拿一个全新的、还没被任何 iframe 消费过的 token**，然后立刻把顶层页签导航到控制台 URL。

`execute_js` **会 await Promise**，所以直接写 `async` + `await fetch` 就行（不用再退回同步 XHR）：

```json
{"id":2001,"method":"execute_js","params":{"body":"async () => { const r = await fetch('/wework_admin/apps/qykit/login/tokenAndOAuthCode?lang=zh_CN&f=json&ajax=1', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'type=exmail&srcUrl=exmail.qq.com%2Fmail%2Fmngpage&noopentoken=1' }); return (await r.json()).data; }"}}
```


拿到 token 后**立刻**顶层打开（不要先做别的事，token 有短时效）：

```
https://exmail.qq.com/mail/mngpage?qykit_goto=&locale=zh&token=<token>&wwmng_authcode=<auth_code>&style=top_bar#toolbox/index
```

**这一步成功的话**，你会看到页面变成腾讯企业邮箱的管理后台，`document.body.innerText` 里出现
「概况 / 邮箱域名 / 邮箱账号 / 邮箱管理 / 安全管理 / 数据统计 / 操作日志 / 配置」这些导航项。

取 token 和使用它导航按依赖顺序执行；不记录或共享 token。通用 frame 读法见 [读页面分册](../deepseek-browser-use/references/reading-pages.md)。

### 3.3 控制台是 hash 路由：直接改 hash 跳页

进到控制台后，**别急着找按钮点**——它是标准的 hash 路由，`location.hash` 直接改就能跳页，
比找按钮点稳得多（按钮可能是纯 JS 事件、点了没反应）。

从页面上的 `<a title="..." href="#...">` 反查出路由表（实测）：

| 导航项 | hash 路由 |
| --- | --- |
| 概况 | `#toolbox/index` |
| **邮箱域名** | `#qyww/domain/list` |
| 邮箱账号 | `#toolbox/weworkemail/list` |
| 邮箱管理 | `#toolbox/open` |
| 安全管理 | `#toolbox/receive_send` |
| 数据统计 | `#toolbox/statistics` |
| 操作日志 | `#qyww/operateLog/mail` |
| 配置 | `#toolbox/user_login` |

```json
{"id":2001,"method":"execute_js","params":{"body":"(()=>{location.hash='#qyww/domain/list';return {h:location.hash}})()"}}
```

**注意**：`location.hash` 跳转是**同文档导航**，不会重新请求那个一次性 token，所以**可以反复用**。
（而改 `iframe.src` 或重新 `go_to_url` 到带 token 的 URL 会重新请求 → 500。这个区别很关键。）

**回读路由表的写法**（以后页面改版了可以自己重新查）：

```js
(()=>[...document.querySelectorAll('a')].filter(e=>e.getAttribute('title'))
  .map(e=>({title:e.getAttribute('title'),href:e.getAttribute('href')})))()
```

### 3.4 开通邮箱服务并绑定域名

进到 `#qyww/domain/list` 后：

**未开通时**页面是：

> 开通企业微信邮箱服务，使用统一的邮箱域名
> 免费为全员开通统一的邮箱域名，更正式更专业
> **开通** / 了解更多

**坑**：实测点这个「开通」（`<a class="domain_open">开通</a>`）**只触发一次埋点与一个
`activeSuggestDomain` 接口，页面不变、也不弹窗**。埋点名叫
`AdminConsole_SetDomainPage_NewChooseDialog`，说明本该弹出一个「选择开通方式」的弹窗，
但实测没渲染出来。

**别在按钮上耗时间，直接走路由**（这才是稳的路子）：

```
#qyww/domain/add            → 「配置域名」页
#qyww/domain/choose/self    → 「配置已有域名」页（输入框）
#domain/config/<域名>        → 「配置已有域名」的解析指引页
```

- `#qyww/domain/add` 页面内容（实测）：

  > 配置域名
  > 你的企业正在使用默认域名 @`<企业默认域名>`.wecom.work
  > 默认域名每日全员最多可发 50 封，建议配置企业专属域名
  > **免费领取域名** ｜ **已有正在使用的域名**

  **选「已有正在使用的域名」**（我们要绑自己的域名）。它是一个 `<span class="qywx_item_txt">`，
  点它之后 URL 会变成 `#qyww/domain/choose/self`。

- `#qyww/domain/choose/self`：一个 `input[placeholder="请输入域名，如：work.com"]`，填域名 → 点「下一步」。

- `#domain/config/<域名>`：给出两条 MX 记录（与第 2 节一致）和「**已完成设置，立即验证**」按钮。
  这时点「立即验证」。

  **坑**：实测第一次点，按钮文本会变成「正在验证中」然后又变回「已完成设置，立即验证」，
  而**没有发出任何校验请求**（`get_requests` 里只有一个埋点 `domain|own|verify|now`）。
  这种情况**先确认 MX 查询结果与平台校验状态**（第 2.4 节），确认无误后**再点一次**；
  也可以直接 `location.hash='#qyww/domain/list'` 去看域名列表的实际状态——
  **列表显示「使用中」才是真的成功了**，比盯按钮可靠。

- `#qyww/domain/list` 成功后的样子：

  > 添加域名 / 管理记录 / 购买记录
  > **`<域名>`** — **使用中** — 域名指向： 企业微信 — 查看详情

### 3.5 成员邮箱会自动开通

域名绑定成功后，回 `#toolbox/index`（概况）能看到：

> 使用情况
> 总使用成员数 **N** 人
> 使用邮箱服务
> 企业域名 **`<域名>`**

再看 `#toolbox/weworkemail/list`（邮箱账号）：

> 成员邮箱账号 · N
> 成员邮箱账号是为企业通讯录成员提供的免费邮箱账号，可以在企业微信客户端使用邮件
> 姓名 | 企业邮箱 | 部门 | 操作
> `<成员姓名>` | **`<成员名>@<域名>`** | `<部门>` | 编辑
> …

**这一步不需要手工建**——企业微信会按通讯录成员自动分配 `<成员名>@<域名>`。
（默认域名 `@<企业默认域名>.wecom.work` 会被替换成你的域名。）

> 如果确实要建一个**不属于任何成员**的地址（例如给某个验证流程用的随机地址），
> 用「其他邮箱账号」那一栏的「添加邮箱」/「导入」入口，而不是去建假成员。

## 4. 第三步：回企业微信绑定「企业域名」

**顺序很重要**：**企业域名绑定以「域名能收信」为前置**，所以必须**先**在企业微信邮箱里把域名绑好
（第 3 节），**再**回企业微信企业信息里绑企业域名。反了会卡在验证上。

- 入口：`https://work.weixin.qq.com/wework_admin/frame#profile/domain`
  （企业信息页 `#profile` 里「企业域名」那一行的链接是 `<a href="#profile/domain">添加</a>`）
- 页面：**绑定企业域名** / 企业域名: `input[placeholder="abc.com"]` / **绑定域名**
- 填域名 → 点「绑定域名」→ 成功显示：

  > **已绑定 `<域名>` 企业域名**

- 回到 `#profile` 能看到「企业域名 `<域名>` 修改 删除」。

> **实测要点**：域名已经通过企业微信邮箱验证过之后，这里**只需要填域名点绑定，不会再要求你去建
> 一个验证邮箱**。（企业微信的域名验证有两条路：DNS/MX 校验，或「在域名下创建指定地址的邮箱收验证信」。
> 走通了邮箱这条路之后，邮箱那条就自动满足了。）

## 5. 该流程的通用坑

### 5.6 企业微信侧的域名校验以 MX 为准

`getdomainDNS` 这类接口会回 `nameserver` / `ns` / **has_cname_record** 等信息；企业微信校验 MX 时
看的是**该域名权威 NS 上的 MX 记录**。所以第 2.4 节的多解析器验证不是可选项——**先证明 DNS 对了，
再去点验证按钮**，否则你会在按钮上反复点而不知道问题在 DNS 侧。

## 7. 交付

分别报告 MX 查询结果、邮箱控制台的域名验证状态、企业信息页的绑定状态，以及未完成项。公共解析器查询只能说明该解析器当时的结果，不能证明全球缓存均已更新。

## 8. 脱敏约定

本文所有示例都是占位符：`<域名>`、`<企业默认域名>`、`<企业全称>`、`<企业ID>`、`<成员姓名>`、
`<微信号>`、`<订单号>`。

- **不要把真实值写回技能文档**（真实域名、企业名、企业 ID、成员姓名、微信号、token、本机绝对路径）。
- **token 与 auth_code 绝对不要写进文档或日志**：它们能直接换到邮箱管理控制台的管理权限，
  且是一次性的。给用户看过程记录时也要打码。
- 给用户看的过程记录里，域名可以保留（是公开信息），但企业 ID、成员姓名、微信号**要打码**。
