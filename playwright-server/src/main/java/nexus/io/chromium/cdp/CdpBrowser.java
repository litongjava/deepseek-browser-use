package nexus.io.chromium.cdp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 浏览器级 CDP 门面:连上一份已经在跑的 Chrome,开页签、连会话、设全局行为
 *
 * <p>
 * 用法(端点就是 {@code ChromeLauncher} 从 Chrome 的 stderr 里读出来的那个
 * {@code http://127.0.0.1:<port>}):
 *
 * <pre>{@code
 * try (CdpBrowser browser = CdpBrowser.connect("http://127.0.0.1:19222")) {
 *   System.out.println(browser.version().getString("Browser"));   // Chrome/154.0.8037.57
 *   try (CdpPage page = browser.newPage()) {
 *     page.navigate("https://example.com");
 *     System.out.println(page.title());
 *   }
 * }
 * }</pre>
 *
 * <p>
 * 这里只放「浏览器级」的东西({@code Target.*}、{@code Browser.*});页面内的能力在 {@link CdpPage},
 * 会话级的在 {@link CdpSession}。
 */
public final class CdpBrowser implements AutoCloseable {

  private final CdpConnection connection;
  private final boolean ownsConnection;
  private JSONObject versionCache;

  private CdpBrowser(CdpConnection connection, boolean ownsConnection) {
    this.connection = connection;
    this.ownsConnection = ownsConnection;
  }

  /**
   * 连到一个已经在跑的 Chrome
   *
   * @param httpEndpoint 形如 {@code http://127.0.0.1:19222},内部读 {@code /json/version} 换成 WebSocket
   */
  public static CdpBrowser connect(String httpEndpoint) {
    return new CdpBrowser(CdpConnection.connect(httpEndpoint), true);
  }

  public static CdpBrowser connect(String httpEndpoint, long connectTimeoutMs, long timeoutMs) {
    return new CdpBrowser(CdpConnection.connect(httpEndpoint, connectTimeoutMs, timeoutMs), true);
  }

  /** 复用一条已有连接(同一个浏览器要被多个组件共用时用这个,别各连各的) */
  public static CdpBrowser over(CdpConnection connection) {
    return new CdpBrowser(connection, false);
  }

  public CdpConnection connection() {
    return connection;
  }

  // ==================== 浏览器信息 ====================

  /** {@code Browser.getVersion}:{@code protocolVersion} / {@code product} / {@code userAgent} / {@code jsVersion} */
  public JSONObject version() {
    JSONObject cached = versionCache;
    if (cached != null) {
      return cached;
    }
    JSONObject version = connection.send("Browser.getVersion");
    versionCache = version;
    return version;
  }

  /** 形如 {@code Chrome/154.0.8037.57},用来确认「这次真的连上了哪个浏览器」 */
  public String product() {
    return version().getString("product");
  }

  // ==================== target / 页签 ====================

  /** 当前所有 target(含 page / iframe / worker / service_worker);要只看页签用 {@link #pages()} */
  public List<CdpTarget> targets() {
    JSONObject result = connection.send("Target.getTargets");
    JSONArray infos = result.getJSONArray("targetInfos");
    List<CdpTarget> out = new ArrayList<>();
    if (infos != null) {
      for (int i = 0; i < infos.size(); i++) {
        JSONObject info = infos.getJSONObject(i);
        if (info != null) {
          out.add(CdpTarget.from(info));
        }
      }
    }
    return out;
  }

  /** 只把 {@code type=page} 的 target 当页签 */
  public List<CdpTarget> pages() {
    return targets().stream().filter(CdpTarget::isPage).toList();
  }

  /** 开一个新页签并连上它 */
  public CdpPage newPage() {
    return newPage("about:blank");
  }

  public CdpPage newPage(String url) {
    JSONObject params = new JSONObject();
    params.put("url", url == null ? "about:blank" : url);
    JSONObject created = connection.send("Target.createTarget", params);
    String targetId = created.getString("targetId");
    if (targetId == null) {
      throw new CdpException("Target.createTarget 没有返回 targetId");
    }
    CdpSession session = attach(targetId);
    CdpPage page = new CdpPage(session);
    page.init();
    return page;
  }

  /** 连到一个已有 target,拿到它的会话 */
  public CdpSession attach(String targetId) {
    JSONObject params = new JSONObject();
    params.put("targetId", targetId);
    // flatten=true:后续命令与事件直接走同一条 WebSocket,靠 sessionId 区分,不再层层套 sendMessageToTarget
    params.put("flatten", true);
    JSONObject result = connection.send("Target.attachToTarget", params);
    String sessionId = result.getString("sessionId");
    if (sessionId == null) {
      throw new CdpException("Target.attachToTarget 没有返回 sessionId(target=" + targetId + ")");
    }
    return new CdpSession(connection, sessionId, targetId);
  }

  /** 关掉一个页签(target 不存在时也当成功 —— 调用方要的是「它没了」这个结果) */
  public void closeTarget(String targetId) {
    JSONObject params = new JSONObject();
    params.put("targetId", targetId);
    try {
      connection.send("Target.closeTarget", params);
    } catch (CdpException e) {
      if (!isMissingTarget(e)) {
        throw e;
      }
    }
  }

  static boolean isMissingTarget(CdpException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
    return message.contains("no target with given id") || message.contains("target closed");
  }

  /**
   * 打开自动挂载:新建的 target(包括跨域 iframe 的 OOPIF)会自动 attach,并触发
   * {@code Target.attachedToTarget} 事件
   *
   * <p>
   * <b>{@code pauseNewTargets=true} 会把新 target 停在调试器前</b>,这时必须有人把它放行
   * ({@code Runtime.runIfWaitingForDebugger}),否则页面永远白屏。所以这里在打开开关的同时就注册了
   * 「收到 attachedToTarget 立刻放行」的监听器 —— 不把「忘了放行」这种事留给调用方。
   */
  public void setAutoAttach(boolean pauseNewTargets) {
    if (pauseNewTargets) {
      connection.on("Target.attachedToTarget", params -> {
        String sessionId = params.getString("sessionId");
        if (sessionId == null) {
          return;
        }
        try {
          connection.send("Runtime.runIfWaitingForDebugger", new JSONObject(), sessionId, 5_000);
        } catch (RuntimeException ignored) {
          // 放行失败不影响已经挂上的会话;真出问题时页面会一直白,现象很明显
        }
      });
    }
    JSONObject params = new JSONObject();
    params.put("autoAttach", true);
    params.put("waitForDebuggerOnStart", pauseNewTargets);
    params.put("flatten", true);
    connection.send("Target.setAutoAttach", params);
  }

  // ==================== 浏览器级全局行为 ====================

  /**
   * 把下载落到指定目录
   *
   * <p>
   * 这是**自己拉进程 + CDP** 这条路相对 Playwright 持久化上下文最容易丢的一项:那条路有
   * {@code setDownloadsPath},这条路没有,不补的话下载会落到浏览器自己的默认下载目录,
   * 而调用方还在按 {@code ~/Downloads/broswer} 找文件。所以启动时就要补上。
   */
  public void setDownloadBehavior(Path downloadDir) {
    setDownloadBehavior(downloadDir == null ? null : downloadDir.toAbsolutePath().toString(), true);
  }

  public void setDownloadBehavior(String downloadPath, boolean eventsEnabled) {
    JSONObject params = new JSONObject();
    if (downloadPath == null || downloadPath.isBlank()) {
      params.put("behavior", "default");
    } else {
      params.put("behavior", "allow");
      params.put("downloadPath", downloadPath);
    }
    params.put("eventsEnabled", eventsEnabled);
    connection.send("Browser.setDownloadBehavior", params);
  }

  /** 授予权限(不加 origin 表示对所有站点生效) */
  public void grantPermissions(List<String> permissions, String origin) {
    JSONObject params = new JSONObject();
    params.put("permissions", permissions);
    if (origin != null && !origin.isBlank()) {
      params.put("origin", origin);
    }
    connection.send("Browser.grantPermissions", params);
  }

  @Override
  public void close() {
    if (ownsConnection) {
      connection.close();
    }
  }
}
