# 坑与限制（72 条）

> 本文是 [SKILL.md](../SKILL.md) 的分册，按需阅读。SKILL.md 开头的「症状 → 命令」表里提到的「第 N 条」就是本文的编号。

1. **只有一个端点，只支持 POST + JSON 请求体**：`{"id":...,"method":...,"params":{...}}`。参数不放查询串、不放表单，也不需要 URL 编码。
2. **参数问题不再返回 HTTP 500**：缺参数得到 `xxx 失败：缺少参数 name`。**方法名写错会顺带给近似建议**：`不支持的方法：list_tabs，你是不是想用 get_tabs / new_tab / get_tabs？`（按编辑距离与分词近似挑候选），照着改一次就能过，不用再猜。运行期错误也是 `code:0`，例如 `go_to_url 失败：net::ERR_CONNECTION_REFUSED at ...`、`send_keys 失败：Unknown key: "NotAKey"`。
3. **页面变化后索引全部重算**：点击、跳转、异步渲染之后必须重新 `get_browser_state`；沿用旧索引会得到 `索引越界` 或 5 秒超时后提示重新取快照。
4. **快照里没有 `id`/`class`/`href`**：按 id/class 定位用 `click_element_by_selector`，取 href 用 `execute_js`，批量看属性用 `get_interactive_map`（它现在还带 `hasListeners`/`listeners`）。
5. **纯文本容器（`div`/`span`/`li`）没有索引**：这类元素用 `click_element_by_selector` 或 `execute_js` 调 `.click()`；但带 `onclick`/`cursor:pointer` 的 `div`/`span` 会有索引，别一概而论。
6. `wait` 的 `seconds` 必填；要等页面就绪请用 `wait_for_load` / `wait_for_element`。
7. `upload_file` 的 `path` 是**服务器**能打开的路径（绝对路径直接用，相对路径按服务端暂存目录解析），不是 URL；文件不存在时错误信息会告诉你去 `POST /playwright/upload` 把文件送上来。传 `selector` 可以操作隐藏的 file input（比 `index` 更好用，见 `protocol.md` 的「上传文件」）。
8. `scroll_to_text` 找不到文本会等满 30 秒，别用它探测元素是否存在，用 `wait_for_element`。
9. **`execute_js` 没有超时**：脚本里不要写死循环或长时间轮询，否则请求一直挂着；页面上弹模态框时弹窗会被自动确认，也可以用 `set_dialog_behavior` 改成自动取消。
10. `execute_js` 的 `body` 上限 100000 字符；返回 DOM 元素只会得到 `ref: <Node>`。
11. `get_browser_state` 的 `highlight=true` 会在页面上加一层高亮框，它是页面里真实存在的 DOM（`extract_structured_data` 读取正文时会临时隐藏它）；人工观察时很有用，纯自动跑可以传 `highlight=false`。
12. **不要用同一个 `id` 重复 `start`**：会直接返回失败，提示先 `close` 或换一个 id。不同任务用不同 id（浏览器与 profile 是共用的，隔离靠各自的页签）。
13. 一个任务只对应一个当前 Page，**不要并发对同一个 `id` 发请求**；并发任务请各自 `start` 一个任务（共用浏览器，各有各的页签）。
14. `set_credentials` 会重建整个浏览器（所有任务共用），只能在「当前只有一个任务」时用，走 CDP 那条路时（用户自己的 Chrome profile 或 `browser=edge`）不可用；`headless=false` 会弹出真实窗口，只适合本机调试。
15. 服务无鉴权且 `execute_js` 能执行任意脚本，对外部署前必须加访问控制。
16. **不可见元素既不进快照，也不能用「原生方式」操作**：实测百度首页的真实搜索框 `INPUT#kw`（`offsetParent === null`，被新的 AI 输入框取代而隐藏）不在 `data.text` 里，只剩提交按钮；`input_text_by_selector` 按默认方式作用在它上面会等满超时后返回 `input_text_by_selector 失败：[ELEMENT_HIDDEN] 元素当前不可见: 选择器 #kw`。这时有三条路，**优先第一条**：

    1. 传 `mode: "js"`（跳过可操作性检查，直接在页面里设值并派发 `input`/`change`），回执会给出 `data.committed`：

       ```json
       {"id":1001,"method":"input_text_by_selector",
        "params":{"selector":"#kw","text":"Mac Mini M4","mode":"js"}}
       ```

       注意：JS 设值**不保证进框架的 model**（`committed=false`），关键字段仍要用可见的等价输入框重填一遍。
    2. 用 `execute_js` 自己设值并派发事件（要完全控制事件细节时）：
    3. 先把元素显示出来（去掉 `display:none` / 改 `visibility`）再按常规方式操作——**只在确实没有别的办法时用**，改动页面样式可能让站点行为与真实用户不一致。

    ```js
    (function(){var e=document.querySelector("#kw");e.focus();e.value="Mac Mini M4";
      e.dispatchEvent(new Event("input",{bubbles:true}));return e.value;})()
    ```

    判断元素是否可见：快照里有它就是可见的；怀疑隐藏时用 `execute_js` 看 `e.offsetParent !== null`，或直接用 `get_form_state` 加 `includeHidden: true` 看 `visible` 字段。
