---
name: bilibili-video-upload
description: 使用 dsb 在 B 站创作中心上传本地视频、编辑稿件并核验 BV 号。适用于投稿任务，包含草稿恢复、自动分区、封面上传和提交结果核验。
---

> Claude Code：`dsb-skills:bilibili-video-upload`。

# B 站视频投稿（创作中心）

通用 CLI、登录交接和任务管理见 [主技能](../deepseek-browser-use/SKILL.md)。

## 一、站点特征

| 项 | 值 |
| --- | --- |
| 投稿页 | `https://member.bilibili.com/platform/upload/video/frame` |
| 登录页 | `https://passport.bilibili.com/login`（默认就是二维码登录） |
| 稿件管理（回读用） | `https://member.bilibili.com/platform/upload-manager/article` |
| 视频页 | `https://www.bilibili.com/video/<BV号>/` |
| 技术栈 | 创作中心外壳是 Vue 单页 + **micro-app**；投稿表单本身是 micro-app 里的 `video-up` 应用 |
| 登录态 | 在共享 profile 里（Chromium 那份），**扫码一次长期有效**；事后换任务不用再登 |

**不要去找 iframe。** 投稿表单虽然挂在 micro-app 里,`list_frames` 也只会给出主 frame 加两个无关的
`iframe.html` / `nav/index_new_pc_sync`,`get_browser_state` 在主 frame 里就能读到全部表单元素
（`data.text` 里能看到「发布视频 / 基本设置 / 立即投稿」）。这一页**不需要** `includeFrames`。

> `https://member.bilibili.com/platform/manage/home` 不是稿件管理,它会跳回创作中心首页。要回读稿件
> 请用上面表里的 `upload-manager/article`。

## 二、走一遍（每步给出可直接复制的请求体）

### 1. 起任务并打开投稿页

```bash
dsb --id 3001 start --browser chrome --headful
dsb --id 3001 run go_to_url -p url=https://member.bilibili.com/platform/upload/video/frame
```

有头（`--headful`）**是必须的**:登录要人扫码。

### 2. 登录交给人（二维码登录）

未登录时会被弹到 `https://passport.bilibili.com/login`。二维码就在主文档里,不需要 frame:

- 容器 `.login-scan__qrcode`,里面那张 140×140 的 `<img>` 的 `src` 是 `data:image/png;base64,…`。

**这一页 `get_element_screenshot` 与 `screenshot` 都会超时**(见下面「坑」),所以别指望用截图把二维码
交给远处的人。两条可行路径:

```bash
# 路径 A(推荐):人就在机器旁 —— 把窗口带到最前,让他直接扫
dsb --id 3001 run bring_to_front

# 路径 B(人不在机器旁):把二维码**抠出来存成文件**,再用 /data/** 静态路由给人看
dsb --id 3001 js "() => document.querySelector('.login-scan__qrcode img').src"
# 把回执里的 base64 落成 data/<id>/bili-qr.png,然后给人这个地址:
#   http://localhost:10049/data/<id>/bili-qr.png
```

同时用 `request_human_input` 建一条人工待办（`selector` 指向二维码容器）。**二维码有时间限制**,
快过期时页面会自己换一张,所以路径 B 的图要么及时用、要么重抠一张。

**怎么确认真的登录了**——别看页面变没变,看控制台:

```bash
dsb --id 3001 run get_page_snapshot -p includeConsole=true --json
# 日志里出现「手机端扫码成功，等待确认」→「登录成功, 即将跳转」就是成了
```

### 3. 认清一个陷阱:投稿页会**自动恢复上一份未完成草稿**

打开投稿页时,如果账号里有没发完的草稿,B 站会把它的**标题、简介、分区、标签**直接填进表单,
而**视频位是空的**（所以页面看起来是「点击上传或将视频拖拽到此区域」的干净状态）。

你上传的视频会填进**这份草稿**,于是:

- 稿件卡片（`.task-list-content-item .task-title-text`）显示的是**草稿标题**,不是你文件名;
- 标题输入框里是草稿标题,分区/标签/简介也全是草稿的。

