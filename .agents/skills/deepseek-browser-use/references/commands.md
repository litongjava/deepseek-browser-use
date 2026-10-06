# 命令全表（参数与返回字段）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读：这里逐条列出方法名、参数与返回字段，写法与 SKILL.md 完全一致，只是不再占入口文件的篇幅。

下面的名字就是 `method` 的取值，也是 `params` 里的参数名。

### 实例生命周期

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `start` | `id`(可选), `headless`(bool，默认 `true`), `browser`(可选，见下), `networkRecording`(bool，可选) | 开始一个任务；返回 `data.id`，以及 `data.browser`（这次用的浏览器与 profile，见 `browsers.md`）。传 `id` 就把它当任务 ID。第一个任务会把共享的浏览器拉起来，之后的任务只是各领自己的页签。`networkRecording: false` = 这个任务**不记录请求/响应**（等价于把 `browser.network.record` 设成 `off`）：代价是 `get_requests` / `get_response_body` / `wait_for_response` 拿不到数据（回执会明说「没在记」），换来的是**彻底没有**那一族 `response@`/`request@` 伪故障 —— 踩到噪声风暴又不需要网络数据时，这是最干净的解法 |
| `close` | `id` | 关掉**这个任务自己的页签**，别的任务不受影响；**最后一个任务关闭时**浏览器才一起退出（登录态留在 profile 里，下次还在） |

### 导航与页面信息

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `navigate` | `id`, `url` | 返回 `data.status` |
| `go_to_url` | `id`, `url` | 与 `navigate` 等价。**打不开的地址会报 `errorCode:NETWORK_ERROR`**，并带上 `data.netError`（Chrome 的错误码，如 `ERR_NETWORK_CHANGED` / `ERR_CONNECTION_REFUSED`）与可重试建议 —— 这时页面是 **Chrome 自己的网络错误页**，上面没有任何内容可读，别去查选择器，先看本机代理 / VPN（见 `pitfalls.md` 第 74 条） |
| `get_browser_state` | `id`, `highlight`, `viewportExpansion`, `includeElements`, `maxElements`, `includeFrames`, `strictSnapshot` | 见 `reading-pages.md`。`strictSnapshot`(bool，默认 false) 决定「这份索引什么时候作废」：默认只作废**结构变更**（读取期间增删了元素）与逐元素重校验失败的快照，实时行情这类只有文字/属性在变的页面索引仍然可用；传 `true` 恢复旧的「读取期间任何 DOM 变更即作废」判据 |
| `list_frames` | `id`, `refresh`(bool，默认 true) | 列出页面上的**全部 frame**（含跨域 iframe）：`data.frames[]`（`index`/`url`/`name`/`isMain`/`parentIndex`/`depth`/`elementCount`/`indexRange`；读不出来的 frame 另有 `readError`）。`index 0` 固定是主 frame，其余按 frame 树深度优先编号。**顶层读不到元素时先看它**，见 `reading-pages.md`「跨域 iframe」 |
| `get_page_snapshot` | `id`, `includeConsole`(bool), `includeRequests`(bool), `requestFilter` | 一次拿到页面状态：`data.url`、`data.title`、`data.tabs`、`data.dialog`、`data.loading`；`includeConsole=true` 再带 `data.logs`/`data.errors`，`includeRequests=true` 再带 `data.requests`（可用 `requestFilter` 按 URL 子串过滤）。替代六次单独调用，**不含 DOM 快照文本** |
| `diff_dom_text` | `id`, `highlight`, `viewportExpansion` | 重新执行一次 buildDomTree，与上一次快照按行做差集：`data.changed`、`data.added`、`data.removed`（各最多 200 行）、`data.first`。判断「页面到底动没动」比重读整页省 token。**不产生新的截图/文本文件** |
| `get_interactive_map` | `id` | 按当前快照的 xpath 回查全部元素，返回 `data.elements`：`index`、`tag`、`xpath`、`id`、`className`、`href`、`name`、`text`，以及 **`hasListeners`**（这个元素有没有挂事件监听器：`true`/`false`/`null`=未知）与 **`listeners`**（事件名数组）。带 frame 的快照里每项还有 `frameIndex`/`frameUrl`。补上快照里没有的 `id`/`class`/`href`；需要先有快照 |
| `get_form_state` | `id`, `selector`(可选，默认整页), `includeHidden`(bool，默认 false), `max`(可选，默认 200) | 一次读回整张表单：`data.fields`（每项含 `label`/`id`/`name`/`type`/`value`/`checked`/`disabled`/`readOnly`/`required`/`visible`/`invalid`/`error`/`placeholder`）、`data.count`、`data.errorCount`、`data.errors`（`label`+`error` 清单）。**密码字段的值一律回 `[redacted]`** |
| `go_back` | `id` | 返回 `data.status`、`data.url`；无历史时 `msg=无法后退：没有可用历史记录` |
| `go_forward` | `id` | 同上；`about:blank` 这类没有 HTTP 响应的页面 `status` 为 0 |
| `reload` | `id` | 返回 `data.status` |
| `get_url` | `id` | 返回 `data.url` |
| `get_title` | `id` | 返回 `data.title` |
| `wait` | `id`, `seconds`（必填，秒） | 固定等待；缺 `seconds` 得到 `wait 失败：缺少参数 seconds` |

`get_form_state` 是用来「填完一屏后对一遍」的：

- 一次调用代替十几个 `get_element_value`/`is_visible`/`get_element_attribute`，尤其适合提交前的自检。
- `data.errors` 直接给「哪个字段、错在哪」，比去猜 `.ant-form-item-explain-error` 之类的站点类名稳。
- `invalid` 来自 CSS 伪类与 `aria-invalid`，`error` 取的是该字段所在的表单行里的错误文本。
- **注意「值在 DOM 里但不在框架模型里」的坑**：`value` 读的是实时 DOM 属性，值写进去了就会显示出来；如果预览/提交仍然说「不能为空」，说明写值的方式没进框架的 model（见 `protocol.md`「动作的三种执行方式（mode）与降级」），要用 `input_text_by_selector` 重填，而不是继续加值。
- 隐藏控件（`type=hidden`、`display:none`）默认不返回，`includeHidden: true` 才带上——真实站点上的 file input 常常就是这类。

### 元素交互（按索引）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `click_element_by_index` | `id`, `index`, `mode`(可选), `timeoutMs`(可选) | 单击；返回点击回执（见下） |
| `double_click_element_by_index` | `id`, `index`, `mode`(可选) | 双击；返回点击回执 |
| `hover_element_by_index` | `id`, `index` | 悬停 |
| `focus_element_by_index` | `id`, `index` | 聚焦 |
| `check_element_by_index` | `id`, `index`, `mode`(可选) | 勾选复选框/单选框 |
| `uncheck_element_by_index` | `id`, `index`, `mode`(可选) | 取消勾选 |
| `type_text` | `id`, `index`, `text` | 逐字输入，**不清空**原有内容 |
| `input_text` | `id`, `index`, `text`, `mode`(可选) | 覆盖式填充（等价 `fill`，会清空）；`text` 必填，**清空请用 `clear_text`** |
| `drag_element_by_index` | `id`, `index`, `targetIndex` | 把第 index 个元素拖到第 targetIndex 个元素 |
| `upload_file` | `id`, `path`, `index` 或 `selector`(二选一), `nth`(可选), `timeoutMs`(可选), `frame`(可选) | `path` 是**服务器本地路径**：绝对路径或按服务端暂存目录解析的相对路径（见下面的「上传文件」）。**上传完会回读校验**，见下。`selector` 命中多个 file input 时回执报 `data.matched` / `data.chosenIndex` / `data.visibleMatched`（file input 基本都是隐藏的，所以**不做可见性挑选**，按文档顺序取第一个）——要精确指定就传 `nth`（0 基） |
| `send_keys` | `id`, `keys` | `keys` 收**两种形态**：① **按键**——单个键名（`Enter`、`Tab`、`ArrowDown`、`F5`）或「修饰键 + 一个键」的组合（`Control+A`、`Shift+Enter`、`Control+Shift+T`）；② **整段文本**（`CRCL`、`hello world`、中文）。回执里 `data.mode` 是 `press` 还是 `type` 直接告诉你走了哪条路，另有 `data.keys` / `data.text` 与 `data.focused`。整段文本走逐字符打字（会触发 `keydown/keypress/input`，受控输入框也认），所以「隐藏输入框没选择器、又不想先取快照拿索引」时可以直接用它 |
| `key_down` | `id`, `keys` | 按住不放（配合 `key_up`） |
| `key_up` | `id`, `keys` | 松开按键 |
| `get_dropdown_options` | `id`, `index` | 返回 `data.options`，选项文本数组 |
| `select_dropdown_option` | `id`, `index`, `text` | 按**选项文本**（label）匹配 |
| `scroll` | `id`, `down`(bool), `numPages`(int), `index`(可选) | 不传 `index` 时按 `PageDown`/`PageUp` 翻页；传 `index` 时对第 index 个元素 `scrollBy` |
| `scroll_to_text` | `id`, `text` | 按 `text=` 定位并滚动到可见；找不到会等满 30 秒后失败 |