17. **批量接口的约定**：每项只能有一个键；`stopOnError` 默认 `true`（遇到第一个失败就停），批量里推荐显式传 `false`；布尔参数不传按 `false` 处理（`scroll` 要写 `"down":true`）；`commands` 不能嵌套；覆盖范围是除 `commands` 外的全部方法；数组最多 200 条。
18. **验证 `route` mock 时别等页面自己的回调**：实测页面加载时自己发起的 `fetch` 被 mock 后，渲染进程里的 `.then` 可能迟迟不执行（没有真实网络 IO 去唤醒它），而用 `execute_js` 主动发一次同样的请求就能立刻拿到 mock 数据。要验证拦截效果就用 `execute_js` 主动发请求，或看 `get_requests` 里的状态码。
19. **一个批次里前面的失败会影响后面**：批次是顺序执行的，索引类命令依赖同一次快照，前面的点击跳转会改变 DOM；PTC 的稳妥做法是「动作段 + 末尾 `get_browser_state`」，用下一次推理基于新快照决定后续，而不是在一个批次里塞几十步。
20. **`get_dialog` 是「最近一次弹窗」，不会自动清除**：它可能来自很早以前的一次操作，别把内容当成当前这一步的结果。实测提交验证码失败过之后，后续查询明明成功了，`get_dialog` 仍返回上一轮的「验证码输入错误」，据此误判会白跑一轮。用 `data.dialog.seq`/`timestamp` 判断新旧，或在每次提交动作前先 `get_dialog` 加 `consume: true`。
21. **`ok=true` 不代表点中了东西**：点击类方法只保证动作没抛异常。实测点悬浮菜单时文本命中的是纯文本容器，方法返回成功但页面毫无变化。是否观察到变化看回执里的 `data.changed`；`click_element_by_text` / `click_element_by_role` / `hover_and_click` 还会返回真正命中的 `data.tag`/`data.outerHtml`。
22. **`input_text` 清空不了**：`text` 是必填参数。要清空用 `clear_text`。
23. **`get_requests` 只有元数据**：`method/url/resourceType/status`，加带请求体请求的 `postData`（最多 4000 字符），**没有响应体**。要读接口返回的内容用 `wait_for_response`（先回看最近 10 秒，再等新响应）或 `get_response_body`（回看最近 100 个响应）。另外 `wait_for_response` 遇到页面**自己**发起的 fetch 时，回调可能迟迟不执行（见本文第 18 条），这时用 `execute_js` 主动发一次同样的请求，或改用 `get_response_body` 回看。**响应体是收到时当场抄下来的**（xhr/fetch、单条上限 10 万字符），所以「等一下再读」「已经跳走了再读」都还能读到；`data.bodyFromCache` 说明读的是缓存。真读不到（非 xhr/fetch）才回 `bodyAvailable:false`，那时用 `wait_for_response` 重新等。
24. **页签索引不稳定**：实测点一次菜单会弹出两个同 URL 的重复页签，这时 `pageIndex` 很容易指错。按 URL 用 `switch_tab_by_url` 切换，用 `close_other_tabs` 清理，别一个个 `close_tab`（索引会整体前移）。
25. **图片类元素只能靠截图接口拿**：`get_browser_state` 只有文本，`execute_js` + canvas 抠图遇到跨域图片会被污染直接失败。用 `get_element_screenshot`（走 Playwright 元素截图，不受同源限制）；智能体本身读不了图时，按 `human-in-loop.md` 请人来看。**但只在确实必须看图时才调它**，非必要不要读图（见 `SKILL.md` 开头的省 token 铁律）。
26. **截图序号是任务级的，不是调用级的**：`seq` 只增不减，`close` 再 `start` 同一个 id 也会接着往上加（文件不删就继续累加）。想要干净的一轮就从空的 `data/<id>/` 目录开始。
27. **`data/<id>/` 里的文件不会自动清理**，长期跑要自己定期清理；服务只监听本机，`/data/**` 也没有鉴权，别把它暴露到公网。
28. **非必要不要读 `get_browser_state`（以及任何自动截图）返回的图片**：`data.screenshot` / `data.screenshot_path` 只是地址，把图读进上下文非常贵，而定位和操作要的信息全在 `data.browser_state` + `data.text` 里。默认只读文本字段；确认页面变化用 `data.changed` / `diff_dom_text`；只有验证码、二维码、图表、纯图片元素这类文本表达不了的场景才取图，并且优先 `get_element_screenshot` 只截那一个元素。详见 `SKILL.md` 开头的省 token 铁律。
29. **登录页白屏时可以换引擎试试 Firefox**：先记录最终 URL、HTTP 状态和控制台错误，区分 `/login` 返回 HTTP 400 的空白页与跳转到 `about:blank`，不要直接归因于沙盒或 DevTools。2026-09-22 国家知识产权局登录页实测中，**Playwright 1.53.0 + Firefox 139.0** 两次正常显示登录表单；**Playwright 1.63.0 + Firefox 155.0** 两次出现 HTTP 412 → 400 后白屏；Chromium 系（本机 Chrome、内置 Chromium、Edge）在该站点一律白屏。遇到类似现象，换引擎 + 全新会话从官网入口重试，并观察至少一分钟。

    **怎么换引擎**：`start` 时传 `browser`，取值 `auto`（默认，本机 Chrome，没装退回内置 Chromium）/ `chromium` / `chrome` / `edge` / `firefox`：

    ```bash
    {"id":"1001","method":"start","params":{"browser":"firefox","headless":false}}
    ```

    也可以给服务配默认值 `browser.engine=firefox`（等价 `browser.type=firefox`），命令行覆盖示例：`mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dbrowser.engine=firefox"`。要点：

    - Firefox 用的是 **Playwright 自带的那份**（打过补丁、走 juggler 协议），本机安装的普通 Firefox 接不上，所以**不要**配 `browser.firefox.path` 指到本机 Firefox。
    - **profile 与 Chromium 那份是分开的**：换引擎等于换一套登录态，Chrome 里登录过的站点在 Firefox 下要重新登一次（反之亦然）。
    - `pdf` 命令只支持 Chromium，Firefox 下会返回明确失败原因；`set_credentials`、`--profile-directory` 这类 Chromium 概念在 Firefox 下同样不适用。
    - 换引擎会重建浏览器，所以只能在「当前没有其它任务」时换（把在跑的任务 `close` 掉再 `start`，不用重启服务）。
    - 这仍然是**排障候选**，不保证适用于所有网站，也不代表已成功登录。详见[问题记录与复测证据](scripts/diagnostics/RESULTS-2026-09-22.md)。

30. **`data.mode=js` 意味着「这次没走真实交互」**：未发现遮挡的元素上，原生点击失败后可能降级成 JS 派发事件；明确被遮挡时 auto 不会穿透点击，对象释放异常也不会自动补点（回执里 `data.mode=js` + `data.fallbackReason`），点击本身通常是有效的。但 JS 设值**不保证进框架的 model**：`input_text` 走 JS 时会回 `data.committed=false`，DOM 上明明有值、预览或提交校验却说「不能为空」就是这种情况——用 `input_text_by_selector` 重填一遍（可见字段默认走真实输入）。**关键步骤（提交、缴费）看到 `mode=js` 要额外确认页面状态**，不要只看 `ok=true`。

31. **`POST /playwright/upload` 与 `/data/**` 一样没有鉴权**：能访问端口的人就能往服务端磁盘写文件（只能写进暂存目录、文件名会被清洗，但文件内容不限）。服务只监听本机时问题不大，**对外部署前必须一起加访问控制**；`browser.upload.enabled=false` 可以整体关掉这个接口。追踪日志默认脱敏（手机号、证件号、邮箱、长数字）但只是尽力而为，日志与暂存文件也都不会自动清理。

32. **强杀服务会留下孤儿浏览器，下一次 `start` 可能卡在启动超时**：浏览器是**独立进程**，杀掉服务（或它的 mvn 进程）**不会**关掉它启动的浏览器。残留的浏览器占着 profile 目录，下一次启动时它既不连上管道也不退出，`start` 就一直等到启动超时（默认 60 秒，见 `browser.launch.timeoutMs`），而错误信息里只有一句 `Timeout ... exceeded`。

    - 服务在 Windows 上会**自动清掉残留痕迹**（profile 里的 `parent.lock` 与 `.startup-incomplete`：前者删得掉就证明没有活着的持有者，后者是「上次启动没走完」的标记），并且**第一次失败后会自动重建驱动再试一次** —— 实测这个重试往往就能成功（表现为第一次等 60 秒、第二次 2 秒起来）。
    - 手工处理顺序：先结束残留浏览器进程（`Get-Process firefox | Stop-Process -Force`），再重启服务。
    - **规范做法是「先 `close` 任务，再停服务」**：关掉最后一个任务时浏览器会一起退出，就不会留下孤儿。关掉全部任务与共享浏览器也可以直接用 `shutdown`（服务进程不退出，HTTP 还能应答）。
    - 有头模式（`headless:false`）下这个现象更容易出现：有头启动比有头慢，且窗口/桌面状态会影响启动。排查时可以先确认「无头能不能起来」（`headless:true`），能起来就说明是环境问题而不是引擎问题。
    - **profile 目录默认按端口分开**（`shared-<端口>`）：同一台机器上跑多个服务实例时，各自用各自的 profile，不会互相抢锁。要用同一份 profile（例如复用已登录的会话）就显式配 `browser.profileDir`。当前解析到哪个目录用 `get_config` 看。
    - **强杀服务前先确认没有活着的任务**：`list_tasks` 能直接看到活着的任务与共享浏览器，不用翻日志猜。

33. **`browser` 参数在老版本发布包上会被静默忽略**：老版本只认 `headless`，传了 `browser:"firefox"` 照样用内置 Chromium 打开页面，回执还是 `ok:true`——实测在一个需要 Firefox 的站点上白排查了很久。现在 `start` 的回执里明确给出 `requestedBrowser`、`effectiveBrowser`、`engineHonored`，不匹配时还会带 `engineWarning`。**看到 `engineHonored:false` 就说明这个服务实例没有按参数切浏览器**，要升级服务端；也可以用 `get_config` 确认服务端认的 `engine`/`configuredType`。

