# 维护与交接

通用执行规则已合并到 [主技能](../SKILL.md)；CLI 输出与文件引用见 [客户端](client.md)，超时、取消和断言见 [命令可靠性](command-reliability.md)。此路径保留供既有引用使用。

## 执行环境故障

遇到 spawn pwsh ENOENT，先核对 cwd 和 shell 路径；命令尚未启动，不能记成业务失败。不盲目重试、修改系统 PATH 或重启共享服务。

## 交接模板

交接浏览器 task ID、session、目标 URL、目标日期/时区、当前所有者、已确认业务事实、证据路径、在途 commandId/jobId 和阻塞项。前任明确停止操作后接手。数字查询的来源和代表性方案要求统一见 [网页取数规范](../../web-data-as-text/SKILL.md)。

## 文档维护与安装

技能源为仓库 .agents/skills；scripts/sync-skills.mjs 按宿主处理 frontmatter。Claude Code 安装为 dsb-skills 插件，使用 dsb-skills:{name} 调用；DSH 与 Codex 保留原有命名。旧清单管理的 Claude 扁平副本迁移到技能扫描目录外的备份位置，避免重复加载。
先读取源与安装文件并检查差异，保留双方已有改动。同步脚本当前按宿主同步全部技能，不支持单技能筛选；只改三份技能时，定向同步已审阅文件，不能运行 --all --force 覆盖其他技能。不要修改安装清单或无关安装文件来掩盖漂移。同步后比较内容哈希，确认新引用文档也存在。

文档检查不需要打开真实票务网站。CLI 示例通过 help 核对；静态检查覆盖链接、frontmatter、禁止的旧写法、日期与金额示例。只有真的运行过的检查才能写“通过”。
