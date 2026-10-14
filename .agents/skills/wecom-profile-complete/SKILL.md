---
name: wecom-profile-complete
description: 使用 dsb 完善企业微信的 logo、地址、简称、域名、联系电话和企业名片；包含简称修改次数、地址级联、隐藏上传框及保存后回读。
---

> Claude Code：`dsb-skills:wecom-profile-complete`。

# 企业微信「企业信息」完善（实操手册）

一句话流程：**有头 Chrome → 人扫码登录 → `#profile` 回读现状 → 逐字段改（logo / 地址 / 简称 / 域名）→ 每改一项立刻回读确认**。

本文是「怎么用 deepseek-browser-use 把这件事做稳」的实战记录，不是企业微信使用指南。命令的通用语义看主技能
`deepseek-browser-use`，本文只讲这个后台上「哪一步会翻车、怎么写才过」。

## 0. 边界与合规

- **做到哪一步为止**：把用户在本次任务里点名的字段改完并回读确认即停。**不要顺手改用户没要求的字段**
  —— 企业信息是**对外展示**的资料（企业地址、企业简称会出现在企业名片与对外沟通里），改错了别人看得见。
- **人来做的部分**：扫码登录、短信验证码、人脸识别、支付、盖章。**登录一律交给人**（见第 2 节）。
- **不做的事**：不绕过验证码、不伪造企业资料、不替企业做承诺类勾选。
- **三个必须提前告知用户的不可逆 / 有后果项**：
  1. **企业简称只能免费改一次**，之后再改需要走认证流程（见第 6 节）。改之前一定让用户确认。
  2. **企业域名绑定后，该域名的收信会指到企业微信邮箱**（企业微信会对域名做 MX 校验），
     动之前确认这个域名本来就该用来收企业邮件。
  3. **联系电话可以留空**，是可选字段 —— 用户说不填就不填，别自作主张补一个（见第 8 节）。
- 改动提交后一般只有 toast，**`ok=true` 不代表真的生效**：每一项都必须按第 3.4 节的写法回读一次。

## 1. 开工前

### 1.1 服务与浏览器

通用命令、等待、人工协助和任务收尾见 [主技能](../deepseek-browser-use/SKILL.md)。 使用有头 Chrome，并核对实际登录的企业。

### 1.3 开工前必须问清的字段

一次问全，避免填到一半停下来。**标记「可选」的字段，用户不说就留空**：

| 字段 | 必填 | 要问什么 |
| --- | --- | --- |
| 企业 logo | 视任务 | 图片文件路径（服务端能打开的那个）、格式与尺寸 |
| 企业地址 | 视任务 | 省 / 市 / 区三级区划 + 详细地址（门牌号） |
| 企业简称 | 视任务 | 简称文字 + **命名依据选哪一项**（见 6.3），并**明确告知只能免费改一次** |
| 企业域名 | 视任务 | 域名（**并确认这个域名已经在企业微信邮箱里绑好、MX 已生效**，见第 7 节） |
| 联系电话 | **可选** | 用户明说不填就留空；要填就问清号码 |
| 企业名片 | 可选 | 是否需要，以及要展示什么 |

**地址与简称是「对外可见」的**，填之前把值复述给用户确认一次，比事后回滚便宜。

## 2. 登录：交给人

后台入口：

```
https://work.weixin.qq.com/wework_admin/frame#profile
```

```json
{"id": 1001, "method": "commands", "params": {"stopOnError": false, "commands": [
  {"go_to_url": {"url": "https://work.weixin.qq.com/wework_admin/frame"}},
  {"wait": {"seconds": 4}}
]}}
```

然后**请人在弹出的 Chrome 窗口里扫码登录**。用 `bring_to_front` 把页签带到最前，或走
`request_human_input` 建一条人工请求（它会顺手把页签带到最前）。

**登录成功的判据**：左侧导航出现 `首页 / 通讯录 / 协作 / 应用管理 / 客户与上下游 / 高级功能 / 安全与管理 / 我的企业`。
**不要靠标题判断**（未登录时标题也是「企业微信」）。

```js
// 回读是否已登录：左侧导航在 = 进去了
var nav = [].slice.call(document.querySelectorAll('a span'))
  .map(function(e){ return e.innerText.trim(); })
  .filter(function(t){ return t === '我的企业' || t === '通讯录' || t === '安全与管理'; });
return {loggedIn: nav.length >= 2, nav: nav};
```

