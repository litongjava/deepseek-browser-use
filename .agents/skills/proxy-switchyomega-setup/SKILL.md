---
name: proxy-switchyomega-setup
description: 在正在运行的 Chrome 中安装 Proxy SwitchyOmega 3（ZeroOmega），导入用户本地配置，设置 GAE-Proxy 地址并启用自动切换。包含中国大陆网络环境下的离线包、指定镜像和已有代理下载方式，以及精简输出和隐私保护。
---

# ZeroOmega 安装与自动切换

用于配置用户已有的网络代理。扩展本身不提供网络服务。使用中性描述：扩展安装、网络代理、自动切换和连接验证；不把私人规则列表、访问记录或环境信息写进公开文档。

浏览器操作遵循 [deepseek-browser-use](../deepseek-browser-use/SKILL.md)。复用宿主 session 的浏览器绑定，以下 `dsb` 命令不手动传 `--id` 或 `--session`。独立 CLI 的 `--session` 仅是留档标签，需由宿主或环境变量提供任务上下文。

## 1. 准备与下载

- Windows 优先 Git Bash；路径传给 Python/Node 等 Windows 程序时用 `cygpath -w` 或 `C:/...`。Windows 原生进程信息查询才用 PowerShell，仅读取所需字段。
- 找到用户配置文件和当前 Chrome 的 profile，读取其 `DevToolsActivePort`，不能写死端口、扩展 ID 或工作目录。复用已有浏览器，不为安装重启共享服务。
- 核对扩展名称：旧 SwitchyOmega 与 ZeroOmega 不混用。此流程使用支持 Manifest V3 的 ZeroOmega。
- 先检查本地已下载包；需下载时使用下方工具。默认固定已验证的 v3.5.2，避免每次获取完整 GitHub release JSON。升级版本时先从可信官方发布记录核实对应 SHA-256。
- 中国大陆环境可使用已有本地代理 `--proxy http://127.0.0.1:10808`，或用户/组织指定的完整 HTTPS 下载地址 `--url`；镜像内容仍须匹配可信 SHA-256。没有可用网络时使用 `--archive` 离线包。不要自动选择来历不明的镜像，也不要关闭 TLS 校验；不预设某下载站在所有地区可用。

从技能目录运行（参数路径为示例，按用户环境替换）：

```bash
python scripts/prepare.py package --destination 'C:/BrowserTools/ZeroOmega/3.5.2' --proxy http://127.0.0.1:10808
python scripts/prepare.py package --destination 'C:/BrowserTools/ZeroOmega/3.5.2' --archive 'C:/Downloads/chromium-release.zip'
```

以上为二选一。`--url` 传指定镜像的完整 zip URL；代理和下载 URL 不含凭据。脚本只打印版本与校验状态，错误不回显路径、URL 或配置正文。解压目录长期保留，不覆盖已有目录；失败时选择新目录重试。

## 2. 精确修改配置副本

```bash
python scripts/prepare.py config --input 'C:/Private/OmegaOptions.bak' --output 'C:/Private/OmegaOptions.local.bak' --host 127.0.0.1 --port 10808
```

脚本解析 JSON，仅修改 `+GAE-Proxy.fallbackProxy` 的 host/port 和 `-startupProfileName`，保留原代理协议及其他配置；必须存在 `GAE-Proxy自动切换`，输出文件不得覆盖原文件。这里的 HTTP 协议来自用户配置；端口同时支持 HTTP/SOCKS 时不擅自换协议。先用对应协议验证用户指定的测试地址，仅输出 HTTP 状态，不输出响应头或正文。

配置副本包含原始私人数据，留在用户指定本地目录，不提交或上传外部服务。检查只使用脚本摘要，不执行 `cat OmegaOptions.bak`；单行 JSON 的 `head`/`Get-Content -TotalCount` 仍可能输出完整十几万字符。禁止全文打印规则正文、订阅 URL、凭据、Chrome Preferences 或 storage。

## 3. 加载扩展

先检查当前浏览器是否已有 ZeroOmega；已有时复用，避免安装第二份竞争代理设置。可用 `chrome://extensions/` 或工具的 `inspect` 模式确认。`inspect` 仅列 CDP 可见的未打包扩展，不能据其空列表断言商店版不存在。

```bash
node scripts/extension.mjs inspect --profile 'C:/BrowserProfile'
node scripts/extension.mjs load --profile 'C:/BrowserProfile' --extension 'C:/BrowserTools/ZeroOmega/3.5.2'
```

脚本从指定 profile 读取实际 CDP 端口，使用浏览器级 `Extensions.loadUnpacked`，输出实际扩展 ID 和 enabled 状态。它是 dsb 尚未提供扩展安装命令时的专用 CDP 补充，不用于替代普通页面操作。需要 Node.js 22+。

CDP 不支持或拒绝加载时，使用 `chrome://extensions/` 的开发者模式 → 加载已解压的扩展，选择同一目录；不要修改全局策略或强制重启浏览器。`Extensions.getStorageItems` 在浏览器级连接可能报 `No associated browser context`，导入配置优先使用选项页，无需反复探测 storage。

## 4. 导入并启用

下面 `EXTENSION_ID` 替换为实际返回值。将参数文件放在本地临时目录，用文件避免 shell 引号丢失；`-p` 多参数要重复传，不能用 `&` 拼成一个参数。

```bash
dsb run go_to_url -p 'url=chrome-extension://EXTENSION_ID/options.html#!/io' --summary
dsb state --text-only
dsb run upload_file --params '@import.json' --summary
```

`import.json`：

```json
{"selector":"#restore-local-file","path":"C:/Private/OmegaOptions.local.bak"}
```

确认 `Options imported.` 提示并回读 GAE-Proxy 的 host、port、scheme；文件 input 被框架清空并不代表失败。已有配置的浏览器导入会替换选项，先按用户授权范围保存本地备份。按页面状态决定是否点击 Apply changes，不盲目重复导入。

打开 `chrome-extension://EXTENSION_ID/popup-iframe.html`（可能跳转到 `popup/index.html`），使用当前快照的精确文本选择 `GAE-Proxy自动切换`。选择后弹窗标签页可能关闭；先读取现存页签，再重新打开弹窗只读验证。新版活动项为 `li.om-active`，旧版可为 `li.active`；不要把链接自身没有 class 当作未激活。

保存为 `verify-mode.js`，使用 `dsb js '@verify-mode.js' --select data.result`：

```javascript
() => ({active: Array.from(document.querySelectorAll('li.om-active, li.active')).some(element => element.innerText.trim() === 'GAE-Proxy自动切换')})
```

确认启动配置和当前激活配置都为目标模式。只写入 `-startupProfileName` 不代表已切换当前模式。

## 5. 验收与输出

- 回读扩展 enabled、GAE-Proxy 地址端口、当前模式、启动模式。联网验证使用用户指定的测试站点，输出标题和成功/失败；页面打开仅证明可达性，不证明实际代理/直连路由。需要路由证明时再查询扩展匹配结果或代理日志的脱敏摘要。
- 未重启实测时只报告已写入 profile，不保证重启持久性；不能为了验证关闭其他任务浏览器。交付时说明未打包目录需保留。
- 例行动作用 `--summary`；读取单字段用 `--select`，首次定位才读 `state --text-only`。大型结果必要时 `--out` 留本地，再解析少量字段。不要用重复操作补输出。
- 返回 3–5 行结果即可，不显示完整 profile 路径、内部地址、私人规则、认证字段、下载 URL 查询参数或大段响应。此次执行中的输出与错误复盘见 [执行复盘](references/execution-review.md)。