**表单出现后、编辑前备份草稿字段**（标题、分区、标签、简介）。有的版本要先完成第 4 步上传，表单和恢复的草稿才出现。
判断「是不是恢复的草稿」很简单:标题与文件名不一致、简介内容与视频无关,就是。

> 「添加视频」（`.task-list-content-btn`）点下去只会弹出系统文件选择框,**不要用它来新建稿件**——
> 它不产生新任务,而且会弹一个 Playwright 接不住的原生对话框。

### 4. 上传视频

视频输入框是隐藏的,用 `selector` 最稳(元素选择器能吃隐藏 input,`index` 不行):

```bash
# 视频文件在**服务端**能打开的路径(同机时直接给绝对路径;不同机先 POST /playwright/upload)
{"upload_file": {"selector": ".bcc-upload-wrapper input[type=file]",
                 "path": "<视频绝对路径>.mp4", "timeoutMs": 120000}}
```

上传 47MB / 本地同机大约 20 秒。**怎么确认**:回执 `data.consumed=listened`、`filesLength=1`,
随后页面里出现文件名 + 「上传完成」:

```bash
# 一行读出稿件卡片与视频项的状态
dsb --id 3001 js "() => JSON.stringify({tasks:[...document.querySelectorAll('.task-list-content-item')].map(e=>({title:(e.querySelector('.task-title-text')||{}).innerText,status:(e.querySelector('.task-status .text')||{}).innerText})),files:[...document.querySelectorAll('.file-item .title-text')].map(e=>e.getAttribute('title'))})"
```

### 5. 填标题与简介

| 字段 | 选择器 | 备注 |
| --- | --- | --- |
| 标题 | `input[placeholder="请输入稿件标题"]` | 唯一,≤80 字,页面有 `N/80` 计数 |
| 简介 | `.desc-container .ql-editor` | **Quill 的 contenteditable**,`input_text_by_selector` 可以直接覆盖式填进去（回执 `committed=true`）,不用 `click` + `type_text` |

```bash
{"input_text_by_selector": {"selector": "input[placeholder=\"请输入稿件标题\"]", "text": "标题"}}
{"input_text_by_selector": {"selector": ".desc-container .ql-editor", "text": "简介…\n\n项目地址：https://…"}}
```

> 简介里**不要**放 B 站会当成外链的东西之前先想清楚，正常写 URL 没问题;正文里的换行用 `\n` 即可。

### 6. 分区:填完标题**必须回头再看一眼**

这是本页最阴的一个坑:**B 站会按标题自动改分区**。实测标题从草稿的「INMO Air 2开发指南」改成
「使用 deepseek-browser-use 在腾讯云购买服务器」之后,分区被自动从「科技数码」改成了「**vlog**」。

> **改分区不止发生在改标题那一下(2026-09-25 第二次投稿实测)**：那次填完标题回读仍是「科技数码」,
> 等**简介、标签、封面都填完之后**再看,分区已经被自动改成了「人工智能」。
> 所以「填完标题看一眼」是不够的——**提交前最后一次回读才算数**,最后对字段时务必再确认一次分区。
> （顺带:自动改出来的分区不一定差,那次「人工智能」就比草稿里的「科技数码」更贴切;
> 要采纳就采纳,要改回来才动下拉。）

分区是自定义下拉,不是 `<select>`:

```bash
# 1) 打开下拉
{"click_element_by_selector": {"selector": ".video-human-type .select-controller"}}
# 2) 按 title 属性点选项（下拉项在 .drop-list-v2-item，title 就是分区名）
{"click_element_by_selector": {"selector": ".video-human-type .drop-list-v2-item[title='科技数码']"}}
# 3) 回读确认
dsb --id 3001 js "() => document.querySelector('.video-human-type .select-item-cont').innerText"
```

**第 2 步的回执很可能是 `changed:false` + `coveredBy: div.header`(提示说「被遮挡物吃掉了」),
但点击其实生效了。** 这不是你的错觉,也不是选择器问题(见下面「坑 3」)——**以第 3 步回读的值为准**,
不要因为回执不好看就反复重点。