登录态长期留在共享 profile 里，**同一个浏览器后续任务不用再登**。若回执里 `data.browser.userProfile=false`，
说明这次用的是托管 profile，仍要按上面请人登一次。

## 3. 企业信息页结构总览与定位策略

### 3.1 页面长什么样

`#profile` 的 `document.body.innerText` 顺序（实测）：

```
企业信息 / 前往认证 / 当前认证有效期至<日期>
企业logo            <img class="profile_...">
企业简称            <企业简称>  修改
企业全称            <企业全称>  修改
主体类型            企业
企业名片            @<企业简称>   在桌面端完善
企业地址            <省><市><区><详细地址>
联系电话            添加                     ← 留空时就是「添加」
企业域名            <域名> 修改 删除          ← 未绑定时是「添加」
企业成员            N个成员  统计
企业部门            N 个部门
已使用/人数上限      N/1000 申请扩容
发票抬头            添加
行业类型 / 员工规模 / 创建时间 / 企业ID
```

对应的稳定类名（**定位优先用这些，别记索引**）：

| 位置 | 选择器 |
| --- | --- |
| 每个字段行 | `.profile_enterprise_item` |
| 字段名 | `.profile_stage_label` |
| 字段值 / 操作区 | `.profile_enterprise_item_main` |
| logo 行 | `.profile_enterprise_item_Logo` |
| 企业域名行 | `.profile_enterprise_item_domain` |
| 企业地址值 | `.profile_enterprise_item_address` |
| 企业域名「添加」链接 | `a[href="#profile/domain"].js_domain_add_report` |

> 这些类名是站点自己生成的，版本升级可能变。**用之前先回读一次确认存在**（`get_element_count` 最省事），
> 不要当永久契约。

### 3.2 定位策略：选择器 > 索引

这个后台**索引失效极快**：任何点击、任何异步渲染之后，索引全部重算。沿用上一次快照的 `[index]`
会拿到两种报错之一：

```
click_element_by_index 失败：元素不存在或页面已变化,请重新调用 get_browser_state 获取元素索引
click_element_by_index 索引越界: 999
```

所以纪律是二选一：

- **全程用选择器**（推荐）：`click_element_by_selector` / `input_text_by_selector` /
  `get_form_state` 都按 CSS 选择器走，不受索引重算影响。
- **必须按索引时**：严格「一次 `get_browser_state` → 只做一个动作 → 再取快照」，中间不插入任何其它动作。

### 3.3 两个结构性坑：内层滚动容器 + 视口外元素

**坑一：内容区是内层滚动容器，不是 `window`。** 实测 `window.scrollTo(0, 0)` 毫无效果
（`window.scrollY` 恒为 0，而目标元素还在 `y=-192`）。所以：

```js
// 正解：让元素自己滚进视口，不用管是谁在滚
var el = document.querySelector('.profile_enterprise_item_domain');
if (el) el.scrollIntoView({block: 'center'});
return {y: el ? Math.round(el.getBoundingClientRect().top) : null};
```

要确认到底是哪个容器在滚（排查用）：

```js
return [].slice.call(document.querySelectorAll('div'))
  .filter(function(e){ return e.scrollHeight > e.clientHeight + 50 && e.clientHeight > 300; })
  .map(function(e){ return {cls: String(e.className).slice(0, 50),
                            sh: e.scrollHeight, ch: e.clientHeight, top: e.scrollTop}; });
```

服务端也有现成的：`scroll_to_text`（按文本滚动到可见，找不到会等满 30 秒）——
**别用它探测元素是否存在**，那是 `wait_for_element` 的活。

**坑二：快照只覆盖当前视口。** 视口外的元素**不在 `data.text` 里、也没有索引**。
所以「找不到元素」的第一反应应该是**它不在视口内**，而不是选择器写错了。判断顺序：

1. `get_element_count` 按选择器数一下（不需要索引、不受视口影响）；
2. 有数量但快照里没有 → `scrollIntoView` 把它滚进来，再取快照；
3. 还是没有 → 才怀疑选择器。

```json
{"id":1001,"method":"get_element_count","params":{"selector":".profile_enterprise_item"}}
```

