# 浏览器测试：业务断言与截图证据

用于执行测试用例、截图留证和排查截图超时。仅适用于本次实际观察到的对象，不用命令成功、旧截图或预期文本代替业务事实。

## 每项测试分别记录三件事

| 维度 | 记录方式 |
|---|---|
| 前置条件 | 登录主体、角色、视口、页面状态和数据对象是否满足；缺少条件记 BLOCKED，不执行依赖它的断言 |
| 业务断言 | 写清具体文本、DOM状态、HTTP或数据库事实，以及 PASS / FAIL / BLOCKED / NOT_RUN |
| 证据 | 文本、网络响应、截图分别登记完整 / 部分 / 缺失；注明截图是全页、视口还是元素 |

推荐登记模板：

| 用例/分支 | 前置条件及对象 | 实际操作与断言 | 业务状态 | 文本/接口证据 | 图片范围与状态 | 限制及后续动作 |
|---|---|---|---|---|---|---|
| 示例 | 未登录后台，390×844 | 页面有登录入口、无库存变更按钮 | PASS | 本轮页面文本 | 截图缺失 | 不能据此判定具名管理员窄屏授权阅读通过 |

截图失败不会自动推翻已经证实的文本断言，但依赖视觉布局、遮挡、二维码可读性的断言必须记为未验证或 BLOCKED。如果测试前置条件不成立，修正测试及结果登记，保留原始记录，不把测试脚本问题报成产品缺陷。

## 截图超时与恢复

自动截图与 `screenshot` / `get_element_screenshot` 共用每个任务的熔断状态。默认截图预算来自 ``browser.capture.timeoutMs``（8000毫秒），显式 ``timeoutMs`` 可取1—120000。通过命令分发的调用还受30秒共享重试窗口限制；未承诺中断任意JavaScript或其他既有阻塞操作。

- 看到 ``capture_degraded:true`` 时检查 ``retryAfterMs``；普通手动截图会快速失败，不再另等30秒。继续完成不依赖图片的断言，不在循环中反复请求截图。
- 需要主动检查截图是否恢复时，明确传 ``force:true``。它只尝试一次截图，不执行内部重试或视口回退；成功清除熔断，失败继续冷却。不要把强制探测变成高频重试。
- 需要全页图但接受部分证据时，可传 ``fullPage:true,fallbackToViewport:true``。全页尝试只使用部分预算，失败后在剩余预算内尝试视口图。
- 回退成功仍是 ``ok:true``，但必须同时检查 ``fallbackUsed``、``fullPageCaptured`` 与 ``warning``；``fullPageCaptured:false`` 只能登记为视口证据，不能算完整全页截图。
- 超时并不证明页面业务失败，也不证明页面不存在。检查本轮文本/接口事实；需要人确认视觉状态时保留现场再请求协助。

CLI示例（仓库根目录）：

```powershell
dsb --id 1001 state --text-only
dsb --id 1001 state --out state.json
dsb --id 1001 run screenshot -p fullPage=true -p fallbackToViewport=true -p timeoutMs=8000 --out shot.json
dsb --id 1001 run screenshot -p force=true -p timeoutMs=3000 --out probe.json
```

``state --text-only`` 的stdout保持纯页面文本，截图降级、快照不可靠、观测不完整及索引不可用的告警保留在stderr。重定向stdout留证时也保存stderr。``--select``不代表可以忽略这些告警；``--out``保存完整JSON，优先于终端字段筛选。

## 诊断字段

``data.capture`` 包含请求/实际模式（``requestedMode`` / ``actualMode``）、实际截图调用数（``attempts``）、共享重试数（``retries``）、``elapsedMs``、``timeoutMs`` 和 ``stage``。阶段区分预检、熔断拒绝、截图、完成、写文件；浏览器路径还提供可获取的视口尺寸、页面关闭状态及浏览器连接状态。视口尺寸不等于文档总尺寸，不能由它推定全页已捕获。

``data.retryBudget`` 描述命令与嵌套截图的共享重试统计。事件泵伪故障共用最多两次额外重试，不再由各层分别重置次数；``attempts``为初次加共享重试数，``commandAttempts``为外层命令实际调用次数。明确请求的视口回退不属于事件泵重试，其截图调用数以 ``data.capture.attempts`` 为准。动作默认仍不重试；不要因为截图缺失而重复点击或提交。