34. **截图与日志不会自动清理**：一次完整的站点操作能攒下上百个追踪文件与几十张截图。定期用 `cleanup` 清理（**默认只预演**，`dryRun:false` 才真删），或用 `browser.capture.enabled=false` 关掉「每次页面变化都自动截图」（显式调 `screenshot` 不受影响）。

35. **报错信息里现在带「下一步」**，不用再自己想到要去查什么：

    - **实例不存在**：`没有找到对应的浏览器实例：2002（本服务进程启动于 2026-09-24 12:09:33,任务实例只存在内存里、重启即失效。先用 list_tasks 看现存任务;确认是重启导致的话重新 start 一个即可——登录态在 profile 里,不会因为这个丢）`。**别被 `retryable:false` 误导**：它不是「这个 id 不存在」，而是「这个进程里没有这个 id」。
    - **索引越界**：形如 `xxx 索引越界: 44,当前索引来自快照(12.3 秒前),共 38 个可交互元素(合法区间 0-37),这期间页面发生过 3 次 DOM 变更。页面变化后索引会全部重算,请重新调用 get_browser_state,或改用 *_by_selector 这类不依赖索引的命令`。有了这些数字就能立刻判断是「索引取错了」还是「页面早就变了」，不必再取一次快照对比。
    - **元素操作失败**：同样附上快照的来历；`data.errorCode` 给机器可读的分类（`STALE_ELEMENT` / `ACTION_TIMEOUT` / `ELEMENT_OBSCURED`…）与 `data.retryable`。
    - **点击没变化**：回执里的 `data.hit` 给出这次真正命中的 `tag`/`text`/`outerHtml` —— 「拿到 `changed:false` 之后毫无线索」这件事没有了。

36. **快照里找不到明明在页面上的元素**：先看 `data.pixels_above` / `data.pixels_below` 是不是非 0 —— 非 0 就说明元素在视口外、**没有索引**。三条解法按优先级：① `get_browser_state` 传 `viewportExpansion`（例如 `1500`）一次拿到首屏之外的元素；② 改用 `click_element_by_selector` / `input_text_by_selector`（不依赖索引）；③ 滚动到目标位置后重新取快照。

    > 这一条以前只写在 `get_browser_state` 的参数表里，症状导向搜不到，于是实测时整场任务都退回手写 JS 扫 DOM。**「元素在视口外所以没索引」与「用 `viewportExpansion` 解决」必须连在一起说。**

37. **整页读不到元素、而页面上确实有内容 → 内容在跨域 iframe 里**：`get_browser_state` 传 `includeFrames: true`（或先 `list_frames`）。这类站点的症状很有迷惑性：回执里只有一个空壳，看起来像「页面还没加载完」或「选择器写错了」，于是反复重取快照。服务现在会在这种情况下主动给 `data.frameHint` 指出下一步。企业微信的**邮件 / 微盘 / 文档 / 会议**四个应用全是这个形态，所以这不是个例。详见 `reading-pages.md`「跨域 iframe」。

38. **「设了值 / 上传了文件 / 派发了事件，但页面没反应」先查监听器**：`get_element_listeners`（或在 `get_interactive_map` 里看 `hasListeners`）。实测企业微信的 `<input class="uploadInput">` 上**一个监听器都没有**，`setInputFiles` 把文件放进去了，但框架的 `change` handler 不存在，组件的 `upload()` 从来没被调用——页面一直提示「请上传工商营业执照」，而 `upload_file` 回的是 `ok:true`。`upload_file` 的回执现在会直接给 `data.consumed`（`listened`/`noListener`/`unknown`）与 `data.hint`；看到 `noListener` 就**别再换选择器了**，改用组件方法直调。

39. **「点了返回 ok 但弹窗还在」先看 `get_modals` 的 `matchedBy`**：`count:0` 现在可信（跑了三轮扫描：框架选择器 / 类名线索 / 几何兜底，`data.scannedBy` 会列出来）；`count>0` 时按 `matchedBy` 判断置信度。企业微信那种自有类名的弹窗（``qui_dialog``、``mall_invoice_dialog_container``）靠类名线索命中，关它用 `which:"class:<子串>"`。

40. **`engineHonored:true` 不说明「这份 profile 里有登录态」**：这是两件事。`start` 的回执现在另外给 `data.profileSeenBefore`（这份 profile 之前用过吗）与 `data.profileNote`（例如「该 profile 目录本次是首次创建,任何站点都需要重新登录」/「引擎从 chromium 切到 firefox:两种引擎的 profile 格式不通用,登录态不通用,需要重新登录」）。看到引擎被换过、又碰上「所有站点都退登录了」，先看这两个字段。

41. **站点 skill 里的非命令标识符请用双反引号**：`SkillDocConsistencyTest` 会把单反引号里的 snake_case 名字当命令名检查。Vue 字段、CSS 类名、HTML id、URL 参数、接口字段这些**页面里的名字**写成 `` ``subject_name`` `` 就不会被误判。完整约定见 `docs/SKILL-CONVENTIONS.md`。

42. **`execute_js` 里的 `.click()` 触发不了「真点击才有的东西」**：JS 派发的 click 不是可信事件，`window.open` 会被浏览器拦掉，部分框架的提交按钮也不认它。实测 12306 结果页的「预订」（`<a class="btn72" onclick="checkG1234(...)">`）用 `.click()` 完全没反应，**而接口照样回 `ok:true`** —— 于是「点了没反应」被误判成页面问题。正解是 `click_element_by_selector` / `click_element_by_index`（真实鼠标事件）。判断有没有生效看回执里的 `data.mode`（`js` 就是没走真实交互）与 `data.changed`。

43. **服务进程被杀 = 它启动的浏览器一起退出 = session cookie 型登录态失效**：12306 这类站点的登录 cookie 是会话级的，浏览器一关就得人工重新登录。实测踩过：agent 的后台任务被回收时带走了 mvn / java / Chrome 整棵进程树，人工白登录一次。所以**别把「重启服务」当成无痛操作**：要么用 `scripts/run/start-server.cmd` / `start-server.sh`（Windows / macOS+Linux，脱离当前进程树启动）与 `scripts/run/stop-server.cmd` / `stop-server.sh`（先关任务与浏览器再结束进程），要么在动手前先确认没有正在进行的人工登录环节。

44. **动作抛了异常 ≠ 动作没生效：别看到失败就重试**。实测点企业微信的「下载合同」时 `click_element_by_selector`
    抛 `Object doesn't exist: response@…`，点 Chrome 内置 PDF 查看器的下载按钮时 `mouse_click` 抛
    `Object doesn't exist: artifact@…` —— **而文件都已经落盘了**。这类「动作成功、事后取证时对象没了」现在
    不再回 `ok:false`：页面确实变了（或确实发生了下载）就回 `ok:true` + `data.warning` + `data.actionError`
    （原始异常）+ `data.downloadsStarted`，所以：

    - 看到 `data.warning` 说「很可能已经生效」时，**先用只读命令确认页面状态**，别直接重发；
    - 回执里的 `data.downloads` / `data.lastDownload` 是「确实下载过」的正向证据（**下载不一定改变 DOM**，
      所以它单独计数、也单独进回执）；
    - 而 `ok:false` 且 `data.errorCode` 是 `ACTION_UNCERTAIN` 时，意思是「执行器抛了未预期异常、**无法判断**
      有没有生效」——同样不该直接重试（重试可能造成重复下载 / 重复提交）；
    - 端点兜底 catch 现在把「参数校验错」（`缺少参数 xxx`，命令根本没发出去，可安全重试）与上面这类
      运行期异常分开了。

    > **放宽是**刻意收窄**的**：只有 `Object doesn't exist`（句柄已失效）这一族才可能被改判为成功。
    > **超时 / 元素不稳定这类异常即便页面变了也照旧失败**——它们是「动作根本没做完」。实测一条回归用例
    > 正好卡在这个边界上：一直在动的元素会持续改变 DOM 指纹，只看「页面变了」就判成功的话，
    > 原生点击超时会被误判成点击成功。