### 3.4 回读确认（每一项改完都要做）

**`ok=true` 只代表动作没抛异常，不代表点中了东西。** 用 `data.changed` 只能说明「观察窗口内页面动过」，
所以改完必须回读真实值：

```js
// 一次性回读企业信息页所有字段（标签 → 值），比逐个 get_element_text 省得多
return [].slice.call(document.querySelectorAll('.profile_enterprise_item')).map(function(e){
  var k = e.querySelector('.profile_stage_label');
  var v = e.querySelector('.profile_enterprise_item_main');
  return (k ? k.innerText.trim() : '?') + ' = ' +
         (v ? v.innerText.trim().replace(/\s+/g, ' ') : '(空)');
});
```

要按「字段真的变了」下断言时，用批量指令的 `expect`（见 [批量分册](../deepseek-browser-use/references/batch-and-js.md)）：
**回执说成功不算数，断言通过才算数**。

## 4. 企业 logo

### 4.1 怎么点

logo 行是 `.profile_enterprise_item_Logo`，点它旁边的「修改」打开上传弹窗：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"var row=document.querySelector('.profile_enterprise_item_Logo');if(!row)return {noRow:true};var a=[].slice.call(row.querySelectorAll('a,span,div')).filter(function(e){return e.innerText.trim()==='修改'})[0];if(a){a.id='dsh_logo_edit';a.click()}return {clicked:!!a};"}},
  {"wait":{"seconds":3}}
]}}
```

### 4.2 上传框是「隐藏且跑到视口外」的 file input

**这是本任务最容易卡住的一步。** 弹窗里的 file input：

- `opacity: 0`
- 位置在视口外（实测 `x = -1166`）

后果有两个，都很迷惑人：

1. 它**不在快照里**（不可见 → 没有索引）→ 按索引的 `upload_file` 无从下手；
2. 它**也不是「看得见但被挡住」**，所以 `mode:"mouse"` 这类强制真实鼠标的招数对它没用。

**正解：`upload_file` 直接传 `selector`。** 它不需要元素可见，也不需要索引 —— 主技能对应分册
「上传文件」写得很明确：

```json
{"id":1001,"method":"upload_file",
 "params":{"selector":"input[type=file]","path":"<服务端能打开的 logo 文件路径>"}}
```

- `path` 是**服务端**路径，不是 URL。相对路径按服务端暂存目录解析。
- 智能体与浏览器不在同一台机器时，先把文件 POST 上去，再用回执里的 `path`：

```shell
dsb --port 10049 upload logo.png
# → {"data":{"filename":"logo.png","path":"<服务端暂存目录>/logo.png","relativePath":"logo.png","size":16799,...}}
```

- **选择器要选准**：页面里可能同时挂着别的 file input（例如「补充证明材料」那类）。传之前先用
  `get_element_count` 数一下 `input[type=file]` 有几个，必要时用更具体的选择器（带上弹窗容器的类名）。
- 这个 logo 上传框是**普通的 `ww_fileInput`，原生上传就能成功** —— 与认证流程里那个
  「Vue `ImageUploader` 的 `uploadInput` 没挂监听器、必须直接调组件方法」的坑**不是一回事**
  （那个坑见 9.4）。**先按本节的写法做，不要一上来就去调组件方法。**

### 4.3 回读

上传成功后页面上那张 `<img>` 的 `src` 会换成新地址（实测会变成 `wework.qpic.cn` 上的新链接），
并且 `width` 变成实际渲染宽度：

```js
var img = document.querySelector('.profile_enterprise_item_Logo img');
return {src: img ? img.src : null, w: img ? img.width : null, h: img ? img.height : null};
```

**判据是 `src` 变了**，不是「弹窗关了」。弹窗不自动关的话按 9.5 收掉。

> 想让隐藏 input 变成可见（**只在确实没有别的办法时用**）：`style.cssText` 强行改成
> `position:fixed; top:80px; left:80px; width:240px; height:40px; opacity:1; display:block; visibility:visible; z-index:2147483647`。
> 改动页面样式可能让站点行为与真实用户不一致，**而且这里根本不需要** —— `upload_file` 传 `selector` 就够了。

## 5. 企业地址

### 5.1 打开编辑

企业地址那一行点「修改」→ 弹窗里是 **三级区划级联下拉 + 详细地址**。

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业地址'; })[0];
if (!row) return {noRow: true};
var a = [].slice.call(row.querySelectorAll('a,span,div'))
  .filter(function(e){ return e.innerText.trim() === '修改'; })[0];
if (a) a.click();
return {clicked: !!a};
```