**点击回执**：`click_element_by_index`、`double_click_element_by_index`、`click_element_by_selector`、`click_element_by_text`、`click_element_by_role`、`hover_and_click` 都会在 `data` 里返回点击前后的状态对比：

| 字段 | 说明 |
| --- | --- |
| `data.urlBefore` / `data.urlAfter` | 点击前后的 URL |
| `data.tabCountBefore` / `data.tabCountAfter` | 点击前后的页签数（变多说明弹出了新页签） |
| `data.textLengthBefore` / `data.textLengthAfter` | 点击前后 `document.body.innerText` 的长度（粗略反映内容变化） |
| `data.changed` | URL、页签数或页面指纹发生变化则为 `true`；指纹涵盖正文、表单实时值和 DOM 结构状态 |
| `data.hint` | `changed=false` 时出现：动作已执行，但观察窗口内尚未发现变化；不代表点击失败 |
| `data.tag` / `data.text` / `data.outerHtml` | **只有** `click_element_by_text`、`click_element_by_role`、`hover_and_click` 返回：真正命中的元素是什么 |
| `data.seq` / `data.screenshot` / `data.screenshot_path` | 这一步的自动截图，见 `protocol.md` |

`ok=true` 只代表动作没抛异常，**不代表点中了东西**：实测点悬浮菜单时文本命中的是纯文本容器，方法返回成功但页面毫无变化。`data.changed` 仅表示观察到变化，不证明业务操作成功；为 false 时应等待目标条件。

#### 上传文件（`upload_file` 与 `POST /playwright/upload`）

`upload_file` 的 `path` 是**服务端**能打开的路径，不是 URL。`index` 与 `selector` 传一个即可，**优先用 `selector`**：真实站点上的 file input 基本都是隐藏的（`display:none` 或 `visibility:hidden`），既没有操作索引，也要先想办法把它显出来才能用索引定位——用选择器就不需要这一套。

```bash
# 把 file input 的选择器喂给它（不需要元素可见，也不需要索引）
{"id":"1001","method":"upload_file","params":{"selector":"#form_item_imageAttJson","path":"图样.jpg"}}
```

##### 命中多个 file input 时：先看 `matched`，要精确指定就传 `nth`

真实站点上「同名的隐藏文件框有两份」是常态（X 的投稿页就是：发推弹窗里一个、后面的内联编辑器里一个，**两个都是 0×0**）。`upload_file` **不做可见性挑选** —— file input 基本都是隐藏的，「挑可见的」会把唯一正确的那个排除掉 —— 所以它按**文档顺序**取第一个，并把话说明白：

| 字段 | 含义 |
| --- | --- |
| `data.matched` | 选择器一共命中几个 file input。**大于 1 就说明这次是「猜的」**，值得确认一下 |
| `data.chosenIndex` | 这次用的是第几个（0 基） |
| `data.visibleMatched` | 命中的前 20 个里有几个是可见的（file input 正常情况下是 0） |
| `data.selectorNote` | 一句话说清「怎么挑的、要精确指定该怎么办」 |
| `data.explicitNth` | 传了 `nth` 时为 `true`（此时不做任何挑选，就是你要的那个） |

```bash
# 命中两个时，明确要第二个
{"id":"1001","method":"upload_file","params":{"selector":"input[data-testid='fileInput']","nth":1,"path":"图样.jpg"}}
```

选择器**一个都没匹配到**时会当场以 `ELEMENT_NOT_FOUND` 失败，不会等满可操作性超时再报一句「超时」—— 那会让调用方以为「元素在、只是不可点」，方向全错。

**客户端-服务器模式**（智能体在客户端、浏览器在服务端）下，客户端本地文件服务端读不到，先把文件 POST 到暂存接口，再用回执里的路径：

```shell
# 1. 上传到服务端暂存区（就是 POST /playwright/upload 的封装）
.\client\dsb.cmd --port 10049 upload 图样.jpg
# 回执：{"ok":true,"data":{"filename":"图样.jpg","path":"<服务端暂存目录>/图样.jpg",
#                        "relativePath":"图样.jpg","size":18363,"sha256":"...","existed":false}}
```

```json
// 2. 把 path（或 relativePath）回填给 upload_file
{"id":"1001","method":"upload_file","params":{"selector":"#form_item_imageAttJson","path":"图样.jpg"}}
```

- 暂存区的底层接口是 `POST /playwright/upload`（另有 `GET` 列出、`DELETE ?name=…` 删除）；日常用 `dsb upload` 就够，不必自己拼 multipart。

- 文件字段名默认 `file`，可用 `?field=xxx` 改；底层也接受「原始字节」与「JSON + base64」两种非 multipart 写法（`dsb upload` 已替你选好了）。文件名会被清洗（只留基本名、去掉路径分隔符与控制字符、保留中文），并且**只能落在暂存目录里**，`../` 这类路径会被拒绝。
- 回执字段：`data.filename`、`data.path`、`data.relativePath`、`data.size`、`data.sha256`（客户端可据此核对是否传对了文件）、`data.target`（`index=3` 或 `selector=...`）。
- 单文件上限默认 64MB（`browser.upload.maxBytes`），同名默认覆盖（`browser.upload.overwrite=false` 则自动改名 `a-1.jpg`）。暂存目录默认 `<启动目录>/upload`，`start` 的返回里能看到实际路径。
- 文件不存在时错误信息会直接告诉你去 `POST /playwright/upload`，不要再去猜路径。
- 上传动作也会写进追踪日志的 `uploads.log`（只记元数据，不记内容）。
- file input 在跨域 iframe 里时传 `frame`（序号见 `list_frames`，或 URL/name 子串）。

##### 上传回执会回读校验：`mode:"native"` **不等于**「页面处理了它」

`setInputFiles` 的语义只是「把文件放进 input」，**页面有没有消费完全是另一回事**。实测企业微信后台的营业执照上传：`upload_file` 回 `ok:true`，页面却一直停在「请上传工商营业执照」——根因是那个 `<input class="uploadInput">` **没有挂任何事件监听器**（Vue 2 的 `` _vei `` 是空的），`setInputFiles` 把 `input.files` 设好了，但派发的 `change` 到不了框架的 handler，组件的 `upload()` 从来没被调用。

这类「报成功但没生效」比报错危险得多——它会让人去错的方向排查（换选择器、换文件、怀疑上传接口）。所以回执里现在会一起回来：