45. **`upload_file` 的回读可能看不到 input，这不是上传失败**。SPA 的上传组件常在收下文件后**把原来的 input
    换掉**（企业微信的授权书上传就是这样），于是回读时那个节点已经不在页面上了。老版本回读走 `Locator`，
    节点一脱离文档就要等满默认 30 秒超时，把一次成功的上传写成
    `readbackError: Timeout 30000ms exceeded.` + `consumed: unknown` —— 看着像失败，一重传就可能传两份。
    现在回读改成**现场重新解析 DOM**（不走 `Locator`，不会再等），并会给出说明：

    - `data.elementGone: true` + `data.readbackNote` → 元素已被框架换掉，**不代表上传失败**；
    - `data.filesLength: 0` + `data.readbackNote` → 框架已经把文件收走并重置了 input，这通常是**成功**信号；
    - `data.consumed` 才是「页面会不会处理它」的结论（`listened` / `noListener` / `unknown`）；
    - **判断上传成没成，以页面为准**（文件名出现、`data.changed`、下一步断言），不要只看 `consumed`。

46. **参数名与回执字段名不一定同名**：`switch_tab` / `close_tab` 的参数叫 `pageIndex`，而 `get_tabs` 回执里每个
    页签的字段叫 `index`。照着回执传 `index` 过去，回执会直接把对应关系写出来
    （`缺少参数 pageIndex（你传的是 index：…）`），照改即可。

47. **成串出现、对象与命令毫不相干的 `Object doesn't exist: response@… / request@…`：这是 Playwright
    事件泵投递过来的伪故障，不是页面坏了。**

    **症状**（一次阿里云控制台的真实任务里连着踩）：`execute_js` 连续 8 次失败，连 `() => 1` 都失败；
    `get_form_state`、`get_element_box`、`go_to_url`、`wait_for_idle`、自动截图跟着一起报，**报错的对象
    每次都不一样**（`response@…` / `request@…`），而同一个页面上 `get_browser_state`、`get_element_count`
    却一切正常。最迷惑人的地方就在这：**换一条不相干的命令也报同一个错**，于是很容易误判成「页面坏了 /
    选择器写错了 / 登录态失效」，然后去改一堆根本没问题的地方。

    **机制**（读驱动源码 + `javap` 字节码确认，不是猜的）：Playwright Java 把 Page 级事件挂在**上下文**上，
    服务器按上下文通道下发 `response` / `request` / `requestFailed`；客户端在
    `BrowserContextImpl.handleEvent` 里按 guid 查对象，**查不到就抛** `Object doesn't exist`。而这一抛
    发生在**消息泵**里，会顺着 `Connection.processOneMessage → ChannelOwner.runUntil` 逃出来，砸在**当时
    正在等待回复的那次 API 调用**上。所以栈长这样（`data.error.stack` 与 `logs/log.<日期>.log` 里能直接
    看到）：

    ```
    PlaywrightException: Object doesn't exist: response@537bc134…
      at Connection.getExistingObject(Connection.java:195)
      at BrowserContextImpl.handleEvent(BrowserContextImpl.java:777)   ← 只有 "response" 分支
      at Connection.dispatch(Connection.java:295)
      at Connection.processOneMessage(Connection.java:214)
      at ChannelOwner.runUntil(ChannelOwner.java:136)
      at FrameImpl.evaluate(FrameImpl.java:277)                        ← 受害者：这次只是碰巧在跑 evaluate
    ```

    根因是「对象已被释放（驱动自己发的 ``__dispose__``，reason 可能是 ``gc``）」撞上「事件还在下发」，
    属于**上游的竞态**：playwright-java#1197（Page close + onRequestFinished 监听器）、#624。

    **升级 Playwright 没用**：1.53.0 与 1.63.0 的 `BrowserContextImpl.handleEvent` 里，只有 `dialog` 与
    `pageError` 两个分支包了 try/catch（对比两者字节码的 Exception table 就能看到），`response` / `request`
    分支的 `getExistingObject` 至今没有兜。**服务端也挡不住**：它在 handler 之前就抛，`safely(...)` 这类
    「把监听器包起来」的写法对它无效（所以「别订阅网络事件」也换不掉这个能力，`get_requests` /
    `get_response_body` 就靠它）。

    **服务端现在这么做**（`ActionError.SPURIOUS_DISPATCH`，`data.errorCode` 里能直接看到这个码）：

    | 命令类别 | 服务端行为 | 调用方该怎么做 |
    | --- | --- | --- |
    | **只读 / 幂等 / 覆盖式落盘**（`get_*`、`is_*`、`diff_dom_text`、`list_frames`、`wait_for_*`、`go_to_url`、`reload`、`screenshot`、`get_element_screenshot`、`pdf`、`set_viewport` …） | **自动重发**（含首次最多 3 次，间隔 120ms）；被吃掉后回执里多一个 `data.spuriousRetry`（含 `attempts`） | 什么都不用做；看到 `spuriousRetry` 就知道这次噪声发生过 |
    | `execute_js` | **默认不重发**（脚本可能有副作用）；调用方在参数里写 `retryOnSpurious: true` 才重发 | 读页面 / 取值的脚本大胆传 `retryOnSpurious: true`；会点按钮、提交表单的脚本**不要**传 |
    | **动作类**（点击 / 输入 / 勾选 / 上传 / 切换页签 / `start` / `close`） | **绝不自动重发**，只把码标成 SPURIOUS_DISPATCH（执行器抛异常时是 ACTION_UNCERTAIN + `data.spuriousDispatch: true`） | **先读页面状态再决定**：重发可能重复提交。看到这一类报错别去改选择器，那跟选择器无关 |

    自动截图与 `get_element_screenshot` 的内部实现也各自重发一次（`PlaywrightService.spuriousRetry`），
    所以「第 N 张截图失败:Object doesn't exist」这类日志会明显变少。

    **怎么确认不是别的问题**：报错文本里出现 `Object doesn't exist` / `Cannot find object to call`，且
    **同一步换一条只读命令能正常读到页面** —— 那就是它。反过来，`Object doesn't exist: elementHandle@…`
    也可能是**你自己那条命令的目标句柄真的失效了**（元素被页面换掉），文本上分不开；所以服务端只拿它当
    **重发**的依据（只读命令重发无害），不用它下「动作成功了」的结论。

