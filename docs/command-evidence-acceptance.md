# 执行预校验与脱敏证据验收

2026-11-04，Windows，独立服务端口 10069，可见 Chrome 和隔离 profile；使用 dsb CLI 验证。既有 10049 服务未重启。页面是本地构造的测试夹具，不涉及真实账号或数据库。

## 结果

- 整批预校验：第一步增加页面计数器，第二步给 wait_for_function 错传 body。返回 INVALID_PARAMS、steps[1].wait_for_function.expression、actionExecuted:false，CLI 退出 2；随后只读计数器仍为 0。[响应](assets/command-evidence-20261104/invalid-result.json)
- 脱敏：输入固定的假秘密值，后续 JS 回读并断言。同步与异步执行成功；stdout 均可解析为单个 JSON。最终敏感记录的请求、响应与 steps.log 均不含假秘密值；同步留档保留两个子步骤，断言保留 passed，报告展开 children。job 查询留档仅保留协议证据，命令名无法从原请求匹配时留空。旧记录不追溯修改。
- 输出：batch/job 支持 out、grep；异步等待进度走 stderr，stdout 为最终响应信封。Go 回归覆盖失败退出码 2、导出失败、异步查询成功但任务失败的区别。
- 遮挡：全屏 div 没有 role=dialog。get_modals 返回 count:0，但 covers 中含 diagnosticOnly 候选；点击返回 ELEMENT_OBSCURED，并提示 get_modals 与 get_dialog 的区别。[遮罩](assets/command-evidence-20261104/modals.json) · [点击响应](assets/command-evidence-20261104/blocked.json)
- 外部连接：attach 到测试页面后导航至 about:blank#recovered。attachment.attachedUrl 仍为 about:blank；page.currentUrl 更新，带独立 observedAt；environment 仍为 unknown。[回读](assets/command-evidence-20261104/recovered.json)。close 后 targets 仍能找到原页面，确认只 detach。

[视口截图](assets/command-evidence-20261104/viewport.png) 仅说明测试夹具外观；图中 FIXTURE_SECRET_9182 是固定测试文本。截图与显式导出仍可能包含原值，不属于自动留档的脱敏范围。

## 自动检查与维护

- Java 定向回归 50 项通过，覆盖批次零执行、异步预拒绝、参数组合、等待单位、既有超时/恢复/命令集合/响应格式以及技能文档一致性。
- Go 全量 go test ./... 通过；按 build.txt 使用 build 完成 Java 构建和 Go 测试、安装。
- 参数定义位于 CommandTable 的注册项与 CommandSchema；list_methods 的 details 输出和 parameters.md 共用它。更新定义后运行 Maven：`-Dtest=CommandSchemaDocTest -DupdateSchemaDocs=true`，普通测试会检查生成文档没有漂移。
- skill 主入口移除重复的方法目录，client 分册删除重复编码解释，batch 分册纠正失败继续执行的建议。保留未知动作不重放、last 只读取、expect/readback 和外部连接只 detach 的边界。

本机内嵌 Chromium 启动曾返回 TargetClosedError；本次可见浏览器验收改用 dsb 支持的已安装 Chrome。该结果不宣称覆盖内嵌 Chromium 或真实 WebView2 应用；外部 CDP 连接路径使用同一测试 Chrome 验证。
