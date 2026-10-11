---
name: kuaishou-research
description: 用 deepseek-browser-use 在快手网页版（kuaishou.com）做用户需求挖掘、评论采集与舆情分析的实操手册。写透实测坑位：登录态托管在 profile、搜索结果卡片点不进去（只能靠 hover 预览取 clientCacheKey 反推视频 ID）、评论容器自身不滚动而是一个祖先可滚、数字 photoId 也能当 short-video URL、自动连播会在采集前把页面切走、截图熔断（视频自动播放）下的纯文本回退。
whenToUse: 需要在快手（kuaishou.com）采集视频评论、挖掘产品需求、做用户痛点/舆情分析时使用。
---

> Claude Code 调用名：`dsb-skills:kuaishou-research`。由 `dsb-skills` 插件提供命名空间；源技能名与目录名保持不变。

# 快手网页版需求调研实操手册

一次真实生产级任务（2026-10-09）的完整沉淀：用 `dsb` 驱动 Chrome，在快手网页版完成从扫码登录、定位高互动视频、到滚动穿透评论区抓取 237 条真实评论并做需求聚类。

> **统一命令行规范**：一律通过 `dsb` 执行（装在 `PATH` 上）。
> **跨调用复用同一个数字 `--id`**；本手册示例用 `--id 20261009`。

---

## 零、开发中遇到的问题（按踩坑顺序）

这一节是「当时卡在哪、怎么定位、最后怎么解决」的完整复盘，比结论更值钱。

### 问题 1：登录弹窗的二维码元素截不了图
- **现象**：`get_element_screenshot -p selector='img[src^="data:image/png;base64"]'` 超时 8000ms，`request_human_input` 也带上 ``imageError``。
- **误判**：以为选择器错了，反复改 selector。
- **真相**：该二维码 `<img>` 是 base64 内联、尺寸 170×170，元素截图超时是 Playwright 的老问题；且同一选择器匹配到 7 个元素（`get_element_screenshot` **不支持 ``nth`` 参数**，多匹配时取第一个可接收事件的，未必是二维码）。
- **解决**：**不截元素图**。直接把有头浏览器 `bring_to_front`，用 `request_human_input` 让用户在可见窗口里扫码；要程序化确认元素存在时，用 `dsb js` 读 `getBoundingClientRect()` 拿坐标与尺寸。

### 问题 2（最主要）：搜索结果卡片点不开，导航不触发
- **现象**：在 `/search/video?searchKey=...` 上点卡片，`click_element_by_index`、`mouse_click`、`double_click_element_by_index`、对 `.cover` 直接 `el.click()` 全都只弹内嵌 hover 预览，URL 不变。
- **定位过程（值得复用）**：
  1. 先确认结果卡有没有 `<a>`：`a[href*="/short-video/"]` 数量为 0 → 说明是 JS 路由，不是链接；
  2. 挂 `history.pushState` 钩子看点击是否触发路由 → `__navHook` 始终为空，确认**根本没触发导航**；
  3. `document.elementFromPoint(x,y)` 看点上到底命中了谁 → 命中的是 ``.video-interact-panel``/``mask``，说明点在了预览遮罩上。
- **解决**：放弃「点开」。改为 **hover 卡片 → 读新出现的 `<video>` 的 ``clientCacheKey`` → 反推视频 ID → 直接 `go_to_url`**（见坑 1）。或者干脆换**话题页 / 发现页**，那里是真 `<a href>`。

### 问题 3：评论加载不动，评论数卡死在个位数
- **现象**：`.comment-container` 的 `scrollHeight === clientHeight`（1376=1376），对它赋值 ``scrollTop`` 完全无效。
- **定位**：把 ``elementFromPoint`` 和祖先链打出来，发现**可滚的是评论容器的某个祖先**（``parentElement`` 的 `scrollHeight 1592 / clientHeight 975`）。
- **解决**：向上找 6 层祖先，取「可滚幅度最大」的那个当滚动对象，并 `dispatchEvent(new Event('scroll',{bubbles:true}))`（见坑 2）。

### 问题 4：采到的评论和视频对不上（自动连播偷换页面）
- **现象**：刚抓完的评论主题完全是另一个视频（抓「搅拌机」视频得到「教师土地」评论）。
- **定位**：对比 `document.title`，发现视频播完后页面自动切到了下一条推荐。
- **解决**：进详情页后**立刻 `video.pause()`**，采集循环里反复 pause，并在落盘前校验 `document.title`（见坑 3）。