48. **自动截图可能整页截不出来，而回执现在会明说（``capture_degraded``）**。实测 B 站投稿页上
    `page.screenshot()` **一次都没成功过**，每次都等满默认 30 秒：结果是**每条命令白等 30 秒**，
    而调用方**完全没有画面可看**，页面上出现过整页白屏也判断不出来（当时是等人来说「屏幕都白了」
    才知道）。现在自动截图连续失败到阈值就**熔断**一段时间，并在每条回执里给出：

    ```
    capture_degraded: true
    capture_note: 自动截图不可用(连续 N 次失败,已暂停 X 秒,首次失败原因:Timeout …)…
    ```

    **看到 ``capture_degraded`` 就要改变工作方式**，而不是照原样继续点：① 画面取证改用文本
    （`get_browser_state` / `diff_dom_text` / `execute_js` 读 DOM）；② **不要**只凭文本断言「页面正常」
    ——白屏、样式错乱、遮罩在文本里看不出来；③ 关键步骤（提交、支付、扫码）请人看一眼
    （`request_human_input`）。调参：`browser.capture.timeoutMs`（单次超时，默认 8000）、
    `browser.capture.failThreshold`（连续失败几次熔断，默认 3）、`browser.capture.cooldownMs`（默认 120000）。

    实测**有些站点是「必然截不出图」而不是「偶发失败」**：百度搜索资源平台后台连续几十条命令全部
    `ACTION_TIMEOUT`，冷却结束后重试仍然失败，整场任务一张图都拿不到。这类页面上不要反复重试截图、
    也不要等它「自己恢复」：直接全程改用文本取证（`get_browser_state` 的 `data.text`、`execute_js` 读 DOM
    当唯一证据），需要画面时请人看一眼浏览器窗口。**别把「截不出图」当成「页面有问题」。**

49. **选择器匹配 0 个 = `ELEMENT_NOT_FOUND`，不是超时**。以前这种情况会等满动作超时再报
    `ACTION_TIMEOUT` + 一句「不能据此确定元素不存在」——两种含义混在一句话里，于是调用方只能两条路都试
    （查监听器、查遮挡），方向全错。实测把一个 input 的**兄弟关系写成了父子关系**
    （`.tag-pre-wrp input.input-val`，而那个 input 其实是 `.tag-pre-wrp` 的兄弟），白花了两轮。

    - 看到 `ELEMENT_NOT_FOUND`：**去改选择器**，先 `get_element_count` 复核数量，再检查父子/兄弟关系；
    - 匹配到**多个**同名控件时，动作类命令会**优先挑可见的那个**，并把解析结果写进回执
      （`matched` / `chosenIndex` / `scanned` / `selectorNote`）。站点的同名控件常有两份，
      另一份在隐藏的弹窗里且是 0×0 —— 这时 `get_element_count` 说「有 2 个」而 `locator.first()` 挑到的
      正是隐藏那份，表现就是「明明有元素却怎么都点不动」；
    - 匹配到的元素**全都不可见**时**不判死**（`input_text_by_selector` 的 auto 模式要靠 JS 设值把值填进
      `type=hidden` 字段），回执会给 `visibleMatched: 0` 与 `hiddenMatchNote`。
    - **只作用于「动作」命令**：`wait_for_element`（等的就是「现在还没有」）与 `upload_file`
      （file input 天生隐藏）**不适用**这套挑选规则。

50. **回执里有 `probeTrustworthy` / `observationComplete`，它们说「这次取证能不能当结论」**。
    页面正在导航、或 SPA 整页重建（Vue 重挂载、micro-app 重建）时取的探针**什么都不能说明**，
    而老回执会给出这种看着像结论、其实全错的组合：

    ```
    observationComplete: false, textLengthAfter: 0, changed: false, coveredBy: {className: "header"}
    hint: …目标中心点上命中的是别的元素——很可能被遮挡物吃掉了…
    ```

    实测（B 站投稿页打开封面弹窗、点「立即投稿」）这两次点击**都生效了**，`coveredBy` 报的
    `div.header` 是页面被清空时 `elementFromPoint` 撞到残留节点的产物，**那个遮挡物根本不存在**。
    现在服务端的行为是：发现探针不可用就**先等页面回来再下结论**（最多约 2.5 秒），等到就标
    `page_appears_blank: true` + `probeRecovered: true`（说明 changed 是恢复后测的），等不到就
    `probeTrustworthy: false` 并**盖掉遮挡提示**。

    **调用方的判据**：`probeTrustworthy` 为 `false` 时**什么都别下结论** —— 既不要重复点击（可能重复提交），
    也不要急着重新取快照（页面还没稳），等几秒重新 `get_browser_state` 看真实结果。

51. **`frame` 为 null 是「页面正在导航」，不是动作失败**。用户手动刷新、SPA 整页重建时，只读命令会抛
    `Cannot invoke "com.microsoft.playwright.Frame.childFrames()" because "frame" is null`。以前它被归成
    `ACTION_UNCERTAIN`（「无法判断是否生效，不要重试」）——对一条只读命令来说这是最糟的答复：明明重发一次
    就好，却告诉调用方别动。现在单独给 `PAGE_NAVIGATING` 并标成**可重试**（建议 1 秒后重发），
    只读命令服务端会自己等页面回来。

52. **`send_keys` 会回报按键时的焦点元素**。实测「填完输入框 → `send_keys` Enter」会**悄悄什么都不做**
    （焦点已经不在那个 input 上了），而回执只有一句 `ok:true`，调用方只能靠「结果没多出来」反推，白跑
    一轮。现在回执里有 `focused`（`tag`/`id`/`className`/`placeholder`/`isBody`）；焦点落在 `<body>`
    上时还会给 `focusNote` 提示「先 click 目标输入框再送键」。

53. **导航报伪故障时，地址栏才是答案**。实测 `go_to_url` 连试 3 次都报
    `Object doesn't exist: response@…`，随后 `get_url` 读到的**正是目标地址** —— 报成失败会直接把人
    带偏去排查「为什么打不开」。现在幂等导航会读一次地址栏：已经落在目标上（比较主机+路径，
    忽略站点自动追加的 `?vd_source=…` 这类会话参数）就按成功返回，并带 `data.warning` 与
    `data.spuriousDispatch`。

54. **截图里可能混进 get_browser_state 画的彩色高亮层 —— 二维码/验证码会因此**完全不可用**。**
    实测在 DeepSeek 平台的收银台上：微信支付二维码是一个 160×160 的 `<canvas>`，
    `get_element_screenshot` 截出来是"有内容"的图，但**人拿手机怎么都扫不出来**，因为我们自己画的
    `#playwright-highlight-container`（每个可交互元素一个彩色框、`position:fixed` 全屏覆盖）被带进了镜头。
    这件事在文本里完全看不出来 —— 只有把图交给人才会暴露，所以症状是"你给的图我扫不了"，而不是"报错"。

    **确诊办法：不要靠眼睛看图，做一次像素直方图**（把 PNG 的颜色统计出来）。正常二维码只该有黑白灰；
    出现 `255,165,0`（橙 `orange`）、`70,130,180`（钢蓝 `steelblue`）、`220,20,60`（绯红 `crimson`）
    这些**高亮层专用色**就实锤了。实测污染版 `distinctColors=16+`，干净版 `distinctColors=5`。

    **服务端现在这么做**：`screenshot`、`get_element_screenshot` 与**每次动作后的自动截图**
    （`PlaywrightService.withHighlightHidden`）在拍之前把高亮层 `display:none`、拍完还原 ——
    `extract_structured_data` 早就是这么干的，这次把同一套做法推广到了所有截图口。隐藏失败不阻断截图。

    **如果手上是旧构建**（`data.url` 给的图仍是花的）：先用 `execute_js` 把高亮层从 DOM 里删掉再截，
    顺序不能反、中间不要再调 `get_browser_state`（它会把高亮层画回来）：

    ```json
    {"body": "() => { const c=document.getElementById('playwright-highlight-container'); let n=0; if(c){c.remove();n++;} document.querySelectorAll('.playwright-highlight-label').forEach(e=>{e.remove();n++;}); return {removed:n}; }"}
    ```

    回归用例：`BrowserObservationUpgradeTest#elementScreenshotExcludesHighlightOverlay`、
    `#actionCaptureAlsoExcludesHighlightOverlay`。