| 字段 | 含义 |
| --- | --- |
| `data.filesLength` | input 里现在有几个文件（正常应当是 1）。**回读看到 0 别慌**：SPA 组件收下文件后会把 input 重置，见下面 `readbackNote` |
| `data.listeners` | `{vue2, vue3, react, inline, jquery, events}` —— 这个 input 挂了哪些事件；全为 `false` 就是「这个 input 没人监听」 |
| `data.hasListeners` | 三态：`true` 有 / `false` 确认没有 / `null` 未知（见下面「hasListeners 的三态」） |
| `data.listenerDetection` | `cdp`（浏览器自己报的清单，可信）/ `heuristic`（只探到框架痕迹） |
| `data.consumed` | 启发式结论：`listened` / `noListener` / `unknown` |
| `data.hint` | `noListener` / `unknown` 时给一句**可直接操作**的提示 |
| `data.changed` / `data.changeStatus` | 上传前后页面有没有变化（探针取在 file input 所在的那个 frame 里） |
| `data.effective` | `consumed=noListener` 且页面没变化时为 `false` —— 这次上传**没生效** |
| `data.elementGone` / `data.readbackNote` | 回读时元素已不在页面上（框架把它换掉了）。**这不代表上传失败**，`readbackNote` 会说明原因 |

> **回读看不到 input ≠ 上传失败。** 实测企业微信的授权书上传：组件收下文件后把原 input 换掉，回读时那个节点
> 已经不在页面上。老版本回读走 `Locator`，节点一脱离文档就要等满默认 30 秒超时，于是 `ok:true` 的操作被写成
> `readbackError: Timeout 30000ms exceeded.` + `consumed: unknown` —— 看着像失败，一重传就可能传两份。
> 现在回读**现场重新解析 DOM**（不走 `Locator`，不会再等），并把原因写进 `data.readbackNote`。
> **判断上传成没成，以页面为准**（文件名出现、`data.changed`、下一步的 `expect` 断言），不要只看 `consumed`。

看到 `data.consumed: "noListener"`（或 `hasListeners: false`）就**不要再去调选择器**了，正解是：

1. 用 `get_element_listeners` 复核一次（`selector` 或 `index` 都行）；
2. 改用**组件方法直调**：`execute_js` 里拿到页面上的 Vue 实例，直接调它的 `upload()` / `emitChange()`（企业微信那个 `ImageUploader` 就是这么绕过去的，见 `.agents/skills/wecom-register-certify`）；
3. 或先点它的可见父元素 / 触发框架自己的入口，再上传。

### 读取元素信息与状态（按索引，或按选择器）

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `get_element_text` | `id`, `index` 或 `selector`(二选一), `canvasOnly`(可选), `frame`(可选) | `data.text`（innerText）+ `data.target`（这次定位到的是谁）。**只认真正的文字节点**：图表 / 地图 / 看板上的刻度与浮标是画在 `canvas` 上的像素，这里必然是空。所以 `canvasOnly:true` 时，读不到文字会追加 `data.canvasOnly`（这里是不是基本只有 canvas）与 `data.hint`（给出路：像素手段或读旁边的真 DOM 文字）。canvas 通常**不进快照**（不可交互 → 没索引），要诊断它只能用 `selector` |
| `get_element_html` | `id`, `index` | `data.html`（innerHTML） |
| `get_element_value` | `id`, `index` | `data.value`（输入框的 value） |
| `get_element_attribute` | `id`, `index`, `name` | `data.value`（属性值，可为 null） |
| `get_element_listeners` | `id`, `index` 或 `selector`(二选一), `frame`(可选) | 这个元素挂了哪些事件监听器：`data.found`、`data.tag`/`className`/`type`、`data.vue2`/`vue3`/`react`/`inline`/`jquery`、`data.listeners`（事件名数组）、`data.hasListeners`、`data.detection`（`cdp` = 浏览器自己报的清单，可信；`heuristic` = 只探到框架痕迹）、`data.note`。**「有没有挂事件」是 SPA 自动化的基础诊断信息**，凡「设了值/派发了事件但页面没反应」先查它 |
| `get_element_count` | `id`, `selector`, `frame`(可选) | `data.count`（CSS 选择器匹配数量，不需要索引） |
| `get_element_box` | `id`, `index` | `data.x/y/width/height`；元素不可见时失败 |
| `is_visible` | `id`, `index` 或 `selector`(二选一), `frame`(可选) | `data.visible` + `data.target`。**「这个按钮现在能不能点」正是不想读整页快照时才问的**，而元素有没有索引取决于它在不在视口内 —— 所以这一族与 `get_element_text` 同形，也能只按 `selector` 问 |
| `is_enabled` | `id`, `index` 或 `selector`(二选一), `frame`(可选) | `data.enabled` + `data.target` |
| `is_checked` | `id`, `index` 或 `selector`(二选一), `frame`(可选) | `data.checked` + `data.target` |

##### `hasListeners` 的三态，以及它为什么不能瞎猜

`hasListeners` 有三种取值，**别把 `null` 当成 `false`**：

| 值 | 含义 | 怎么来的 |
| --- | --- | --- |
| `true` | 确认有监听器 | CDP 报的清单非空，或探到了框架痕迹（Vue `_vei` / React props / 内联 `on*` / jQuery） |
| `false` | **确认没有**监听器 | 只有 CDP（`data.detection: "cdp"`，Chromium 系）才给得出这个结论：浏览器自己报的清单是空的 |
| `null` | **未知** | 既没走 CDP、也没探到框架痕迹。原生 `addEventListener` 在元素上**不留任何可枚举痕迹**，所以「没探到」绝不等于「没有」 |

所以：

- **`get_element_listeners` 在 Chromium 系上走 CDP 的 `DOMDebugger.getEventListeners`**（`data.detection: "cdp"`），
  这是浏览器自己报的清单，原生 `addEventListener` 也算，结论可信 —— P2 那个「input 没人监听」就是靠它一眼看出来的。
- **Firefox / CDP 不可用时退回启发式**（`data.detection: "heuristic"`）：探到框架痕迹才敢说 `true`，
  否则只能报 `null`（未知）并给一句提示。
- **元素清单（`get_interactive_map` / `get_browser_state` 的 `data.elements`）里的 `hasListeners` 是启发式的**
  —— 对每个元素都开一次 CDP 会话太贵。所以那里只会是 `true`（探到痕迹）或 `null`（未知），**永远不会是 `false`**；
  要确认「确实没人监听」就单独对那个元素调一次 `get_element_listeners`。

### 按选择器 / 文本 / 语义定位（快照过期时的兜底）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `click_element_by_selector` | `id`, `selector`, `mode`(可选), `timeoutMs`(可选), `frame`(可选) | CSS 选择器取第一个匹配并点击；**点完如果弹出新页签会自动切过去并带到最前**；返回点击回执。目标在跨域 iframe 里时传 `frame` |
| `input_text_by_selector` | `id`, `selector`, `text`, `mode`(可选), `frame`(可选) | 覆盖式填充。**可见字段默认走真实输入**（进框架模型），这是「值填了但预览/校验说为空」时的正解 |
| `click_element_by_text` | `id`, `text`, `mode`(可选) | 按可见文本定位。**先向上找最近的可点击祖先**（`a`/`button`/`[role=button]`/`[onclick]`），找不到才点文本节点本身；返回真正命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `click_element_by_role` | `id`, `role`, `name`(可选), `mode`(可选) | 无障碍角色，`role` 如 `button`、`link`、`textbox`、`checkbox`；返回命中的 `data.tag`/`data.outerHtml` 与点击回执 |
| `input_text_by_label` | `id`, `label`, `text`, `mode`(可选) | 按表单标签 / `aria-label` 定位输入框 |
| `clear_text` | `id`, `index`(可选), `selector`(可选) | 清空输入框，返回 `data.value`（清空后的值）。`input_text` 的 `text` 必填，所以清空走这里；`index` 与 `selector` 传一个即可 |
| `hover_and_click` | `id`, `index`(可选), `selector`(可选), `hoverDelayMs`(可选), `mode`(可选) | 悬停后**立刻**点同一个元素，`hoverDelayMs` 默认 300 毫秒。悬浮菜单专用：分两步调用中间隔着一次推理往返，菜单早收起来了。返回命中信息与点击回执 |