### 问题 5：后台脚本与前台命令抢同一个任务（``COMMAND_TIMEOUT``）
- **现象**：跑批量采集 python 脚本的同时下别的命令，报 `同一个任务上已有命令正在执行，等满 5000 毫秒仍未轮到`。
- **根因**：**同一个 `--id` 上的命令是串行的**，后台脚本持续占用该任务。
- **解决**：批处理脚本必须顺序执行、不要与前台命令并发；或给不同任务用不同 `--id`。

### 问题 6：话题页偶发空结果（假故障）
- **现象**：`/hashtag/效率工具` 打开后 body 只有 423 字符，正文「没有找到合适结果」，但该话题 3916 万播放。
- **解决**：`reload` 重试；仍不行就换发现页/搜索页取 ID（见坑 6）。

### 问题 7：评论时间戳格式比预想的多
- **现象**：第一版正则只覆盖「分钟/小时/天前」，提取数为 0；实际评论大量是「**月前 / 年前**」。
- **解决**：正则补齐 `刚刚|N秒前|N分钟前|N小时前|N天前|N周前|N个月前|N年前`。

---

## 一、站点核心特征

| 维度 | 特征 |
| :--- | :--- |
| 入口 | `https://www.kuaishou.com`；未登录首页只给登录弹窗 |
| 前端 | Vue SPA + 虚拟列表；导航走 router，**文章列表极少用 `<a href>`** |
| 登录态 | 托管在 `.dsb-backend/profile`，扫码一次长期有效；登录后侧栏出现「<昵称> 我的」 |
| 安全机制 | 视频自动播放导致 Playwright 等待渲染静止超时 → 截图熔断（`circuit-open`） |
| 内容页 | 视频详情 `https://www.kuaishou.com/short-video/<id>`，评论区在 `.comment-container` |
| 话题页 | `https://www.kuaishou.com/hashtag/<urlencode>`，卡片带真实 `<a href="/short-video/...">`（但偶发「没有找到合适结果」） |
| 发现页 | `https://www.kuaishou.com/?isHome=1`，卡片带真实 `<a>`，可用 |

---

## 二、避坑指南（实测高频坑）

### 坑 1：搜索结果卡片点不开（只弹 hover 预览，URL 不变）
- **现象**：在 `/search/video?searchKey=...` 上，`mouse_click` 卡片只触发内嵌 hover 预览，``urlAfter`` 不变、`history.pushState` 不被调用；`click_element_by_index` 点 `.cover` 同样不跳转。
- **根因**：搜索页结果卡是 hover 预览交互，真正的路由跳转入口不暴露给脚本点击。
- **解法**：**不要试图点开搜索卡片**。改用「hover 卡片 → 读取新出现的 `<video>` 的 ``clientCacheKey`` → 反推视频 ID」：
  ```js
  () => [...new Set(Array.from(document.querySelectorAll('video'))
        .map(v => (v.src || v.currentSrc || '').match(/clientCacheKey=([^&_]+)/)?.[1])
        .filter(Boolean))]
  ```
  ``clientCacheKey`` 去掉下划线后缀（`_xxxx`）后就是 `/short-video/<id>` 的 ID，可直接 `go_to_url` 打开。
- **更稳的取 ID 方式**：优先用**话题页**或**发现页**的 `<a href="/short-video/...">`，那是真的链接。

### 坑 2：评论容器自身不滚动，真正可滚的是它的祖先
- **现象**：`document.querySelector('.comment-container').scrollHeight === clientHeight`，对它赋值 ``scrollTop`` 无效，评论数卡在首屏。
- **解法**：向上找 6 层祖先，取 `scrollHeight - clientHeight` 最大的那个当滚动对象，同时 `dispatchEvent(new Event('scroll',{bubbles:true}))`：
  ```js
  let el = document.querySelector('.comment-container'), chain = [];
  for (let i = 0; i < 6 && el; i++) { chain.push(el); el = el.parentElement; }
  const scroller = chain.filter(e => e.scrollHeight > e.clientHeight + 80)
    .sort((a, b) => (b.scrollHeight - b.clientHeight) - (a.scrollHeight - a.clientHeight))[0];
  ```

### 坑 3：自动连播会在采集前把页面切走
- **现象**：`go_to_url` 到目标视频后，若视频播完，页面自动切到下一条推荐，`document.title` 变成另一个视频，采到的评论对不上。
- **解法**：进入详情页后**立刻暂停视频**，并在采集循环里反复 pause：
  ```js
  const v = document.querySelector('video'); if (v) { v.pause(); v.loop = true; v.autoplay = false; }
  ```
  采完立刻校验 `document.title` 是否仍匹配目标视频标题，不匹配就重来。连播开关是 `[role=switch]`，但详情页有时取不到，直接 pause video 最可靠。