### 5.2 级联的顺序与纪律

三级区划是**级联下拉**：先选省 → 才出市 → 选市 → 才出区。三条纪律：

1. **一级一级来，每级之间等 2 秒**。下一级的数据是上一级选完才拉的，急了就是「暂无数据」。
2. **一级失败就整段重跑**，不要只补最后一级 —— 会把上一级清掉，越补越乱。
3. **选完级联再补详细地址**（门牌号），因为级联选中会覆盖地址框的内容。

下拉的写法（原生 setter + `input` 事件触发前端过滤）：

```js
// 打开某个下拉并塞过滤词；w 是选项文本（省/市/区名）
var openAndFilter = function (sel, w) {
  var box = document.querySelector(sel);
  if (!box) return false;
  ['mousedown','mouseup','click'].forEach(function (k) {
    box.dispatchEvent(new MouseEvent(k, {bubbles: true, cancelable: true, view: window}));
  });
  var inp = box.querySelector('input') || box;
  var set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  set.call(inp, w);
  inp.dispatchEvent(new Event('input', {bubbles: true}));
  return true;
};
```

选完之后**逐级回读**，确认每一级都真的选上了再往下走：

```js
// 回读弹窗里所有输入框的当前值（区划 + 详细地址）
var dlg = [].slice.call(document.querySelectorAll('div'))
  .filter(function(e){ return e.offsetParent !== null && /详细地址/.test(e.innerText || '')
                              && e.innerText.length < 600; }).pop();
if (!dlg) return {noDlg: true};
return [].slice.call(dlg.querySelectorAll('input,textarea'))
  .map(function(e){ return {ph: e.placeholder || '', v: e.value || ''}; });
```

### 5.3 提交并回读

提交后回读 `.profile_enterprise_item_address`：

```js
var a = document.querySelector('.profile_enterprise_item_address');
return {address: a ? a.innerText.trim() : null};
```

**注意**：页面显示的是**拼接后的完整地址**，逐字和你填的详细地址可能不完全一致（级联会把省市区的
规范化名称接在前面）。**判据是「省市区对不对 + 详细地址在不在里面」**，不要因为多了/少了个空格就重填。

## 6. 企业简称（含命名依据）

### 6.1 先讲清楚：只能免费改一次

**企业简称只能免费改一次，之后再改需要走认证流程。** 所以：

- 改之前**把简称文字和命名依据一起复述给用户确认**，得到明确答复再动手；
- 用户如果只是「随口一说」，先问一句「确认现在就改成「<企业简称>」吗？这个只能免费改一次」；
- 已经改过一次的企业，这一行点「修改」可能会引导去认证 —— **别硬试**，如实告诉用户当前限制。

### 6.2 打开编辑

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业简称'; })[0];
if (!row) return {noRow: true};
var a = [].slice.call(row.querySelectorAll('a,span,div'))
  .filter(function(e){ return e.innerText.trim() === '修改'; })[0];