### 标签页

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_tabs` | `id` | 返回 `data.tabs`（`index`/`url`/`title`/`current`，`index` 是 0 基） |
| `new_tab` | `id`, `url`(可选) | 新建标签页并切换过去，返回 `data.pageIndex`、`data.url`；新页签会被带到最前 |
| `switch_tab` | `id`, `pageIndex` | 切换当前操作的标签页，并把该页签带到最前 |
| `switch_tab_by_url` | `id`, `url` | 按 URL 匹配切换当前页签，返回 `data.pageIndex`/`data.url`/`data.title`。匹配规则见下面的「URL 匹配」；匹配不到时 `msg` 里列出当前全部页签 |
| `close_tab` | `id`, `pageIndex` | 关闭标签页；关掉当前页时自动切到第一个（同样带到最前） |
| `close_other_tabs` | `id`, `pageIndex`(可选) | 关掉除指定页签外的全部页签，返回 `data.closed`/`data.remaining`/`data.pageIndex`。不传 `pageIndex` 时保留当前页签。**关重复页签用这个**：一个个 `close_tab` 会因为索引整体前移而关错 |
| `bring_to_front` | `id`, `pageIndex`(可选) | 把指定页签（默认当前页签）带到窗口最前，返回 `data.pageIndex`/`data.url`。只切窗口、**不改当前操作页**，适合「让人看一眼这一页」 |

`new_tab` / `switch_tab` / `switch_tab_by_url` / `close_tab` / `close_other_tabs` / `bring_to_front` 以及 `click_element_by_selector` 命中弹窗时都会调用 `bringToFront()`：**有头模式下浏览器窗口会跟着切到智能体正在操作的那个页签**，人工可以直接看到当前进度；无头模式没有副作用。

页签多了以后 `pageIndex` 不稳定（实测点一次菜单会弹出两个同 URL 的重复页签），优先用 `switch_tab_by_url` 定位。

### 等待

`timeoutSeconds` 不传时默认 30 秒；超时返回 `xxx 超时：等待时间内条件一直没有满足`。

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `wait_for_element` | `id`, `selector`, `timeoutSeconds` | 等元素出现 |
| `wait_for_text` | `id`, `text`, `timeoutSeconds` | 等文本出现 |
| `wait_for_url` | `id`, `url`, `timeoutSeconds` | 等 URL 匹配（支持 `**/path` 这类通配），返回 `data.url` |
| `wait_for_load` | `id`, `state`, `timeoutSeconds` | `state` 取 `load` / `domcontentloaded` / `networkidle` |
| `wait_for_function` | `id`, `expression`, `timeoutSeconds` | 等 JS 表达式为真，如 `() => document.readyState === "complete"` |
| `wait_for_idle` | `id`, `quietMs`(可选，默认 500), `timeoutSeconds`(可选，默认 30), `selector`(可选), `text`(可选) | 等页面「忙完」：连续 `quietMs` 毫秒既没有 DOM 变更、也没有在途请求。**不知道要等多久时用它**，比固定 `wait` 稳、比手写 `wait_for_function` 省事 |
| `wait_for_stable` | `id`, `selector`(可选，默认整个 body), `quietMs`(可选，默认 800), `timeoutSeconds`(可选) | 等**内容**稳定：正文 / 表单值 / 表格内容连续 `quietMs` 毫秒不再变化，返回 `data.stable`、`data.changes`、`data.length`、`data.fingerprint` 与**稳定后的文本 `data.text`** |
| `wait_for_count` | `id`, `selector`, `min`/`max`/`equals`(至少给一个), `timeoutSeconds`(可选) | 等命中数量达标。等弹窗/遮罩**全部消失**用 `max: 0`，等结果行**出现**用 `min: 1`。返回 `data.matched`、`data.count`、`data.waitedMs` |

`wait_for_idle` 的返回：`data.idle`（成功时 true）、`data.waitedMs`、`data.mutations`（观察到的 DOM 变更次数）、`data.inflight`（结束时仍在途的请求数）。超时失败时同样带这几个计数，便于判断是「接口一直不回来」还是「页面有定时器一直在改 DOM」。传了 `selector` / `text` 时会同时要求该条件成立。

**`wait_for_idle` 看「忙不忙」，`wait_for_stable` 看「内容变没变」**，两者不能互相替代：查询/搜索结果是异步刷新的，页面可能一直在动（动画、轮询计时器）但真正要读的内容已经定下来了，这时用 `wait_for_stable`；点一下等它保存完、不知道要等多久，用 `wait_for_idle`。

> **查完立刻读结果是错的**：实测点「查询」后马上读表格，读到的是**上一次**的搜索结果，表现成「明明有这一行却找不到」。正确顺序是 `click` → `wait_for_stable`（或 `wait_for_count`）→ 再读。`wait_for_stable` 已经把稳定后的文本一并返回，省掉一次 `get_element_text`。

### 鼠标

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `mouse_move` | `id`, `x`, `y` | 移动鼠标到坐标 |
| `mouse_down` | `id`, `button` | `left` / `right` / `middle` |
| `mouse_up` | `id`, `button` | 松开 |
| `mouse_wheel` | `id`, `deltaY` | 滚轮，正数向下 |
| `mouse_click` | `id`, `x`, `y`, `button`(可选), `clickCount`(可选) | **真实鼠标点击一个坐标**（一条顶原来的 `mouse_move`+`mouse_down`+`mouse_up` 三条） |
| `mouse_click_by_selector` | `id`, `selector`, `button`(可选), `clickCount`(可选), `timeoutMs`(可选) | 真实鼠标点击某个元素**中心**（自己算坐标，不用先 `get_element_box`） |

**真实鼠标事件是唯一能让某些控件生效的方式**：实测 ant-design 的 `Modal.confirm`「确定/取消」、对话框右上角的 ×，用 JS 派发 `click`（甚至元素原生 `el.click()`）**完全无效**——点了没反应、弹窗不关，而接口照样回成功。这类控件必须走真实鼠标事件。

三个点击方法（`click_element_by_index`、`click_element_by_selector`、`click_element_by_text` 等）都支持 `mode`：

| `mode` | 行为 |
| --- | --- |
| `auto`（默认） | 原生点击 → 未发现遮挡的普通失败才尝试真实鼠标与 JS 降级；目标被遮挡时停止，对象释放异常不自动补点 |
| `native` | 只做原生点击（有完整的可操作性检查） |
| `mouse` | 只做真实鼠标点击（不做可操作性检查，元素一直在动时也能点到） |
| `js` | 只做 JS 派发（不要求元素可见、不被遮挡影响） |

回执里 `data.mode` 是**实际用上的**那一种，`data.fallbackReason` 是降级原因（没降级就没有），`data.effective` 表示这次点击有没有真的改变页面。**只看 `ok:true` 会误判**：JS 派发的点击在框架里可能被忽略，所以要连 `data.effective` 一起看。

`data.coveredBy` 表示目标中心点上实际命中的是别的元素（常见：用户服务协议层、弹窗遮罩、叠起来的确认框），这时先 `close_modal` 关掉遮挡物再点，而不是反复点。

#### 按住（press and hold）读图表上的一点

「按住不放」是 `mouse_move` + `mouse_down` + 读文本 + `mouse_up` 四步。**按下期间十字线被钉住**，图表图例（`[data-qa-id="legend"]` 之类的文本节点）会从「最新一根」切换为「光标锁住的那一根」自己的 `O/H/L/C/涨跌/量`——这正是读某一天 / 某个数据点数值的办法，比截图像素可靠得多：

```bash
# 1) 移到目标点（坐标可由 execute_js 取 getBoundingClientRect 算出来）
dsb run mouse_move -p x=990 -p y=280
# 2) 按住
dsb run mouse_down -p button=left
# 3) 读那根自己的数值（脚本只读，可带重发开关）
dsb js @读图例.js --retry-on-spurious
# 4) 一定要松开：忘了 mouse_up 会一直按着，后面每一次点击都会变成拖拽
dsb run mouse_up -p button=left
```

时间轴上的**日期标签是 canvas 像素**，读不到文字（见 `get_element_text` 的 `canvasOnly`）。要确定「按住的是哪一天」，改从页面上的文字线索推：标的详情里的 `Last update at …`、页面标题、`document.title`、以及系统时钟（美股周末休市 → 最后一根就是上一个交易日）。

### 截图与 PDF

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `screenshot` | `id`, `path`(可选), `fullPage`(bool), `index`(可选), `selector`(可选), `clipX`/`clipY`/`clipWidth`/`clipHeight`(可选), `inline`(bool，默认 false), `timeoutMs`(1—120000), `force`(bool), `fallbackToViewport`(bool，仅全页请求) | **默认落盘**：不传 `path` 时写到 `data/<id>/shot-N.png`，返回 `data.path`/`data.url`/`data.size` 与 `data.base64Omitted=true`；要内联 base64（直接喂视觉模型）才传 `inline: true`。传 `index` 或 `selector` 时**只截该元素**；否则截整页，`clipX/clipY/clipWidth/clipHeight` 四个都传才按区域裁剪。自动/手动共用熔断；force仅探测一次。截图预算及结果范围见 [testing-evidence.md](testing-evidence.md)。 |
| `get_element_screenshot` | `id`, `index`(可选), `selector`(可选), `path`(可选), `inline`(bool，默认 false), `frame`(可选), `timeoutMs`(1—120000), `force`(bool) | 只截一个元素，返回 `data.path`+`data.url`+`data.size`、`data.target`；`inline: true` 时另给 `data.base64`。`index` 与 `selector` 传一个即可。元素在跨域 iframe 里时传 `frame`。自动/手动共用熔断；force仅探测一次。截图预算及结果范围见 [testing-evidence.md](testing-evidence.md)。 |
| `pdf` | `id`, `path`(可选) | 存 PDF，返回 `data.path`；不传 `path` 落到 `~/Downloads/broswer/` |

> 日常「看页面长什么样」**先别看图**：`data.screenshot` 是每个改变页面的方法自动留下的截图地址，但把图读进上下文很贵，非必要不要读（见 `SKILL.md` 开头的省 token 铁律），读 `data.text` 就够了。`get_element_screenshot` 是验证码、二维码、图表这类「必须看图」的元素的标准做法：走 Playwright 自己的元素截图，**不受 canvas 跨域污染限制**（用 `execute_js` + canvas 手抠图，跨域图片会直接失败），而且只截一个元素、比整页图省得多。

### Cookie 与本地存储

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_cookies` | `id`, `url`(可选) | 返回 `data.cookies`（`name/value/domain/path/expires/httpOnly/secure/sameSite`）与 `data.count` |
| `set_cookie` | `id`, `name`, `value`, `url`(可选) | 建议同时传 `url`，否则要自己保证 domain 合法 |
| `clear_cookies` | `id` | 清空 |
| `get_local_storage` | `id`, `key`(可选) | 传 `key` 返回该键的值；不传返回整个 localStorage 的 JSON 字符串（`data.value`） |
| `set_local_storage` | `id`, `key`, `value` | 写入当前站点 |
| `clear_local_storage` | `id` | 清空当前站点 |