55. **按文本点击会点中"包含"这个词的更长的容器 —— 回执 `ok:true` 而真按钮一动没动。**
    实测在 DeepSeek 的开票表单上敲 `click_element_by_text` 传 `text=Submit`，命中的是「Invoice Rules」
    那段说明文字，因为第 4 条写着 "cannot be changed once **submit**ted"：
    `page.getByText(<字符串>)` 是**大小写不敏感的包含匹配**，而老实现取的是**文档顺序里的第一个候选**，
    于是点了一个两千多字符的纯文本容器。

    现在按元组 `[是否完全相等, 是否有可点击祖先, 是否可见, 文本长度]` 字典序打分取最优
    （文本长度放最后是兜底：即使都不完全相等，也该点短的那个），并把匹配真相写进回执：

    | 字段 | 含义 |
    | --- | --- |
    | `data.textMatch` | `exact` 完全相等 / `contains` 只是包含 |
    | `data.textLength` | 命中元素的文本长度（两千多 = 点到正文了） |
    | `data.textCandidates` | 这次有几个候选 |
    | `data.textClickable` | 命中的元素有没有可点击祖先（`false` 时点它很可能什么都不发生） |
    | `data.textMatchNote` | 可疑时给出的下一步建议 |

    **判据**：`ok:true` 之外还要看 `data.textMatch` 与 `data.hit.text`。能拿到索引就用 `click_element_by_index`，
    能写选择器就用 `click_element_by_selector`，按文本点是兜底手段。
    回归用例：`BrowserObservationUpgradeTest#textClickPrefersExactMatchOverEarlierContainingContainer`。

56. **`get_form_state` 对"自定义下拉"（`ds-select` / `ant-select` 那类）会**同时**骗你和漏报：值的归属与错误态。**
    实测 DeepSeek 开票表单的抬头是 `ds-select`，它在两个不同状态下会给出两种相反的错觉：

    | 状态 | ``input.ds-select__input`` 的 `value` | 显示节点 | 容器类名后缀 |
    | --- | --- | --- | --- |
    | 只打了字、**没点 option**（未落库） | **就是你打的字** | 只有占位符 | `--error` |
    | 点中 option（已落库） | **`""`（被组件清空）** | 真值在这里 | `--none` |

    于是：

    - **未落库时**：`get_form_state` 会报出一个**看起来完全正常的值**（其实是过滤框里的文本），
      而字段还在 `--error`、提交时照样说"必填"——**"有值"不等于"落库"**；
    - **已落库时**：真值只存在于组件的显示节点里，`input.value` 是空的，
      老实现只读 `input.value` → 把**已经填好的**字段报成空值。

    现在服务端：

    - `input.value` 为空时去组件根里找显示节点，把值报在 `value` 里并加
      **`valueFrom: "display"`**（普通字段仍是 `"dom"`）。**看到 `valueFrom:"display"` 就别拿它去跟
      `document.querySelector(...).value` 比对**，对不上是正常的；占位节点（``.ds-select__placeholder``）会被跳过，
      不会把占位符当值；
    - 错误态除了外层 `form-item`，也看**控件自己与它父节点**的类名（只认"整词"形状的 error，
      免得把 `errorBoundary` 之类误判）。实测 `ds-select--error` 挂在**组件自己**身上、外层 `form-item`
      干干净净，老版本于是给出 `errorCount:0` 而字段明明是红的。

    **判断这类下拉到底落库没有，一次读三样**（值 / 显示节点文本 / 容器类名），
    别只看其中任意一个（可复制的 JS 见 `deepseek-platform-topup-invoice` skill 第 4.3 节）。
    回归用例：`BrowserObservationUpgradeTest#formStateReadsValueOfCustomSelectFromDisplayNode`、
    `#formStateDetectsErrorClassOnTheControlItself`。

57. **`wait_for_idle` 在带轮询/动画的页面上永远等不到，而它在 `commands` 里失败会把后面所有步骤都吃掉。**
    实测 DeepSeek 的 `/top_up` 页上 `wait_for_idle` 等满 20 秒都没安静（在途请求 21 个、DOM 变更 1157 次）——
    这页有轮询。**"忙不忙"（`wait_for_idle`）与"内容变没变"（`wait_for_stable`）不能互相替代**：
    页面可能一直在动而你要读的内容早就定下来了，这时用 `wait_for_stable`。

    另一半是批量的默认行为：`commands` 的 `stopOnError` 默认 `true`，一条等待超时会让**后面的命令一条都不跑**
    （实测那次的 `get_browser_state` 就没执行，等于白跑一轮、还看不到页面）。
    批量里显式加客户端的 `--keep-going`（= 服务端 `stopOnError:false`），让每一步的结果都回来。

58. **首次 `start` 会卡在「Playwright 下载浏览器」上，而不是卡在起 Chrome 上 —— 判据是 `list_tasks` 的 ``launching``。**
    实测在一台全新的机器上，`start --browser chrome`（用的明明是**本机已装的 Google Chrome**）整整
    十几分钟没有返回：服务端日志里是 `Downloading Chromium 138.0.7204.23 …`、`Downloading Firefox …`、
    `Downloading Webkit …`（约 700MB）—— Playwright 的驱动在被第一次调用时会把**它自己管理的那些浏览器**
    一起下载/升级，与这次到底用哪个可执行文件无关。这一步**不受 `browser.launch.timeoutMs`（默认 60 秒）约束**，
    所以表现是「请求挂住」而不是「启动超时失败」。

    三条判据，按顺序看：

    - `list_tasks` 的 ``launching`` 字段非 `null`（带 `startedAt` / `elapsedMs` / 说明）：就是正在起共享浏览器，
      **别重复 `start`**、也别去查端口；这次实测抓到的现场是 `count:0` + ``launching.elapsedMs`` 一路在涨；
    - 服务端日志（`logs/server/server-<端口>.out.log`）里有 `Downloading …` / `Removing unused browser …`；
    - 什么都没下、`launching` 也是 `null`，才去看端口与进程。

    只想「预热一次」的话，先跑一遍 `./client/dsb selftest --browser chrome` 把这一步摆在明面上。

59. **服务起不来时，先分清是「JDK 跑不起来」还是「版本太低」—— 两者的日志长得完全不一样，而脚本从前的提示都不沾边。**
    实测两次都是环境问题、却都被引到「端口没被覆盖」上：

    - `JAVA_HOME` 指向与本机 CPU 架构不匹配的 JDK（macOS arm64 上装了 x86_64 的 JDK）：
      `java -version` 自己就挂（`rosetta error: Attachment of code signature supplement failed`，退出码 134），
      连 `java` 都跑不起来；
    - JDK 版本低于编译用的版本（本仓库 `pom.xml` 是 `java.version=21`，产物 class file version 65.0）：
      日志里是 `UnsupportedClassVersionError: … class file version 65.0, this version … up to 61.0`，
      进程其实**已经退出**，看起来却像「启动慢」。

    现在 `scripts/run/start-server.sh` / `start-server.ps1` 会先探一次 java（跑不起来或低于 21 就直接报并退出），
    健康检查失败时再按日志归因（`UnsupportedClassVersionError` / `rosetta error` / `Address already in use` /
    `BUILD FAILURE` / `Downloading`）。**换 JDK 请连 `JAVA_HOME` 一起换**，别只改 PATH。