if (a) a.click();
return {clicked: !!a};
```

### 6.3 简称命名依据（关键字段）

弹窗里除了简称输入框，还有一个**命名依据**的下拉 / 单选项。实测取值：

| 值 | 含义 |
| --- | --- |
| `801` | **基于全称中的工商字号命名** |

还有其它选项（基于商标命名、基于字号+行业命名等），**以弹窗里实际列出的为准**，别照抄这张表。

**怎么选**：看用户给的简称是从哪儿来的 ——

- 简称就是企业全称里的字号 → 选 `801`「基于全称中的工商字号命名」（**最常用**）；
- 简称来自注册商标 → 选基于商标那一项；
- 拿不准就**停下来问用户**，不要替他选。选错可能被驳回，而且**简称只能免费改一次**。

### 6.4 提交有二次确认

点提交后会弹**二次确认弹窗**（再确认一次简称）。这一步别漏：

- 二次确认弹窗**必须用真实鼠标点**（`mode:"mouse"` 或 `mouse_click_by_selector`），
  JS 派发的 click 在确认类控件上经常无效（见 9.5）；
- 点完**回读简称是否真的变了**，而不是看弹窗关没关。

```js
// 回读简称（以及企业名片里的 @简称 是否跟着变）
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业简称'; })[0];
return {shortName: row ? row.querySelector('.profile_enterprise_item_main').innerText.trim() : null};
```

**企业名片会自动跟着变**（显示成 `@<企业简称>`），不需要单独去改。

## 7. 企业域名绑定

### 7.1 前置条件：域名可收信

先按 [邮箱域名技能](../wecom-mail-domain/SKILL.md) 配置 MX 并验证邮箱域名，再填写企业信息页的域名。

### 7.2 页面上的两种状态

| 状态 | DOM | 怎么办 |
| --- | --- | --- |
| **未绑定** | `<a href="#profile/domain" class="js_domain_add_report">添加</a>` | 点它，或直接 `go_to_url` 到 `#profile/domain` |
| **已绑定** | 显示 `<域名>` + `修改` `删除` | 已经好了，**回读确认即可，别重复绑** |

直接按路由跳过去最稳（不依赖快照索引）：

```json
{"id":1001,"method":"go_to_url",
 "params":{"url":"https://work.weixin.qq.com/wework_admin/frame#profile/domain"}}
```

### 7.3 绑定页只有一个输入框

`#profile/domain` 的页面文本：

```
绑定企业域名
企业域名:  [ input placeholder="abc.com" ]
绑定域名
```

写法：

```json
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"execute_js":{"body":"var i=[].slice.call(document.querySelectorAll('input')).filter(function(e){return e.offsetParent!==null&&/abc\\.com/.test(e.placeholder||'')})[0];if(!i)return {none:true};i.id='dsh_dom';return {ok:true};"}},
  {"input_text_by_selector":{"selector":"#dsh_dom","text":"<域名>"}},
  {"wait":{"seconds":2}},
  {"execute_js":{"body":"var b=[].slice.call(document.querySelectorAll('a,button')).filter(function(e){return e.offsetParent!==null&&e.innerText.trim()==='绑定域名'});if(!b.length)return {n:0};b[b.length-1].click();return {n:b.length};"}},
  {"wait":{"seconds":10}}
]}}
```

### 7.4 成功判据

页面文本直接变成：

```
绑定企业域名
已绑定 <域名> 企业域名
```

```js
var t = document.body.innerText;
var i = t.indexOf('绑定企业域名');
return {seg: i >= 0 ? t.slice(i, i + 120) : t.slice(-300)};
```

**不需要再建验证邮箱。** 域名验证是通过企业微信邮箱那边的 MX 校验完成的；本页填域名点绑定就结束，
**不会**出现「请创建 `workweixin<随机串>@<域名>` 邮箱并接收验证邮件」那一步。
（踩过的弯路：以为要先去建那个随机地址的邮箱，其实只要邮箱域名绑好了，本页直接过。）

绑定完成后回 `#profile` 回读，那一行应该变成 `<域名> 修改 删除`。

## 8. 联系电话与企业名片

### 8.1 联系电话：**可以留空**

这一行留空时显示的是「添加」。**它是可选字段** —— 用户明确说不填就**不要填**，
别自作主张补一个号码（这是对外可见的企业信息，填错了要再改）。

要填的时候：点「添加」（`a.js_tel_edit_btn` 是它的类名）→ 填号码 → 提交 → 回读。

```js
var a = document.querySelector('a.js_tel_edit_btn');
return {hasTelAddLink: !!a};
```

### 8.2 企业名片

企业名片显示成 `@<企业简称>`，**会跟着企业简称自动变**，一般不需要单独操作。
页面上写「在桌面端完善」的提示属于客户端功能，**网页后台点不动是正常的**，别在这里耗时间。

如果任务确实要求完善名片，先回读它的当前值确认：

```js
var row = [].slice.call(document.querySelectorAll('.profile_enterprise_item'))
  .filter(function(e){ var l = e.querySelector('.profile_stage_label');
                       return l && l.innerText.trim() === '企业名片'; })[0];
return {card: row ? row.innerText.replace(/\s+/g, ' ').trim() : null};
```

## 9. 该站点的通用坑（Vue SPA + hash 路由）