### 浏览器设置

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `set_viewport` | `id`, `width`, `height` | 改视口尺寸 |
| `set_geolocation` | `id`, `latitude`, `longitude` | 会同时授予 `geolocation` 权限 |
| `set_offline` | `id`, `offline`(bool) | 断网 / 恢复 |
| `set_headers` | `id`, `headersJson` | 形如 `{"X-Key":"v"}` |
| `set_credentials` | `id`, `username`, `password` | HTTP 基本认证。凭据只能在创建上下文时设置，所以**会重建整个浏览器**：当前页面丢失，**且会失败** —— 浏览器是所有任务共用的，只允许在「当前只有这一个任务」时调用（否则提示先 `close` 掉其它任务）；走 CDP 那条路时（`data.browser.mode=cdp`：用户自己的 Chrome profile 或 `browser=edge`）不支持，会直接返回失败原因，需要基本认证就改用 `browser=chrome`／`chromium` |
| `set_media` | `id`, `colorScheme` | `light` / `dark` / `no-preference` |

### 弹窗与控制台

这里有两类**完全不同**的弹窗，别混：

| 类型 | 是什么 | 用哪个方法 |
| --- | --- | --- |
| 浏览器原生对话框 | `window.alert` / `confirm` / `prompt`，会阻塞页面 | `get_dialog` / `clear_dialog` / `set_dialog_behavior` |
| 页面里的 DOM 弹窗 | ant-design 的 `Modal`/`Modal.confirm`、用户服务协议层、抽屉 | `get_modals` / `close_modal` |

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `get_dialog` | `id`, `consume`(bool) | 返回 `data.dialog`（`type/message/defaultValue/seq/timestamp`）或 null。**记录不会自动清除**，可能是很早以前的弹窗；`consume=true` 读后即清。`get_js_dialog` 是它的同义名 |
| `clear_dialog` | `id` | 清空弹窗记录，返回 `data.cleared`。`clear_js_dialog` 是它的同义名 |
| `set_dialog_behavior` | `id`, `dismiss`(bool) | 弹窗**默认自动确认**；`dismiss=true` 改成自动取消 |
| `get_modals` | `id` | 列出当前可见的 DOM 弹窗：`data.count` / `countStrict` / `countHeuristic` / `countBlocking`、`data.modals[]`（`kind`/`matchedBy`/`confidence`/`blocking`/`hasMask`/`className`/`id`/`title`/`text`/`buttons`/`buttonPoints`/`hasClose`/`closePoint`/`rect`/`zIndex`/`frameIndex`）、`data.top`（**最后一条 `blocking` 的**；没有 blocking 时才是最后一条）、`data.topIsBlocking`、`data.scannedBy`（跑了哪几轮扫描）。**主 frame 与每个 iframe 各扫一遍**，iframe 里的坐标已换算成主页面视口坐标 |
| `close_modal` | `id`, `which`(可选，默认 `top`), `title`(可选), `button`(可选) | 关掉 DOM 弹窗。**一律用真实鼠标点**，并且点完**校验数量是否真的减少**，返回 `data.closed`、`data.countBefore`、`data.countAfter`、`data.clicked` |
| `get_console_logs` | `id` | 返回 `data.logs` 与 `data.errors`，各最多 200 条 |
| `clear_console_logs` | `id` | 清空 |

**`get_dialog` 是「最近一次弹窗」而不是「当前这一步的结果」**：实测提交验证码失败过一次之后，后面查询明明成功了，`get_dialog` 仍然返回上一轮的「验证码输入错误」，很容易误判成这次也失败了。判断弹窗是不是新的看 `data.dialog.seq`/`timestamp`；稳妥做法是**每次提交动作前先 `get_dialog` 加 `consume: true` 清一次**，动作后再读。

**DOM 弹窗用 `get_modals` / `close_modal`，不要自己写 JS 点它**：`close_modal` 的 `which` 取 `top`（默认）/ `first` / `all` / `class:<子串>`，`title` 按标题或文本子串匹配（给了就以它为准），`button` 指定点哪个按钮（不给则优先右上角 ×，其次「取消/关闭/知道了/我接受」这类非提交按钮）。反复点一个关不掉的弹窗会把确认框**一层层叠起来**（实测叠到 16 个），之后所有「取第一个可见弹窗」的逻辑都在操作最老的那个——所以要看 `data.closed`：为 `false` 说明点了但数量没减少，这时用 `get_modals` 拿 `closePoint`/`buttonPoints`，再 `mouse_click` 那个坐标。