60. **`get_response_body` 传 `requestId` 读不到时，三种原因要分开看（回执现在会直接告诉你哪一种）。**
    实测拿 `get_requests` 回执里的 `requestId` 去读响应体，只得到一句
    `没有匹配的响应: null` —— 那个 `null` 是 `filter`（压根没传），传进去的 `requestId` 一个字都没出现，
    于是「id 不存在 / 还没收到响应 / 响应被挤出去了」在调用方看来一模一样。现在的回执按请求记录回查后给出结论：

    - 「这个 requestId 不在请求记录里」→ 它不是这个任务页签记到的（记录从认领页签那一刻才开始，最多 200 条）；
    - 「这条请求还没有收到响应（`status` 是 null）」→ 实测最常见的一种：页面刚发出的请求还没回来，
      改 `wait_for_response` 等一个新响应，或稍后再读；
    - 「响应体已经不在最近 100 条里了」→ 只保留最近 100 条，要留证据就在动作发生的当下读。

    另外 `get_requests` 回执里的 `requestId` 是数字：命令行直接写 `-p requestId=<数字>` 即可
    （实测能正确绑上并匹配到请求记录），不要自己去猜它的类型。

61. **实时页面上的索引曾经「永久失效」：判据看错了东西。**
    在实时行情页（价格文字每几秒刷新、状态灯换 class）上，**每一次**按索引操作都只拿到
    `索引越界: 20,当前没有页面快照,请先调用 get_browser_state 获取元素索引`，重取快照也一样，
    于是整场任务只能退回选择器与文本定位。原因不是页面有问题，而是旧判据**只要整个文档在读取期间
    发生过任何变更就把整份快照作废** —— 而这类页面永远在变，所以索引永远是死的。

    判据本身看错了对象：索引最终解析成的是**位置型 xpath**（形如 `html/body/div[2]/form/input[1]`，
    只有同级序号、没有 class 谓词），因此

    - **内容类**变更（`characterData` 文字改写、`class`/`style` 属性变化）**不会**挪动任何同级序号，
      位置型 xpath 照样指回同一个元素 → 不该作废；
    - **结构类**变更（`childList` 里增删了元素节点）会挪动同级序号 → 索引区间确实不可信，照旧作废。

    现在按这个分界判，并且**额外做一次逐元素重校验**：读到的每个索引按它的 xpath 再查一次
    （`get_browser_state` 本来就在做这件事，结果落在元素的 `resolved` 字段上），再比对标签与
    `id`/`name`，对不上才作废。这比单看变更计数**更强** —— 计数没变但某个 xpath 已经指向别的元素时，
    旧判据反而发现不了。

    回执里新增的字段就是用来判断这件事的：

    | 字段 | 含义 |
    | --- | --- |
    | `data.snapshotStructuralMutations` | **本次读取期间**增删元素的次数（增量，不是页面累计值）。为 0 说明元素结构没动 |
    | `data.snapshotContentMutations` | 本次读取期间文字/属性变化的次数。不为 0 但结构为 0 时索引仍然可用 |
    | `data.snapshotNote` | 一句话解释「为什么这次没作废」 |
    | `data.snapshotStrict` | 本次用的是哪种判据 |

    实测同一页面前后对比：修复前 `indicesUsable:false` +「当前没有页面快照」；修复后
    `indicesUsable:true`、`snapshotContentMutations:1`、`snapshotStructuralMutations:0`，
    按索引点 `Trade` 按钮回执 `changed:true`、`hit.text:"Trade"`。要退回旧的严格判据传
    `strictSnapshot:true`（排查用；它会让实时页面重新变得没法按索引操作）。

62. **`--select` 碰上失败响应会整封打印，正好在最需要它的时候失效。**
    批量回执里**只要有一步失败，整批的 `ok` 就是 `false`**，但 `data.results[N]` 依然完整返回。
    客户端以前一看到 `ok:false` 就跳过投影、把整封打出来，于是「用 `--select` 只看失败的那一步」
    这个最自然的用法恰好失效（实测一批 6 步、只想看第 4 步，每次都被上千行整封回执淹掉）。

    现在**失败响应照常按路径投影**；只有路径确实不存在（例如单条命令根本没有 `data.results`）才退回整封，
    并在 stderr 说明是退回去了，免得调用方以为投影生效了。业务退出码不变。

63. **`--params` / `batch` 现在认「整个请求体」。**
    手边常常已经有一份完整请求体（留档文件、文档示例、别人贴过来的 curl 载荷），原样存成文件喂进去最自然：
    `{"id":1001,"method":"request_human_input","params":{…}}`。以前会把 `id`/`method` 当命令参数一起发下去，
    真正的参数一个都没传，报回来的却是 `缺少参数 prompt` —— 完全指不到原因。现在按 `method` 字段识别，
    只取 `params` 那一层。`batch` 同样认三种写法：纯数组、`{"commands":[…]}`、整个请求体。

64. **`js` 缺一个「伪故障重发」开关。**
    `execute_js` 是只读场景的主力，却最容易撞上 Playwright 事件泵投递的伪故障
    （`Object doesn't exist: response@…`，见第 47 条）。服务端早就支持 `retryOnSpurious`，但客户端没有开关，
    调用方只能自己手拼 `{"body":…,"retryOnSpurious":true}` 走 `--params` —— 等于把「用 dsb 少踩坑」
    这件事又还回去了。现在有 `js @脚本.js --retry-on-spurious`。
    **只给只读脚本加**：会点按钮、提交表单的脚本重发等于再执行一次。

65. **`send_keys` 只认键名，于是「往输入框里打一段文本」变成了没有一条命令顺手。**
    实测要在一个搜索框里输一个四位代码，`send_keys keys:"CRCL"` 直接得到 Playwright 的
    `Unknown key: "CRCL"`；而紧接着的两条退路都不好走 —— `input_text_by_selector` 要选择器
    （隐藏输入框还够不着），`type_text` 要**元素索引**（得先取一次快照）。
    调用方的意图其实毫无歧义，所以现在 `send_keys` 按**输入形态**分流（不是猜意图）：

    - 单键名（`Enter` / `Tab` / `ArrowDown` / `F5`）与「修饰键 + 恰好一个键」的组合
      （`Control+A` / `Shift+Enter` / `Control+Shift+T`）走 `press`；
    - 其余一律走 `type`（逐字符打字，会触发 `keydown/keypress/input`，受控输入框也认）。

    回执里 `data.mode` 是 `press` 还是 `type` 直接写明走了哪条路，`data.keys` / `data.text` 回报打进去的内容。
    注意 `Control+A+B` 这类**更长的不算组合键**（`press` 只吃「修饰键 + 一个键」），会当文本打。

66. **图表上的「文字」读不出来，因为它是 canvas 像素 —— 而且 canvas 根本不进快照。**
    实测读行情图表的时间轴日期：`innerText` 是空，换选择器、重取快照、等加载全都白费。
    两件事叠在一起造成误判：① 刻度与浮标画在 `<canvas>` 上，**元素里没有文字节点**；
    ② canvas 不可交互，`buildDomTree` 不给它索引，所以它既不在 `data.text` 里，也够不到
    「按索引读文字」这条入口。现在 `get_element_text` 支持 `selector`（够得到 canvas 这类元素），
    并可传 `canvasOnly:true`：读不到文字时回 `data.canvasOnly` 与 `data.hint`，直接说明「不是选择器错了」。
    正确解法按优先级：读页面上**同时是文字**的地方（图例 / 数据面板 / 工具栏）→ 用鼠标按住读图上那一点
    （见第 67 条）→ 实在只能读像素才截图 + `ocr_image`。

67. **「按住不放」是读图表上某一个数据点的正解，但必须记得松手。**
    四步：`mouse_move` 到目标点 → `mouse_down` → 读文本（图例 / 状态行的 `innerText`）→ `mouse_up`。
    按下期间十字线被钉住，图例从「最新一根」切换为**光标锁住那一根**自己的 `O/H/L/C/量` ——
    这比截图像素可靠得多，也不必读图。**漏了 `mouse_up` 会一直按着**，之后每一次点击都会变成拖拽。
    时间轴上的日期标签同样是 canvas 像素，读不到；要确定「按住的是哪一天」，用页面上的文字线索
    （详情里的 `Last update at …`、`document.title`）加系统时钟（休市日 → 最后一根就是上一个交易日）来推。