### 坑 4：数字 photoId 也能当 short-video ID 用
- 视频源 URL 里有两种 ID：`photo-video-mz/<一串19位数字>_...` 与 `clientCacheKey=<base62>`。
- **两种都能拼成 `https://www.kuaishou.com/short-video/<id>` 打开**（数字 ID 也可解析），但**数字 ID 有时会解析成另一条视频**，优先用 ``clientCacheKey``。

### 坑 5：截图熔断（视频自动播放）——纯文本回退
- **现象**：打开视频页报 ``CAPTURE_FAILED``，随后 `capture: stage: circuit-open`。
- **规范**：**不要依赖截图判断页面**，一律走 `dsb state --text-only` 或 `dsb js` 抓 DOM。熔断只影响截图留档，导航/JS/点击完全不受影响。
- 采集评论的脚本本身不需要截图，忽略熔断日志继续跑即可。

### 坑 6：话题页偶发「没有找到合适结果」
- **现象**：`/hashtag/<词>` 打开后 body 只有 423 字符、`a[href*="/short-video/"]` 为 0，正文写「没有找到合适结果」，但该话题明明有千万播放。
- **解法**：`reload` 重试一次，或换用 `?isHome=1` 发现页 / 搜索页取 ID，不要死磕话题页。

### 坑 7：评论行的时间戳格式多样
- 时间戳可能是 `刚刚 / N秒前 / N分钟前 / N小时前 / N天前 / N周前 / N个月前 / N年前`。
- 结构化提取时正则要覆盖「月前 / 年前」，否则会漏掉大量老评论：
  ```js
  const TS = /(刚刚|(\d+)\s*(秒|分钟|小时|天|周|个?月|年)前)/;
  ```

---

## 三、标准采集流水线（复制即用）

### 步骤 1：起浏览器并登录
```bash
dsb --id 20261009 start --browser chrome --headful
dsb --id 20261009 run go_to_url -p url='https://www.kuaishou.com'
```
登录按钮为 `.sidebar-login-button`；点开后弹出二维码弹窗，用 `request_human_input` 提示用户扫码；登录成功后 `document.querySelector('.sidebar-login-button')` 变 null，cookie 含 ``userId``。

### 步骤 2：拿目标视频 ID（二选一）
- 话题页 / 发现页：直接抓 `<a href="/short-video/...">`；
- 搜索页：hover 卡片后读 ``video`` 的 ```clientCacheKey```（见坑 1）。

### 步骤 3：进详情页并冻结播放
```bash
dsb --id 20261009 run go_to_url -p url='https://www.kuaishou.com/short-video/<ID>'
dsb --id 20261009 js "() => { const v=document.querySelector('video'); if(v){v.pause();v.loop=true;} return document.title; }"
```

### 步骤 4：滚动穿透 + 结构化提取评论
见本技能目录下的 `scripts/harvest_comments.js` 模板。先按本次加载的技能目录解析文件绝对路径，再传给 dsb；不要假定当前工作目录是仓库根目录：
```bash
dsb --id 20261009 js '@<本技能目录>/scripts/harvest_comments.js' --retry-on-spurious --select data.result --out out.json
```
脚本逻辑：先向上找可滚动祖先、滚两轮（含回顶），再用「时间戳行 → 下一行内容」配对提取，跳过 ``取消/发送/查看更多回复`` 与纯数字点赞数。

### 步骤 5：校验 title 再落盘
```bash
dsb --id 20261009 js "() => document.title"
dsb --id 20261009 js @harvest_comments.js --retry-on-spurious --select data.result --out out.json
```

---

## 四、怎么确认这一步真的成了

- 登录成功：`.sidebar-login-button` 消失 + cookie 含 ``userId``；
- 视频对齐：`document.title` 与话题页卡片文案一致；
- 评论加载成功：提取数 > 20，且 `scrollerFound: true`；
- 全流程验证：`document.querySelectorAll('.comment-item').length` 随滚动增大。

## 五、注意事项
- **同一 `--id` 上的命令是串行的**：不要开后台脚本与前台命令抢同一个任务（会 ``COMMAND_TIMEOUT``）。
- 评论区噪声大（灌水/引流/主播广告），聚类前先按关键词粗筛。