**`buttons` 认的不只是框架按钮。** 只认 `.ant-btn` / `role=button` 会在老站点上全军覆没：实测 12306 的确认框按钮是 `<a id="qr_submit_id" class="btn92s">确认</a>`，老选择器给出 `buttons: []`，`close_modal` 连「确认」都点不到，只能退回手写 JS。现在会一并认「class 里带 `btn`」「`id` 以 `_id` 结尾」「带 `onclick`」的元素，一个都没认出来时还会兜底扫「短文本 + 可点」的锚（每条按钮带 `matchedBy: selector` 或 `fallback-anchor`）。同时按「像不像按钮」过滤：选座的 `A`/`B`/`C`、加减号、`×` 不会被算成按钮。

**`get_modals` 有通用兜底，不会再漏检自定义类名的弹窗。** 它按三轮扫描，每条结果的 `matchedBy` 说明命中来源：

| `matchedBy` | 怎么命中的 | 置信度 |
| --- | --- | --- |
| `selector:.ant-modal-wrap, .ant-drawer-open` 等 | 框架专用选择器（ant-design / element-ui / vxe / layui / 协议层） | 最高（`confidence: strict`） |
| `heuristic:class-name` | 类名里有 `dialog`/`modal`/`popup`/`overlay`/`mask`/`confirm`… 这类词 | 较高（`confidence: heuristic`）：企业微信 / 微信系的自有类名（``qui_dialog``、``ww_dialog``、``mall_invoice_dialog_container``）靠它命中 |
| `heuristic:fixed-overlay` | 可见 + 面积够大 + `position:fixed` 或 `z-index > 50` 的几何兜底 | 一般：只用来兜底 |

- 启发式扫描只保留**最内层**的候选：包住另一个候选的元素通常是整页遮罩，真正带标题和按钮的是它里面那个。
- **整页遮罩不算弹窗**（面积 ≈ 视口、没有文字也没有按钮的那种 scrim），**sticky 页头/静态提示条也不算**：它们没有按钮、没有标题、也没有关闭，只会把 `count` 抬高。实测 12306 的查询页老逻辑给出 `count: 5`，其中 4 条是页头与提示条，而 `top` 正好落在页头上——照 `top` 去关弹窗就会去点页头。所以判断「该处理哪个」看 `blocking`（本身像悬浮框且有内容）与 `countBlocking`，`top` 现在也优先取 blocking 的那条。
- 所以 `data.count: 0` 现在是可信的（三种扫描都跑过，`data.scannedBy` 会告诉你跑了哪几种）；`count > 0` 时按 `blocking` 与 `matchedBy` 判断是不是真弹窗。
- **弹窗没有标题、也没有 `role=dialog` 时**用类名关：`{"close_modal":{"which":"class:mall_invoice_dialog_container"}}`。`get_modals` 的每项都带 `className`，照着填即可。
- 弹窗在 iframe 里也能被找到（每项带 `frameIndex`），坐标已经换算成主页面视口坐标，`close_modal` 的鼠标点击因此仍然有效。

### 网络

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `route` | `id`, `urlPattern`, `action`, `body`, `status`, `contentType` | `action` 取 `abort`（拦截）或 `mock`（返回自定义响应）；`urlPattern` 用 Playwright 通配，如 `**/api/ping` |
| `unroute` | `id`, `urlPattern`(可选) | 不传则移除全部路由 |
| `get_requests` | `id`, `filter`(可选), `resourceType`(可选), `limit`(可选), `since`(可选) | 返回 `data.requests`（`method/url/resourceType/status`，**带请求体的请求另有 `postData`，最多 4000 字符**），最多 200 条。`filter` 按 URL 子串过滤，`resourceType` 按 `xhr`/`fetch`/`document`/`script` 等过滤，`limit` 限制条数，`since` 只返回该毫秒时间戳之后的。返回里另有 `data.count`/`data.total`/`data.recordedSince`/`data.inflight`/`data.note`。**只有元数据，没有响应体** |
| `wait_for_response` | `id`, `urlPattern`, `timeoutSeconds`(可选), `maxChars`(可选), `lookBackSeconds`(可选) | 等 `urlPattern` 匹配的响应并返回它的响应体：`data.url`、`data.status`、`data.body`（默认最多 20000 字符）、`data.bodyLength`、`data.ageMs`、`data.fromLookBack`。匹配规则见下面的「URL 匹配」 |
| `get_response_body` | `id`, `filter`(可选), `index`(可选), `maxChars`(可选), `requestId`(可选) | 回看**已经发生过**的响应体（保留最近 100 个响应）。`filter` 按 URL 子串过滤，不传取最近一个；`index` 在多个匹配里选第几个（默认最后一个）。可用 `requestId` 精确关联重复 URL 的某次请求。**xhr/fetch 响应体在下载完成回调里缓存**，所以跳转/等一会儿之后照样读得到；读的是缓存时另有 `data.bodyFromCache: true` 与 `data.bodyCapturedAt`，可缓存类型尚未完成缓存时是 `data.bodyCapturePending: true`。未缓存时按需读取，读取失败才给 `data.bodyError` / `data.bodyHint`、`data.bodyAvailable=false`。正常关联带 `correlationMissed: false`；缺失关联明确带 `correlationMissed: true`、`correlatedBy: "none"`，不按 URL 猜配 |

**URL 匹配**（`wait_for_response` 的 `urlPattern`、`switch_tab_by_url` 的 `url`）：先按**子串**匹配，再按**通配**匹配，通配里的 `*` 和 `**` 都表示任意字符、**可以跨 `/`**。模式没写尾部通配时，URL 后面还可以再跟内容（`#fragment`、`?query` 都算），所以：

| 模式 | 能匹配 |
| --- | --- |
| `**/api/query*` | `https://x.com/api/query?y=1` |
| `**/example.com*` | `https://example.com/`（末尾斜杠不影响） |
| `**/smoke.html` | `file:///…/smoke.html#`（尾部 `#fragment` 不影响） |

> 注意这一条和 `route`、`unroute` 的 `urlPattern` **不一样**：那两个用的是 Playwright 原生通配，`*` 不含 `/`，`https://example.com/` 末尾的斜杠会让 `**/example.com*` 匹配不上。写 `route` 时如果 URL 末尾有斜杠，把模式改成 `**/example.com/**` 这类形式。

**读接口数据用 `wait_for_response`，不要靠 `get_requests` 猜**：SPA 的数据都在 XHR 里，而 `get_requests` 只有 url 和状态码。典型用法是「点一下 → 等接口 → 读 JSON」，放在一个批次里：

```json
// api.json：点一下 → 等接口 → 读 JSON
{"id":1001,"method":"commands","params":{"stopOnError":false,"commands":[
  {"click_element_by_index":{"index":12}},
  {"wait_for_response":{"urlPattern":"**/api/query*","timeoutSeconds":20}}]}}
```

```shell
.\client\dsb.cmd --port 10049 --id 1001 batch api.json
```

**为什么顺序写就行**：`wait_for_response` **先回看再等** —— `lookBackSeconds`（默认 10 秒）内已经收到过的匹配响应会直接返回，`data.ageMs` 是它距今的毫秒数、`data.fromLookBack` 为 `true`。响应通常在你拿到点击结果之前就到了，所以顺序调用照样命中。`lookBackSeconds=0` 表示只等新响应（这时必须并发触发，而**同一个实例不要并发发请求**，见 `pitfalls.md` 第 13 条，所以一般不需要）。

已经发过的请求想看返回内容就用 `get_response_body`。

**`get_requests` 返回空不等于「没有请求」**：记录是从页签挂上监听那一刻开始记的，页面在这之前（或另一个页签里）发生的请求不会出现在这里。所以空结果时服务会一并返回 `data.note` 说明这一点、`data.recordedSince` 告诉你从什么时候开始记、`data.inflight` 告诉你此刻还有几个在途。判断顺序：先看 `recordedSince` 是不是晚于你要找的那次请求 → 再用 `filter`/`resourceType`/`since` 缩小范围 → 仍然没有就改用 `get_response_body`（它保留最近 100 个响应，跨页签）。

