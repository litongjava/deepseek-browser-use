---
name: github-issue-triage
description: 在 GitHub issue 上「回复 + 贴本地截图 + 关闭」的完整套路：登录态判定、只读正文与全部评论、往评论框贴本地图片（评论框没有 file input，要注入 input 再派发 paste）、提交必须用真实鼠标点击、以及发布后的双重核验。适用于 issue 维护、批量答疑、把仓库里的答案回给提问者的任务。
---

> Claude Code 调用名：`dsb-skills:github-issue-triage`。由 `dsb-skills` 插件提供命名空间；源技能名与目录名保持不变。

# GitHub issue：回复、贴本地截图、关闭

这份手册是一次真实任务的复盘：某个仓库的 4 个 open issue 全部「读题 → 回复 → 贴本地配图 → 关闭」。
流程本身不长，但**有两处回执会骗人**（点了返回成功却什么都没发生），照下面的顺序做可以一次跑通。

## 一、站点特征（先认清这四件事）

| 事实 | 影响 |
| --- | --- |
| issue 详情页是 React(Primer) 渲染，正文与评论都在 `main` 里 | 读全文用 `execute_js` 取 `main.innerText`；`extract_markdown` 会把导航与页脚的几十行噪声一起带回来 |
| 评论框就是 `textarea[placeholder="Use Markdown to format your comment"]` | 定位它不需要索引，选择器足够；同页只有一个 |
| 评论框**没有** `input[type=file]`（回形针按钮走 File System Access API） | 贴本地图不能用 `upload_file` 直接指过去，见第三节 |
| 登录态在共享 profile 里、与任务 ID 无关 | 开工先读 `meta[name=user-login]`；没登录就请人在有头浏览器里登录，别猜、别用后台口令 |

登录态一读即知（多行脚本放文件里，用 `--params @文件.json` 传，别在命令行里拼引号）：

```json
{ "body": "() => ({ user: (document.querySelector('meta[name=user-login]')||{}).content, title: document.title, url: location.href })" }
```

`user` 为空 = 没登录。这时 `go_to_url` 到 `https://github.com/login` 再 `bring_to_front`，请人登录一次即可（登录态会留在 profile 里，后续任务不用再登）。

## 二、读一个 issue（正文 + 全部评论）

一次 `execute_js` 取 `main.innerText` 就够：正文、每条评论、作者、`Closed`/`Open` 都在里面。

```json
{ "body": "() => (document.querySelector('main') || {}).innerText || '(no main)'" }
```

- 列表页 `https://github.com/<owner>/<repo>/issues` 上的 `Open N` / `Closed N` 是核验总数最省的一处；
- 正文里的「`…`」是被折叠的评论，需要全文就先点开；
- 目标 issue 多的时候**一个 issue 一个页签**，用 `switch_tab_by_url` 切换（`pageIndex` 会随页签增删漂移，别记索引）。

## 三、往评论里贴一张本地截图（本次最费劲的一环）

`upload_file` 需要 `input[type=file]`，而这个页面**没有**（shadow DOM 里也没有，点回形针按钮也不会生成）。
解法是**自己造一个**，再把文件当成剪贴板内容派发 `paste`：

```text
1) execute_js：造隐藏 input
   body: () => { const i = document.createElement('input'); i.type = 'file'; i.id = 'tmp-file';
                 i.style.position = 'fixed'; i.style.left = '-9999px'; document.body.appendChild(i); return true }

2) upload_file：selector=#tmp-file、path=<服务端本地绝对路径>
   ← 关键：Playwright 会把**真实 File** 附上去，页内 JS 因此拿得到 input.files[0]

3) execute_js：派发 paste
   body: () => { const f = document.getElementById('tmp-file').files[0];
                 const ta = document.querySelector('textarea[placeholder="Use Markdown to format your comment"]');
                 ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length);
                 const dt = new DataTransfer(); dt.items.add(f);
                 ta.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
                 return { name: f.name, size: f.size } }
```

