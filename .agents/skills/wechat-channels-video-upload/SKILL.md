---
name: wechat-channels-video-upload
description: 用 deepseek-browser-use 把本地一个视频发布到微信「视频号」（视频号助手 channels.weixin.qq.com）的实操手册：登录页二维码 iframe 反复「加载失败」，正解是把 qrconnect 的 URL 单独在新页签打开、把二维码图给人扫；扫码后停在 oauth-callback 空白页是正常的（登录态已进 cookie），直接 go_to_url 到平台首页即可；发表页整页套在 micro-app 的**跨域子 frame**（`https://channels.weixin.qq.com/micro/content/post/create`）里——**传 frame 参数反而匹配不到，不传 frame 才命中**；上传视频后表单才渲染出来；封面由视频帧自动生成、不必须自己传。同时写透三件坑：截图整页熔断（``capture_degraded``）、发表按钮点击回执 `changed:false` 但实际已提交（URL 会跳到 `post/list`）、以及视频号**没有公开的视频链接**（只能在手机微信打开），回读凭证是稿件列表第一行 + 「视频 (N)」计数加一。
whenToUse: 需要把一个本地视频文件投稿到微信视频号（视频号助手网页版），包括扫码登录、上传视频、填描述、发表并回读确认时。站点是 channels.weixin.qq.com（Vue + micro-app 微前端）。
---

# 微信视频号投稿（视频号助手）

一次真实任务（2026-10-07，发布 `科学和技术有什么区别？.mp4`，1080×1920 竖屏 / 133 秒 / 2.8MB）的完整记录。
文内数据脱敏，替换占位符即可复用。

> **命令一律用 `dsb`（装在 `PATH` 上，任何目录直接敲）。** 服务端连不上时 `dsb` 会自己拉起。

## 一、站点特征

| 项 | 值 |
| --- | --- |
| 登录页 | `https://channels.weixin.qq.com/login.html` |
| 平台首页 | `https://channels.weixin.qq.com/platform` |
| 发表页 | `https://channels.weixin.qq.com/platform/post/create` |
| 稿件列表（回读用） | `https://channels.weixin.qq.com/platform/post/list` |
| 技术栈 | 外壳 Vue 单页 + **micro-app** 微前端；发表表单在**跨域子 frame** 里 |
| 表单所在 frame | `https://channels.weixin.qq.com/micro/content/post/create`（`name="content"`） |
| 登录态 | 在共享 profile 里，扫码一次长期有效 |

## 二、走一遍

### 1. 起任务并打开登录页

```bash
dsb --id 5001 start --browser chrome --headful     # 有头必须：登录要人扫码
dsb --id 5001 run go_to_url -p url=https://channels.weixin.qq.com/platform
```

会被重定向到 `login.html`。

### 2. 二维码在 iframe 里，而且反复「加载失败」

登录页主文档里二维码是套在 **`open.weixin.qq.com` 的跨域 iframe** 里的，实测这个 iframe
**反复显示「加载失败，点击重试」**（点重试也没用）。`includeFrames=true` 读它里面是 **0 个元素**。

```bash
dsb --id 5001 run list_frames       # 会看到 frame 1 = open.weixin.qq.com/connect/qrconnect?...
```

**正解：绕过那个 iframe，直接在新页签打开 qrconnect 的 URL**（同一个 URL，独立打开就能渲染二维码）：

```bash
# 从 list_frames 抄出完整 URL，然后：
dsb --id 5001 run new_tab
dsb --id 5001 run go_to_url -p url="https://open.weixin.qq.com/connect/qrconnect?appid=...&redirect_uri=...&login_type=jssdk&style=white"
```

新页签里二维码就出来了：`img.js_qrcode_img` 的 `src` 形如
`https://open.weixin.qq.com/connect/qrcode/<token>`。

**把二维码交给人的两条路**（二维码有效期约 2–3 分钟）：

```bash
# 路径 A（人在机器旁）：把窗口带到最前直接扫
dsb --id 5001 run bring_to_front

# 路径 B（人不在旁边）：下载二维码图，用 SendUserFile 发给人
curl -s -o qr.png "https://open.weixin.qq.com/connect/qrcode/<token>"
```

### 3. 扫码之后：停在 oauth-callback 空白页是**正常的**

扫码成功后会出现一个 `channels.weixin.qq.com/platform/oauth-callback.html?code=...` 的页签，
**标题「OAuth Callback」、页面全空、不会自动跳转**。这不代表失败——code 已经换进 cookie 了。

```bash
# 直接去平台首页验证登录态，不要傻等回调页
dsb --id 5001 run go_to_url -p url=https://channels.weixin.qq.com/platform
dsb --id 5001 state --full     # 出现「切换视频号 / 首页 / 内容管理 / 设置」+ 账号名 = 登录成功
```

### 4. 发表页：表单在跨域子 frame 里，但 **frame 参数不要传**

```bash
dsb --id 5001 run go_to_url -p url=https://channels.weixin.qq.com/platform/post/create
```

`list_frames` 会看到两个 frame：

| frame | URL |
| --- | --- |
| 0（main） | `.../platform/post/create` |
| 1 | `.../micro/content/post/create`（`name="content"`） |

**这里的反直觉之处（实测踩了 5 次）**：表单元素（file input、`.input-editor`、各种按钮）
用 `execute_js` **传 `frame=1` 能读到**，但用 `upload_file` / `input_text_by_selector` /
`get_element_count` **传 `frame=1`（或 `frame=content`、或 URL 子串）一律 `ELEMENT_NOT_FOUND`**；
**不传 `frame` 反而匹配到 1 个**。