### 9.4 上传后表单仍空

先检查目标输入框与组件状态；不能仅凭 `_vei` 为空断定没有监听器。营业执照上传的组件调用与写回示例见 [企业认证技能](../wecom-register-certify/SKILL.md)。

### 9.5 弹窗：企业微信自己的类名不一定命中 `get_modals`

页面会弹各种浮层：二次确认、成功提示、协议层、运营 banner。**浮层会挡住后面的点击**，
`data.coveredBy` 会告诉你目标中心点上实际命中的是谁。

**先试服务端的方法**：

```json
{"id":1001,"method":"get_modals","params":{}}
{"id":1001,"method":"close_modal","params":{"which":"top"}}
```

`close_modal` **一律用真实鼠标点**，并且点完**校验数量是否真的减少**（回执里 `data.closed`、
`data.countBefore`、`data.countAfter`）。`data.closed=false` 说明点了但数量没减，
这时用 `get_modals` 拿 `closePoint` / `buttonPoints` 的坐标，再 `mouse_click` 那个坐标。

**但这个后台有个陷阱**：`get_modals` 的选择器是 ant / Element / vxe / layui 专用的，
**企业微信自己那套弹窗类名不一定命中 —— 返回 `count: 0` 不等于没有弹窗。**
这种情况用 `execute_js` 自己扫可见浮层：

```js
// 扫「可能挡住点击」的浮层：position:fixed 或高 z-index，且面积够大
var vis = function (e) {
  var s = getComputedStyle(e);
  if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
  var r = e.getBoundingClientRect();
  return r.width > 150 && r.height > 60;
};
return [].slice.call(document.querySelectorAll('div,section'))
  .filter(function (e) {
    if (!vis(e)) return false;
    var s = getComputedStyle(e), z = parseInt(s.zIndex);
    return s.position === 'fixed' || (!isNaN(z) && z > 50);
  })
  .map(function (e) {
    var r = e.getBoundingClientRect();
    return {cls: String(e.className).slice(0, 45), z: getComputedStyle(e).zIndex,
            x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
            txt: (e.innerText || '').slice(0, 150)};
  });
```

**还有一个判据很好用**：看屏幕中心点命中的是谁 —— 如果命中的不是你以为在点的按钮，就是被挡住了。

```js
var c = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
return {centerHit: c ? c.tagName + '.' + String(c.className).slice(0, 50) : null};
```

### 9.7 别用 JS 点左侧主菜单

左侧导航（首页 / 通讯录 / 协作 / 我的企业 …）是 `<a>` + Vue 事件，**很多没有可用的 href**，
JS 派发 click 和真实鼠标点击**都可能不跳转**。要切页时**直接 `go_to_url` 到 hash 路由**更快：

| 页面 | URL |
| --- | --- |
| 企业信息 | `.../wework_admin/frame#profile` |
| 企业域名绑定 | `.../wework_admin/frame#profile/domain` |
| 邮件（跨域 iframe，见下） | `.../wework_admin/frame#apps/qykit/proxy/exmail` |

### 9.8 跨域邮箱控制台

先尝试 `list_frames` / `includeFrames`；邮箱控制台入口与备用 token 流程见 [邮箱域名技能](../wecom-mail-domain/SKILL.md)。

## 11. 交付与收尾

报告各字段保存后的实际值、未完成项与所需人工操作。简称需注明是否已消耗免费修改机会。保留页面供用户复核，结束任务用 `close`；仅清理本次不再需要的敏感临时材料。

## 12. 脱敏约定

本文所有示例都是占位符：`<企业全称>`、`<18 位统一社会信用代码>`、`<企业简称>`、
`<省><市><区><详细地址>`、`<域名>`、`<企业ID>`、`<管理员手机号>`。

- **不要把真实值写回技能文档**（真实公司名、统一社会信用代码、企业地址、域名、企业 ID、
  管理员手机号、本机绝对路径）。
- 给用户看的过程记录里，企业 ID、手机号、统一社会信用代码**只留后 4 位或直接省略**。
- 截图与结构化文本落在服务端 `data/<id>/` 下，含企业信息，**任务结束记得清理**（见第 11 节）。
- 引用其它技能用**仓库相对路径**（如 `.agents/skills/wecom-mail-domain`），不要写本机绝对路径。