### 7. 标签:一个 input,但有两个同名兄弟

```bash
# 先清掉草稿带过来的标签（每个 chips 右边都有一个 × 图标）
{"click_element_by_selector": {"selector": ".tag-pre-wrp .label-item-v2-container svg.close"}}   # 有几个就点几次
# 再加自己的:填值 + 回车
{"input_text_by_selector": {"selector": ".tag-container .tag-input-wrp input.input-val", "text": "腾讯云"}}
{"send_keys": {"keys": "Enter"}}
```

三个必须知道的点:

1. **真正的输入框是 `.tag-pre-wrp` 的兄弟,不是它的子节点。** 写 `.tag-pre-wrp input.input-val`
   会匹配 **0 个**元素。记住这条路径:`.tag-container .tag-input-wrp input.input-val`。
2. 页面上有**两个**同样 `placeholder="按回车键创建标签"` 的输入框,另一个在**隐藏的弹窗**
   （`bcc-dialog__body`,0×0）里。选择器要对准 `.tag-container` 那一个,否则会去点隐藏的那份。
3. **标签可能未落库且无错误提示**，但不能据一次失败推断关键词被禁止（`DeepSeek` 后续曾成功）。每次加一条并回读标签及输入框，再决定是否修改或重试：

```bash
dsb --id 3001 js "() => JSON.stringify({tags:[...document.querySelectorAll('.tag-pre-wrp .label-item-v2-content')].map(e=>e.innerText.trim()),hint:(document.querySelector('.tag-last-wrp')||{}).innerText})"
```

### 8. 创作声明（必填,加了就不能改）

```bash
{"click_element_by_selector": {"selector": ".creation-statement-container .bcc-select-input-wrap"}}
{"click_element_by_selector": {"selector": ".creation-statement-container .bcc-select-list-wrap li.bcc-option"}}  # 第 1 个 = 内容无需标注
```

选项按顺序是:内容无需标注 / 含AI生成内容 / 含虚构演绎内容 / 内容含营销信息 / 个人观点，仅供参考 / 内容为转载。
根据视频实际内容选择声明；不要统一默认「内容无需标注」。转载授权是另一项声明，不能互相替代。

### 9. 封面（必填,而且系统推荐可能一个都没有）

封面是 `*` 必填项。**不要指望系统推荐封面**:实测这个接口返回空列表
（`.ai-cover-list` 带 `is-empty`,页面显示「这里还什么都没有呢～」),而视频已经「上传完成」——
所以必须自己出一张图。

最稳的做法:用本地 ffmpeg 从**视频本身**截一帧当封面。

```bash
# 先用 2×2 拼图扫一眼哪一段画面适合当封面(一次只看一张图,省 token)
ffmpeg -y -v error -i "<视频>.mp4" -vf "fps=1/146,scale=480:-1,tile=2x2" -frames:v 1 sheet.png
# 选中时间点后按原分辨率截一张
ffmpeg -y -v error -ss 292 -i "<视频>.mp4" -frames:v 1 cover.png
```

然后进封面编辑器上传:

```bash
# 1) 打开「封面制作」对话框
{"click_element_by_selector": {"selector": ".cover .cover-slot .cover-empty"}}
# 2) 上传图片（对话框里的 file input 接受 image/png,image/jpeg）
{"upload_file": {"selector": ".cover-editor-panel-select input[type=file]", "path": "<cover.png 绝对路径>", "timeoutMs": 60000}}
# 3) 点「完成」（注意：它是个 div，不是 <button>）
{"click_element_by_selector": {"selector": ".cover-editor-content-right-bottom .button.submit"}}
# 4) 回读：coverEmpty 变成 false 才算设上
dsb --id 3001 js "() => JSON.stringify({coverEmpty: !!document.querySelector('.cover .cover-empty')})"
```

上传成功后回执里会出现 `blob:` 预览,页面文案变成「双比例同步改动」——**4:3 与 16:9 两个比例由
编辑器自动生成**,不用分别上传。对话框里还有模板/文字/贴纸/滤镜,直接跳过即可。