> 实操结论：**这些选择器类命令一律不传 `frame`**，让服务端自己在整页范围内找。
> `execute_js` 读状态时传 `frame=1`（或 `frame=0`）分帧读，反而是准的——两者用途不同，别混。

### 5. 上传视频（先上传，表单才渲染）

**上传之前**，发表页的表单是**空的**（读不到描述框、短标题、封面等），只有一句上传提示。
所以「上传」是第一步，其余字段都要在上传之后填。

```bash
dsb --id 5001 run upload_file \
  -p selector="input[type=file]" \
  -p path="<视频绝对路径>.mp4" \
  -p timeoutMs=180000
```

回执里 `matched=1`（**不要传 `frame`**）。上传后页面出现 `<video>`，**`duration` 与源文件一致**
（本次 133.184 秒）就是传进去了。

```bash
# 轮询上传进度（把探针脚本放进 get_config 的 jsDir，再用 bodyFile 调）
dsb --id 5001 run execute_js -p frame=1 -p bodyFile=probe_prog.js --select data.result
# 脚本读 document.body.innerText 里的 N% / 取消上传 / 封面预览
```

### 6. 填视频描述

描述是 `contenteditable` 的 `.input-editor`（placeholder 是「添加描述」）：

```bash
dsb --id 5001 run input_text_by_selector \
  -p selector=".input-editor" \
  --params @desc.json      # {"selector":".input-editor","text":"..."}  （不传 frame！）
```

回读确认：

```bash
dsb --id 5001 run execute_js -p frame=1 \
  -p bodyFile=probe_readback.js --select data.result
# 读 .input-editor 的 innerText 长度与首尾
```

### 7. 封面：自动生成，**不必须自己传**

和 B 站不同，视频号**会自动从视频里取一帧当封面**，页面直接出现
`img.cover-img-vertical`（`src` 是 `finder.video.qq.com/.../stodownload?...`）。
不填封面也能发表。要换封面点「编辑」进封面编辑器（本次没走这条路）。

### 8. 发表前对一遍

```js
// probe_final.js：一次读齐所有待确认字段
{
  descLen: <描述字数>, descStart: <描述开头>,
  coverSet: <封面是否已生成>, videoDur: <视频时长>,
  shortTitle: <短标题>, publishBtnVisible: <可见的「发表」按钮数>
}
```

### 9. 点「发表」

「发表」是可见的主按钮（`button.weui-desktop-btn_primary`，文案「发表」）。页面上有**几十个**
同名/相似按钮（大多在隐藏弹窗里），所以**先给它打一个唯一 id 再按 id 点**最稳：

```js
// mark_btn.js
var b = [...document.querySelectorAll('button')].filter(b => b.innerText.trim()==='发表' && b.offsetParent!==null)[0];
b.id = 'dsb-publish-btn';
```

```bash
dsb --id 5001 run mouse_click_by_selector -p selector="#dsb-publish-btn"
```

**点完的回执很可能是 `changed:false` / `effective:false`——这不代表没提交。** 判断成功的唯一
标准是**页面自己跳走**：URL 从 `/post/create` 变成 `/post/list`。

### 10. 回读确认（视频号**没有公开视频链接**）

```bash
dsb --id 5001 run execute_js -p frame=1 \
  -p bodyFile=probe_verify.js --select data.result
```

判据：

- 稿件列表**第一行**就是刚发的视频，标题 + 描述开头能对上；
- 列表头计数 **`视频 (N)` 比上传前 +1**（本次 4 → 5）；
- 时间戳是刚才发表的时间。

> **视频号不像 B 站有 BV 号 / 公开 URL。** 它只有「视频ID」，且只能在手机微信内打开；
> 视频ID 要进 `post/list` 点第一条的「复制视频ID」才拿得到。所以**回读凭证就是列表第一行**。

## 三、这个站点特有的坑

### 坑 1：整页截图熔断（``capture_degraded``）

实测发表页上 `screenshot` 会超时并在若干次失败后**熔断**，回执里出现：

```
capture_degraded: true
capture_note: 截图不可用(熔断中)。这次没有画面留档，不能只凭文本断言页面正常…
```

后果与 B 站一致：**没有画面可看**，只读文本判断不出白屏/样式错乱。画面取证改用文本
（`get_browser_state` / `diff_dom_text` / `execute_js` 读 DOM），关键步骤请人看一眼。

### 坑 2：登录页二维码 iframe 加载失败

见第 2 步。**不要**在那一页反复点「加载失败，点击重试」，也不要指望 `includeFrames` 能读到二维码
（实测 iframe 内 0 元素）。**把 qrconnect 的 URL 单独开一个页签**就有二维码。

### 坑 3：`frame` 参数在这些命令上帮倒忙

见第 4 步。表单明明在 frame 1 里，`execute_js -p frame=1` 读得到，
`upload_file` / `input_text_by_selector` / `get_element_count` 传 `frame=1` 却报
`ELEMENT_NOT_FOUND`（`count=0`），**不传 frame 才对**。

### 坑 4：发表点击回执 `changed:false`

见第 9 步。**不要因为回执难看就重复点击**（可能重复投稿）。等几秒，看 URL 有没有跳到 `post/list`。

### 坑 5：必须先传视频，表单才存在

见第 5 步。空表单状态下读 `.input-editor` / 短标题 / 封面全是 `undefined`，别误判成「页面坏了」。

## 四、收尾

- 发表成功后浏览器停在 `post/list` 方便人复核；要收就说一声，别默默关窗。
- 登录态留在 profile 里，下次投稿不用再扫码。
