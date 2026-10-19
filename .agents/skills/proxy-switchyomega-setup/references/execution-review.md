# 安装过程复盘

| 原操作 | 问题 | 固化后的方式 |
| --- | --- | --- |
| `Get-Content -TotalCount 40 OmegaOptions.bak` | 文件是单行 JSON，输出约四万 token；还带出了私人规则和订阅信息 | JSON 解析后仅投影目标配置，不输出规则正文 |
| `start` 整封 JSON | profile 路径、启动说明、上传及调试字段重复 | `start --summary` 仅浏览器类型、headless、模式和任务标识 |
| 全量读取 CDP 协议和反复搜索压缩源码 | 数千行输出仍没回答当前配置是否激活 | 小型 CDP 加载脚本，选项页导入，DOM 活动项回读 |
| 多次整页 state/get_tabs/get_url/get_title | 相同内容重复进入上下文 | 首次定位读 state，后续只读目标字段或单次 JS 摘要 |
| 直接打印 GitHub release JSON | 大量作者及资产元数据无关 | 固定版本与可信摘要；离线包/指定镜像/已有代理下载 |
| PowerShell 内联 JS、JSON 反斜杠和 Bash heredoc | 引号丢失、解析失败、重复调用 | 明确 Git Bash；复杂参数和 JS 走文件，Windows 路径用正斜杠 |
| `-p 'selector=...&path=...'` | path 没有成为独立参数 | `--params '@import.json'` 或重复 `-p` |
| 先删除解压目录再解压 | 不必要的破坏性动作，影响已安装扩展 | 新版本目录，已存在则检查/复用，不递归删除 |
| `Extensions.getStorageItems` 浏览器级连接 | 缺少 browser context | 使用扩展自带导入页 |
| 读取 `a.className` 判断模式 | 活动 class 位于父 `li.om-active` | 读取实际活动项；兼容旧版 `li.active` |
| 同时传任务 ID 与 session | 覆盖宿主绑定、命令冗长 | 已绑定宿主直接 `dsb …`；独立 CLI 区分任务上下文和留档标签 |
| 打开国内/其他站点后声称直连/代理 | 可达性没有证明路由 | 将可达性、规则命中、实际连接路由分开报告 |

`--summary` 是终端展示控制，`--response-mode compact` 是服务端裁剪，二者不同。摘要截断必须明确提示；完整结构仍可用 `--json`/`--select`/`--out` 或已有 `last` 读取。凭据脱敏不能靠截断，尤其不能先打印完整配置再裁剪。
