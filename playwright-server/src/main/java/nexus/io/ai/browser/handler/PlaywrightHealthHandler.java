package nexus.io.ai.browser.handler;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.ai.browser.service.ActionService;
import nexus.io.ai.browser.service.PlaywrightService;
import nexus.io.jfinal.aop.Aop;
import nexus.io.model.body.RespBodyVo;
import nexus.io.tio.boot.http.TioRequestContext;
import nexus.io.tio.http.common.HttpRequest;
import nexus.io.tio.http.common.HttpResponse;
import nexus.io.tio.http.common.utils.HttpIpUtils;

@Slf4j
public class PlaywrightHealthHandler {

  /** 进程启动时刻:用来区分「还是原来那个实例」与「这个端口上换了一个新实例」。 */
  private static final String STARTED_AT =
      java.time.Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()).toString();

  public HttpResponse ping(HttpRequest request) {
    log.info("ping from :{}", HttpIpUtils.getRealIp(request));
    HttpResponse response = TioRequestContext.getResponse();
    // 带上**身份**,而不只是「我活着」。
    //
    // 客户端要靠这几个字段确认「这次应答的是不是我启动的那个实例」:任何进程占着这个端口
    // 都会回 ok,光看 ok 分不出「我原来那个」和「端口上换了一个刚起的空实例」—— 多后端
    // (不同端口 = 不同 profile / 登录态)时这正是最要紧的问题。字段取不到就是零值,
    // 老版本客户端不看这些字段,所以是向后兼容的追加。
    Kv kv = Kv.by("name", "playwright-server")
        .set("pid", ProcessHandle.current().pid())
        .set("startedAt", STARTED_AT)
        .set("port", serverPort());
    response.body(RespBodyVo.ok(kv));
    return response;
  }

  /** 实际监听的端口:客户端启动时用 -Dserver.port 指定;取不到就是 0。 */
  private static int serverPort() {
    String configured = System.getProperty("server.port");
    if (configured == null || configured.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(configured.trim());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  /**
   * 当前活着的任务与共享浏览器
   *
   * <p>
   * 与 {@code POST /playwright/command} 里的 {@code list_tasks} 同一份数据,单独开一个 GET 是为了
   * 让运维侧(脚本、监控、浏览器直接打开)不用构造 POST 请求体就能看到「现在有哪些任务、浏览器还活着没」。
   */
  public HttpResponse tasks(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    response.body(Aop.get(PlaywrightService.class).listTasks());
    return response;
  }

  /** 命令清单:直接 GET 就能看到服务端支持哪些方法,不必先猜一个方法名再被拒 */
  public HttpResponse methods(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    java.util.List<String> names = new java.util.ArrayList<>(nexus.io.ai.browser.actions.registry.CommandTable.names());
    response.body(RespBodyVo.ok(Kv.by("count", names.size()).set("methods", names)));
    return response;
  }

  /** 服务端生效配置(引擎、profile 目录、降级开关、日志目录等) */
  public HttpResponse config(HttpRequest request) {
    HttpResponse response = TioRequestContext.getResponse();
    response.body(Aop.get(PlaywrightService.class).getConfig(null));
    return response;
  }

  /** 仅供测试与排障:取 ActionService(避免 handler 里散落 Aop 调用) */
  static ActionService service() {
    return Aop.get(ActionService.class);
  }
}