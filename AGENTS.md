# Browser task instructions

## Read and scope

- Read all applicable skills in full before acting.
- For browser work, read [deepseek-browser-use](.agents/skills/deepseek-browser-use/SKILL.md).
- For numeric data, also read [web-data-as-text](.agents/skills/web-data-as-text/SKILL.md).
- For railway queries, also read [railway-12306-ticket](.agents/skills/railway-12306-ticket/SKILL.md).
- Follow [reliable execution and the concise prompt template](.agents/skills/deepseek-browser-use/references/reliable-execution.md).
- Read each file before editing. Inspect the current diff. Preserve unrelated changes.
- Skill sources are in .agents/skills. Inspect scripts/sync-skills.mjs before synchronization. Compare source files with installed files.
- Do not force-sync unrelated skills.

## Execute and coordinate

- Use dsb CLI, with numeric --id and a distinct --session per worker. Check subcommand --help instead of guessing flags. PowerShell file arguments use quotes: dsb js '@query.js'. Save structured output with --out.
- Assign one owner to each browser task, including read-only state calls. Parent agents request progress through messages, not through the child's page. Transfer ownership explicitly.
- Distinguish browser task id, dsb jobId, host jobId, and subagentId. A continuable subagentId is not a host job_output argument.
- Do not busy-poll or sleep-loop on background work. Use completion notifications, bounded tool waits, or work on independent steps. Collect relevant jobs before finishing and cancel work that no longer matters.
- Never infer business success from ok, changed, effective, or a DOM diff alone. Read the business postcondition. Unknown action state requires observation, not repeated submission.
- On spawn pwsh ENOENT, inspect the cwd and known executable paths with available file tools. Do not blindly retry or change system configuration. State which tests could not run.

## Report data faithfully

- Fix an absolute target date and business timezone. Verify the actual response date, not only the URL. Preserve overnight arrival dates.
- Track source and observation time per field. Distinguish internal DOM/API checks from independent-source validation. Matching counts do not validate every price or seat status.
- Keep fare decimals, currency, cabin/seat class, tax status and sale status. Unknown tax stays unknown. Waitlist is not availability. Do not guess sleeper names from internal seat codes.
- Count train numbers separately from station-pair travel options. Include intermediate stations and unknown fields in consistency checks.
- Return 5–8 representative verified options first. Add all results only when requested or required. Label incomplete coverage. Do not claim whole-day minima from a sample. Use concise user-language progress, not internal reasoning.
- Query-only requests do not authorize login, ordering or payment. If browser testing is needed, use a visible browser. Store documentation evidence in assets. Label its scope.

## Java 开发与框架文档约定

- 写 Java 前先检索配置的 tio-boot 文档，再按需读源码；不要凭记忆重复实现 Content-Type 参数绑定等已有能力。
- 一个接口一个 Handler，Handler、Service、Model 分包；确定字段使用实体，不确定字段使用 Kv。响应统一使用 RespBodyVo。
- 路由使用 HttpRequestRouter.get/post；token 校验放在 interceptor。参数校验封装为工具类，使用 ParameterValidator；全局捕获 ParameterValidationException。
- Handler 内通过 Aop.get 获取 Service；Service 间可使用 Aop。Handler 与 interceptor 不通过 Aop 实例化。
- 配置通过 EnvUtils.get 系列读取，环境判断使用 appEnv/isDev/isTest/isProd；雪花ID使用 SnowflakeIdUtils.id，先 import，不在调用处使用全限定类名。
- 所有 if 必须有花括号，非必要不用 var；大段SQL和文本使用 Java 21 文本块。底层代码新增注释使用英文。
- 先检查 build.txt，使用 build 构建，Maven 跳过 GPG 验证；测试与安装必须记录真实结果。
- 文档示例包含完整 import，不包含内部检出目录名、底层库版本或源码完整绝对路径。应用文档放应用项目；底层文档只记录通用能力与可运行案例。