68. **动作命令撞伪故障时，从前只有「可能已生效，别重试」这一条路 —— 而伪故障里有一大支其实根本没发出去。**
    实测在重度 SPA 的图表页上，**同一个** `click_element_by_selector` 一会儿成功、一会儿报
    `[SPURIOUS_DISPATCH]`，页面完全正常。老策略把动作类一律排除在重发之外（怕重复提交，这个担心是对的），
    但调用方在只读诊断确认「上次没生效」之后，仍然没有任何办法让服务端替它吃掉噪声，只能绕道去按坐标点。
    现在多了一条**按调用点声明**的口子：任何命令都可以带 `retryOnSpurious: true`
    （客户端 `dsb run … --retry-on-spurious`、`dsb js … --retry-on-spurious`），服务端才会重发。
    **动作类默认仍然一次都不重发** —— 这条界线交给最清楚语义的调用方，服务端不替它猜。

    建议的三步处置：① 先用只读命令确认上次到底生效没有；② 确认没生效 → 带开关重发；
    ③ 连 SPD 都过不去 → 退回**按坐标点**：`execute_js` 取 `getBoundingClientRect()` 算中心坐标，
    再 `mouse_move` + `mouse_click x y`（不依赖 DOM 节点句柄，在重度 SPA 上最稳）。

69. **传了参数却好像没生效？回执里现在会直接点名（`unknownParams`）。**
    命令表的每个执行体各自读自己认识的键，**没有一份声明式的参数清单**，所以拼错的参数名从前是
    **被静默丢弃**的：回执照样 `ok:true`，调用方以为自己的意图生效了。实测踩过：想让人工待办活得久一点，
    凭直觉传了 `expiresInSeconds:3600`，而 `request_human_input` 当时只认 `timeoutSeconds` ——
    待办按默认 **300 秒** 建好，框架还没来得及把请求推给人，它就已经过期，而回执里一个字都没提。

    现在每条命令跑完都会比对「传进来的键」与「真正被读过的键」，多出来的写进回执：

    ```
    unknownParams: ["expiresInSeconds"]
    unknownParamNote: request_human_input 忽略了这些参数:expiresInSeconds。它们没有被这个命令使用,
                      多半是名字写错或该命令不支持——本次实际用到的参数是:prompt, steps。
                      完整参数表见技能文档 references/commands.md。
    ```

    - 这是**只加说明、不改结论**：命令该成功还是成功。动作类命令一旦被翻成失败，调用方很可能重发，
      而重发等于重复点击、重复提交（与第 68 条是同一条理由）。
    - **看到 `unknownParams` 就要当回事**：它说明你这一次的意图没有被完整执行。先按 `unknownParamNote`
      给出的「实际认的参数」改过来，再发一次。
    - `retryOnSpurious` 与 `async` 是分发层读的框架级参数，不会进这个名单，可以放心带。
    - 顺带记一处同源的不对称：`request_human_input` 的**回执**里本来就输出 `expiresInSeconds`，
      而请求从前不收它 —— 照着回执写回来是最自然的写法，却正好踩空。现在两个名字都收，优先级是
      `expiresAt`（绝对毫秒时间戳）> `expiresInSeconds` > `timeoutSeconds` > 默认 300 秒。

70. **自定义控件里的按钮，通用「扫一遍页面上的可点元素」扫不到；控件容器 `height:0` 也不代表没渲染。**
    实测百度账号换绑手机号时，「换个验证方式」这个入口在安全控件 ``#bindPcNewAuthwidget`` 内部，
    是 ``<a class="change-type pass-link">``。外层按「取所有 `a`/`button`/`div`/`span` 再筛短文本」扫，
    **只扫得到控件外面那几个**，于是得出「没有别的验证方式」的错误结论 —— 而「银行卡信息验证」这条路
    恰恰就藏在那一步后面。**是用户提示「百度支持银行卡验证」，才把这个入口找回来的。**

    同一页面上还有第二个诱饵：控件容器自己的几何是 ``height:0``、``top`` 还被推到视口外，看起来像
    「控件没渲染出来」；但它的 ``innerText`` **是有内容的**（「验证方式选择 / 手机号是否可用于验证」），
    点击也照常生效。

    处置办法：

    - 定位到控件容器后，**在容器内部再扫一遍**（``container.querySelectorAll(...)``），不要只扫整个文档；
    - 判断「渲染出来没有」用 ``innerText`` / `get_element_text`，**不要用几何尺寸**；
    - 页面上挂着「系统繁忙，请稍后重试」这类 toast 时先别当成失败：实测这个 toast 全程都在，
      控件内容照样渲染、点击照样生效。

71. **快照只读到一部分时，结论可能是反的 —— 这一条是拿一次实打实的误判换来的。**
    实测腾讯云控制台「我的域名」页：快照里只有 **6 行**，我据此断言「你问的这个域名不在你的账号里」，
    并把它当成事实讲给用户，让用户按「域名写错了」去自查一轮。用户坚持说就在账号里，复查才发现账号
    实际有 **11 个域名**，第一次只是**页面还没渲染完**（SPA 先把骨架和一部分行画出来，数据随后补齐）。
    **用户是对的，我错了。**

    判据与做法：

    - 读完列表类页面，把 `data.text` 里的行数**和页面自己给出的计数对一下**（「共 N 条」「1 / 2 页」
      这类文字就在同一屏里）。对不上就别下结论；
    - 对不上时用 `wait_for_stable` 等内容稳定后重取，或**换一条独立通道交叉验证**
      （页面自带的搜索框、`get_requests` 看列表接口的返回、翻到第 2 页看看）；
    - 这是个通用形态：**「没读到」不等于「不存在」**。凡是要对用户说「没有 / 不在 / 查不到」之前，
      先确认自己拿到的是完整视图，而不是半张。

72. **要用户提供敏感字段时，先问他「你自己填，还是告诉我我来填」，别替他决定。**
    实测往换绑手机号的表单里填**完整银行卡号 + 持卡人身份证号**时，我直接替用户做了决定——在正文里写
    「这一步交给您自己的手最合适」，还顺手劝了一句「请不要发到对话里」。用户当场纠正：**正确做法是把
    选择权交回去**，逐个字段问「您自己填，还是告诉我我来填」。他实际的选择是卡号与身份证号**告诉我**、
    验证码**我提交** —— 这些不问就永远猜不到。更贵的一次是用户已经明确提示过「你应该继续问」，
    我仍然只把「请把值发我」写在正文里、没有真的发起提问，于是又白走一轮。

    处置办法：

    - 需要用户提供**任何只有他知道的东西**（卡号、身份证号、验证码、二选一）时，用提问去要
      （agent 侧 ``ask_user_question``，浏览器侧 `ask_user`），**不要在正文里顺带一提就当问过了**；
    - **给选项**：至少给「我自己填」与「告诉你，你来填」两条路。给了选项用户点一下就能答；
      但**要拿到具体值就别只给选项** —— 只给选项时用户点完就结束了，值并没有传过来（实测踩过：
      用户选了「在这里输入卡号给你」，可回答里只有选项本身，只好再问一次）；
    - 用户选了「我自己填」之后，**不要再把值要过来**；你的职责转成「把表单准备好、把焦点交给他」；
    - 说过的承诺要算数：讲了「验证码我不经手」，下一步就别再问他要验证码。