判据：第 3 步回执 `defaultPrevented:true` 说明页面接住了；等 2～5 秒，textarea 里会多出
`<img width=… src="https://github.com/user-attachments/assets/<uuid>">`。多张图就重复第 2、3 步。

**不要**把图片 base64 拆成几段 `execute_js` 塞进页面 —— 单张 150KB 的图就是 20 万字符起，上面这条路一个 token 都不花。

配图优先用**本地已有**的素材（项目文档 `assets/` 下的截图最合适）；本地确实没有、又必须证明某个事实时，
才用 `screenshot` 现截一张服务端本地图（例如把要引用的公开页面截下来），再走同样的三步。

## 四、提交与关闭

```text
mouse_click_by_selector   selector=button[data-variant=primary]   # 主按钮就是 Comment
click_element_by_text     text=Close issue                        # 关闭（Completed）
```

- 评论框里有草稿时，关闭按钮会变成 `Close with comment`，点它 = 发评论 + 关闭，可一步做完；
- 想分开控制就先用主按钮发评论（草稿清空后再点 `Close issue`）。

## 五、坑（每一条都是实际踩过的）

1. **`click_element_by_role` / `click_element_by_text` 点 Comment 会回 `ok:true` + `effective:true` + `changed:true`，但评论根本没发出去**（该按钮在页面很下方，`getBoundingClientRect().y` 能到 2600 以上）。提交一律用 `mouse_click_by_selector`。
2. **编辑器停在 Preview 页签时，点 `Close with comment` 同样毫无反应**。要提交就回 Write 页签，或直接用主按钮。
3. **别用 `innerText` 里出现评论正文当作「已发布」**：预览面板会把草稿渲染成 DOM 文本，看着像发出去了。核验看 `textarea.value` 是否清空 + 时间线是否出现 `private-user-images` 图片（见第六节）。
4. **`new_tab` 报 `SPURIOUS_DISPATCH` 时，页签可能已经建好但没切过去**，后面的输入与上传会全部落到**上一个页签**（我因此把 A issue 的草稿写进了 B issue）。这类「无法判断是否生效」的报错之后，先 `get_tabs` 看 `current` 或读一次 `location.href`；填错的草稿用 `clear_text` 清掉。较新的构建会自己核对页签数：回 `data.effective` / `data.warning` 说「已生效、别重发」，或 `retryable:true` 说「未生效、可安全重发」——按它给的结论走，但仍要用 `data.currentIndex` 复核一次当前页签。
5. **`get_element_value` 只认 `index`**，按选择器读输入框请用 `execute_js` 读 `textarea.value`。
6. 选择器里带 `[`、`]`、引号的参数**一律走 `--params @文件.json`**：Windows 的 PowerShell 会吃掉方括号与逗号，报的却是「缺少参数」这种指不到原因的话。

## 六、发布前核验（一条都别省）

| 看什么 | 期望 |
| --- | --- |
| `textarea.value` 长度 | 0（草稿已提交） |
| 时间线图片 | 出现 `private-user-images` / `camo` 的图（`img` 的 `src` 上判断） |
| 关闭状态 | 出现 `Reopen issue`，且 `State` 为 `Closed` |
| 列表页 | `Open 0` / `Closed N` 与预期一致 |

## 七、回复内容的取材（别凭空写）

- 项目自己的文档能引用就引用（例如知识库文档 `https://tio-boot.com/zh/61_knowledge-base/<章节号>`），
  并且**引用前先在浏览器里打开一次确认不是 404**；
- 「文档里没有、但仓库里有」的事实一并给出来（SQL 脚本位置、依赖坐标与版本、构建命令），这类答案最容易一次解决提问者的问题；
- 结论涉及版本/发布状态（例如「依赖现在能不能直接下载」）时，去**一手来源**核一遍再写（Maven 目录、仓库文件树），
  别沿用 issue 里几年前的旧回复。