### 人机协同（验证码 / 短信码 / 人工登录）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `request_human_input` | `id`, `prompt`, `index`(可选), `selector`(可选), `frame`(可选), `timeoutSeconds`(可选), `steps`(可选), `expiresAt`(可选), `ocr`(可选), `ocrLanguage`(可选), `inline`(bool，默认 false) | 发起一个人工介入请求。传 `index`/`selector` 时把该元素（通常是验证码 / 二维码）截下来，回 `data.imagePath`（服务端本地路径）、`data.imageUrl`（可直接 GET 的地址，能贴给用户）、`data.imageTarget`（截图取自哪个 frame / 选择器）、`data.imageSize`；**默认不回 `data.imageBase64`**（改为给 `data.base64Omitted=true` 与 `data.imageNote`）—— 那张图的 base64 动辄几 KB 到几十 KB，落到回执里基本只会被原样读进上下文，要内联才传 `inline: true`（与 `screenshot` / `get_element_screenshot` 的默认一致）。还把当前页签带到最前。返回 `data.requestId`、`data.prompt`、`data.expiresAt`、`data.expiresInSeconds`、`data.url`。**目标在跨域 iframe 里时传 `frame`**（`steps[]` 里每项也可各带一个） |
| `submit_human_input` | `id`, `requestId`, `answer`, `stepId`(可选), `answers`(可选) | 提交人工答复。单步请求直接给 `answer`；多步请求（`steps`）用 `stepId` 逐条回填，或 `answers: {"s1":"...","s2":"..."}` 一次回填多步 |
| `get_human_input` | `id`, `requestId`, `timeoutSeconds`(可选) | 取人工答复，返回 `data.status`（`pending`/`partial`/`answered`/`expired`）、`data.answer`、`data.steps`、`data.prompt`。传 `timeoutSeconds` 时长轮询等待，到时间还没答复就返回当前状态（**不算失败**）。过期时另给 `data.expired:true` 与提示 |
| `ocr_image` | `id`, `path`(可选), `index`/`selector`(可选), `frame`(可选), `language`(可选) | 用**本机 OCR**把图上的文字读出来：`data.ok`、`data.text`、`data.lineCount`、`data.imagePath`/`data.imageUrl`。给 `path` 读服务端已有的一张图（例如 `download_image` 落下来的原件）；给 `index`/`selector` 则先截这个元素再读。`data.engine` 说明这次是谁读的：`windows` = 系统 OCR（Windows 自带 `Windows.Media.Ocr`，擅长验证码这类短文本）；`command` = 配置指定的**外部文档 OCR 命令**（整页扫描件、票据、表格截图这类整页文档该走这条，见下方「OCR 后端」）。**模型读不了图时的兜底**，见 `human-in-loop.md` |

完整流程见 `human-in-loop.md`。

### 其它

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `extract_structured_data` | `id`, `query`, `extractLinks`(bool) | 返回 `data.text`（正文，读取时会临时隐藏高亮层）与 `data.links`；另有 `data.length`（截断前全长）、`data.truncated`、`data.limit`。上限默认 20000，可用配置 `browser.extract.maxChars` 改（与 `extract_markdown` 同源）。**判断读全了没有要认 `truncated`，别看"像不像到头了"** |
| `extract_markdown` | `id`, `selector`(可选), `frame`(可选), `nth`(可选), `includeLinks`(bool，可选), `maxChars`(可选) | 把页面（或 `selector` 命中的元素）转成 Markdown：`data.markdown`、`data.length`、`data.truncated`、`data.source`（`body` 或选择器，指定了 `nth` 时形如 `table >> nth=1`）、`data.url`、`data.title`；`includeLinks` 时另给 `data.links`。**表格按 GFM 表格输出**（`| 表头 |` + `| --- |` + 数据行），而 `extract_structured_data` 的 `innerText` 会把单元格逐行摊平、列对不上。**命中多个时用 `nth` 指定第几个（0 基）**，越界会报出总数；先 `list_tables` 拿现成选择器最省事。`selector` 在跨域 iframe 里时传 `frame`（与 `get_element_count` 同一套取值） |
| `find_text` | `id`, `text`(必填), `regex`(bool，可选), `contextChars`(可选，默认 80), `maxMatches`(可选，默认 20), `selector`(可选), `frame`(可选) | 在页面文本里找词（或正则），只回**命中与前后文**：`data.matchCount`、`data.returned`、`data.truncated`、`data.matches[]`（每项 `index`/`line`/`match`/`before`/`after`）、`data.source`、`data.textLength`。**想找一行就用它**，别把整页拉进上下文。取文本的规则与 `extract_structured_data` 一致（`innerText`，读前隐藏高亮层），所以**图里的字它同样找不到**。正则非法会当场失败（不会悄悄退化成字面量搜索）；不重叠匹配 |
| `list_tables` | `id`, `frame`(可选) | 列出页面上所有 `<table>`：`data.count`、`data.tables[]`（`index`、**`selector`**（形如 `table >> nth=1`，可直接填进 `extract_markdown`）、`rows`、`cols`、`className`、`id`、`textLength`、`imageCount`、`preview`）、`data.url`、`data.title`。`imageCount > 0` 值得看一眼：表格内容是图片时 `extract_markdown` 转出来是空的，该走 `download_image` + `ocr_image` |
| `download_image` | `id`, `index` 或 `selector`(二选一), `frame`(可选), `filename`(可选) | 把页面上的图片**原始文件**存到服务端 `data/<id>/`：`data.path`、`data.url`（可直接 GET）、`data.filename`、`data.size`、`data.sha256`、`data.contentType`、`data.srcUrl`、`data.via`、`data.naturalWidth`/`Height`。**要的是原件，不是屏幕截图**（扫描件截屏再 OCR 会明显掉字）。取值先走浏览器上下文（带 cookie），失败退回页面内 `fetch`；两条都不行才失败并说明原因。`data.path` 可直接交给 `ocr_image` |
| `execute_js` | `id`, `body` 或 `bodyFile`, `vars`(可选), `frame`(可选), `retryOnSpurious`(可选), `timeoutMs`(可选) | 返回 `data.result`，见 `batch-and-js.md`。**会 await Promise**；在跨域 iframe 里执行要传 `frame`；脚本**重发无害**（读页面这类）时传 `retryOnSpurious: true`，服务端会替它吃掉「事件泵伪故障」。`timeoutMs` 是这一次的时间预算（毫秒，`0` = 不限制），不传用 `browser.eval.timeoutMs`（默认 60000）——服务端在页内用 `Promise.race` 兜底，**永不 settle 的 Promise 也会在预算内结束**并回 `errorCode=EVAL_TIMEOUT`（`data.outcomeUnknown:true`、`retryable:false`：脚本可能已经改了一半状态，**不要直接重发**，先用只读命令确认页面） |
| `commands` | `id`, `params.stopOnError`, `params.commands`, `params.async`(可选) | 批量指令，是 `method` 的一个取值，见 `batch-and-js.md` |

### 服务自省（不知道有什么能力时先问它）