封面对话框的 file input 是隐藏的，用 `get_element_count` 检查存在，不用等待可见。上传后 PNG 可能被转成 JPG，并生成 4:3 裁剪版；核对 `.cover .cover-empty` 消失且容器有 `archive.biliimg.com` 背景图。

无法读图时，可用 ffmpeg 抽帧再 OCR 辅助选内容；OCR 文本不能证明构图合适，需要时交用户确认。

### 10. 提交前对一遍,再点「立即投稿」

```bash
dsb --id 3001 js "() => JSON.stringify({title:(document.querySelector('input[placeholder=\"请输入稿件标题\"]')||{}).value,zone:(document.querySelector('.video-human-type .select-item-cont')||{}).innerText,tags:[...document.querySelectorAll('.tag-pre-wrp .label-item-v2-content')].map(e=>e.innerText.trim()),statement:(document.querySelector('.bcc-select-input-inner')||{}).value,coverEmpty:!!document.querySelector('.cover .cover-empty'),files:[...document.querySelectorAll('.file-item .title-text')].map(e=>e.getAttribute('title'))})"
```

立即投稿是 `span.submit-add`（`存草稿` 是 `span.submit-draft`）:

```bash
{"click_element_by_selector": {"selector": ".submit-add"}}
```

**点完不要重试。** 这一下会让整页重建,回执很可能是 `changed:false` / 文本长度掉到 0 / 走了
`ACTION_UNCERTAIN`——那都不代表没提交。正确做法是先等十几秒,再读页面文案与稿件管理页:

```bash
# 成不成看这句文案
dsb --id 3001 js "() => document.body.innerText.replace(/\s+/g,' ').slice(0,200)"
# 「稿件投递成功 … 立即加热 查看进度 再投一个」= 提交成功

# 再到稿件管理页回读（拿 BV 号）
dsb --id 3001 run go_to_url -p url=https://member.bilibili.com/platform/upload-manager/article
dsb --id 3001 js "() => JSON.stringify([...document.querySelectorAll('a[href*=\"/video/BV\"]')].map(a=>({href:a.getAttribute('href'),text:a.innerText.trim()})))"
```

稿件管理页列表第一行就是刚投的稿件:标题、时长、时间,链接形如
`https://www.bilibili.com/video/BV…`。**注意统计数字可能全是 0、总计数也可能还没算上它**,
那只是审核/转码还没走完,不代表投稿失败——以「稿件投递成功」文案 + 列表里出现该稿件为准。

## 三、这个页面特有的坑

### 坑 1：截图失败

投稿页曾出现整页截图超时。发生熔断时用 DOM 核验业务结果，不能据此断言视觉正常；必要时请用户看窗口。截图诊断见 [测试与证据分册](../deepseek-browser-use/references/testing-evidence.md)，二维码替代方式见第 2 步。

### 坑 2：页面重建时动作结果未知

打开封面对话框或投稿可能触发整页重建，使 `changed:false`、`coveredBy` 等探针失真。`probeTrustworthy:false` / `observationComplete:false` 时等目标内容恢复并回读，不重复点击。即使探针可信，也要以稿件记录核验投稿结果。

`wait_for_stable` 若报「读不到内容指纹」，改用有界的 `wait_for_function` 等待目标字段或稿件状态。

### 坑 3:批量动作别贪大

一次会话里把「填值 + 回车」拼成 12 条命令的批量,跑在中途页面整页重建,客户端侧直接看不到结果。
**小步走**:一个字段一次调用、加完回读;批量的条目越多,一次页面重渲染吞掉的越多。
长批次用 `dsb --id <id> batch cmds.json --async --wait`,别让它撞 HTTP 超时。

## 四、收尾

- 视频已发布的话,任务可以 `close` 掉;登录态留在 profile 里,下次投稿不用再扫码。
- 稿件投出去之后**在浏览器里停一会儿**（比如停在稿件管理页或视频页),方便人自己复核;
  要收就说一声,别默默把窗口关掉。