| 方法 | 参数 | 说明 |
| --- | --- | --- |
| `list_methods` | `filter`(可选) | 返回 `data.methods`（全部方法名）与 `data.count`。**方法名拿不准就先查**，别靠猜——猜错只会拿到一句「不支持的方法」 |
| `get_config` | `id`(可选) | 服务端**生效**配置：`engine`/`configuredType`、`profileDir`（解析后的真实目录）、`action`（超时与两个降级开关）、`jsDir`、`trace`、`upload`、`tasks`、`commands`。传 `id` 时另给该任务的 `browser`。**「为什么这次不是我要的浏览器」「脚本放哪」这类问题先看它** |
| `list_tasks` | 无 | 当前活着的任务：`data.tasks[]`（`id`/`url`/`title`/`tabCount`/`captureSeq`/`inflight`/`profileDir`）与 `data.browser`（类型、profile 目录、是否走 CDP） |
| `list_recipes` | 无 | 服务端有哪些站点配方，返回 `data.recipes[]`（`name`/`description`/`params`/`stepCount`）与 `data.dir` |
| `run_recipe` | `id`, `name`, `vars`(可选), `stopOnError`(可选) | 跑一个站点配方，见本文下面的「站点配方」 |
| `get_job` | `jobId`, `includeResult`(可选，默认 true) | 查异步批次的结果：`data.status`（`running`/`done`/`failed`/`cancelled`）、`data.steps`、`data.data` |
| `cancel_job` | `jobId` | 取消异步批次。**取消是协作式的**：批次会在下一步之前停下来，当前这一步不会被打断 |
| `list_jobs` | `limit`(可选，默认 20) | 最近的异步任务（只保留 50 个，服务重启即丢） |
| `cleanup` | `scope`(可选，默认 `all`), `olderThanHours`(可选，默认 24), `keepLatest`(可选), `dryRun`(可选，**默认 true**) | 清理落盘产物（截图、结构化文本、追踪日志）。**默认只预演不删**，要真删必须显式传 `dryRun: false`。`scope` 取 `all`/`data`/`trace`/`upload`；`all` 不会动 `upload`（暂存文件可能正在被任务使用） |
| `shutdown` | 无 | 关掉所有任务与共享浏览器（服务进程不退出）。比直接杀进程干净：不留占着 profile 目录的孤儿浏览器 |

这些能力也有 GET 版本，脚本与浏览器可以直接打开：`GET /playwright/methods`、`GET /playwright/config`、`GET /playwright/tasks`（与 `list_methods`/`get_config`/`list_tasks` 同一份数据）。

## 站点配方（run_recipe）

配方是把「某个站点上必须这么点」的经验固化成服务端的 JSON 命令序列：文件放在配方目录（默认 `<启动目录>/recipes`，可用 `browser.recipes.dir` 改），文件名就是配方名，内容形如：

```json
{
  "name": "close-antd-modal",
  "description": "关掉 ant-design 的确认框：JS 派发无效，必须真实鼠标点",
  "params": {"title": "要关的弹窗标题，可选"},
  "commands": [
    {"get_modals": {}},
    {"close_modal": {"title": "{{title}}", "button": "取消"}}
  ]
}
```

调用：

```bash
{"id":1001,"method":"run_recipe","params":{"name":"close-antd-modal","vars":{"title":"确认提交"},"stopOnError":true}}
```

- `list_recipes` 看有哪些配方；`get_config` 的 `jsDir`/`recipesDir` 告诉你目录在哪。
- `vars` 用 `{{变量}}` 注入，走 JSON 编码（中文、引号、换行都不用转义）。
- 配方里的每一步都走同一套命令分发，所以**单步手跑与整段跑行为完全一致**，排障时可以把配方里的命令一条条贴出来单独执行。
- 回执里带 `data.recipe` 与 `data.recipeDescription`，其余字段与 `commands` 批量完全一致（`count`/`succeeded`/`failed`/`results`）。
- **配方不会自动生效**：必须显式点名 `run_recipe` 才执行，不做任何「看到这个域名就自动套用」的隐式推断。引擎选择同理，始终由调用方在 `start` 时决定。
- **配方里的命令名会被构建期检查**：写错一个不会等到运行时才发现（见 `docs/SKILL-CONVENTIONS.md`）。

### 现成配方

| 配方 | 用途 |
| --- | --- |
| `close-all-modals` | 清掉页面上所有可见的 DOM 弹窗（ant 确认框、用户服务协议层、抽屉）。这类按钮只认真实鼠标事件 |
| `query-and-read-table` | 点「查询」→ 等表格内容稳定 → 读表格。搜索结果是异步刷新的，点完立刻读会读到上一次的结果 |
| `cnipa-list-drafts` | 中国商标网：「我的账户 → 申请管理 → 未提交」并把日期筛选切到「近三个月」再读列表 |
| `open-console-from-iframe` | **主站把第三方控制台套在跨域 iframe 里**时的通用套路（反查 iframe.src → 找主站发 token 的接口 → 同源 execute_js 现取 → 顶层打开） |
| `gov-site-search` | **国产政府/事业单位站的站内全文检索**：进高级搜索 → 选「包含完整关键词」→ 填词 → 可选日期区间 → 每页 50 → 点搜索。这类站的检索参数全在 JS 状态里，**直接拼 URL 会静默返回 0 条**，必须走表单 |

## OCR 后端（`ocr_image` 走哪条路）

`ocr_image` 默认用系统 OCR（`Windows.Media.Ocr`），够用在验证码、二维码、维护提示图这类**短文本**上：零配置、零依赖。但遇到**整页扫描件、票据、带合并单元格的表格截图**就不行了 —— 它给出的是错乱片段，而这类场景恰恰是「图就是数据」。

这时把它换成外部文档 OCR 工具（本机往往已经有了一个现成的命令行工具）：

```properties
browser.ocr.engine=command
browser.ocr.command=java -jar D:/tools/ocr-cli.jar -i "{input}" -o "{output}"
browser.ocr.timeoutMs=120000
```

- `{input}` = 图片路径，`{output}` = 结果文本路径，`{language}` = 语言（默认 `zh-Hans-CN`）。**带空格或中文的路径一定要加双引号**。

### 四个「出问题时要看」的服务端旋钮

它们的**生效值**都能从 `get_config` 读到（`data.capture.*` / `data.eval.*` / `data.command.*` / `data.network.*`），不必去翻服务器上的配置文件：

```properties
# 自动截图与熔断：连续失败 failThreshold 次后冷却；伪故障单独计数、只短冷却；
# 冷却到期会半开（清零计数），手动 screenshot 传 force=true 可探测一次且失败不延长冷却
browser.capture.enabled=true
browser.capture.timeoutMs=8000
browser.capture.failThreshold=3
browser.capture.cooldownMs=120000
browser.capture.spuriousCooldownMs=15000

# execute_js 的时间预算（页内 Promise.race 兜底）；单次可用 params.timeoutMs 覆盖，0 = 不限制
browser.eval.timeoutMs=60000

# 命令级 wall-clock 兜底：普通命令 / 「要等」的命令（批次、wait_for_*、人机协同、pdf、ocr…）
browser.command.timeoutMs=90000
browser.command.hardTimeoutMs=900000
browser.command.maxStuck=24

# 请求/响应记录：on(默认) 认领页签就挂；lazy 第一次读网络数据才挂（此前的请求不会出现）；
# off 永不挂 —— 踩到 response@/request@ 噪声风暴又不需要网络数据时用它，回执会明说「没在记」
browser.network.record=on
```

> 截图一直不出图时先看 `data.capture_degraded` 与 `data.retryAfterMs`：那是**服务端在告诉你现在是盲操作**，别继续等图，改用文本取证；要视觉确认就用 `screenshot` + `force:true` 探一次，或请人看一眼窗口。
- 回执里的 `data.engine` 会写明这次是谁读的；`get_config` 的 `ocr` 段给出**生效值**（含 `describe`，配了 `engine=command` 却没给命令时会如实说明退回了系统 OCR）。
- 外部命令失败**不会静默退回系统 OCR**：调用方明确要的是文档级识别，悄悄换个差一截的后端只会让人以为"这张图读不出来"。

完整链路（典型用法）：

```text
list_tables / get_browser_state 的 mediaHint 发现有图
  → download_image（取原始文件，拿到服务端 path）
  → ocr_image -p path=…（读文字：表格通常是 HTML 或 Markdown 形式）
  → 结果超过上限时用 find_text 只取关心的行
```

| `wework-qykit-open-console` | 企业微信后台的「邮件 / 微盘 / 文档 / 会议」：现取一个未被消费的 token，把 `exmail.qq.com` 控制台当顶层页面打开 |
