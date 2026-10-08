package nexus.io.chromium.cdp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 一条到 Chrome DevTools Protocol 的 WebSocket 连接
 *
 * <p>
 * <b>只用 JDK 自带的 {@code java.net.http.WebSocket} + 项目已有的 fastjson2</b>,不引第三方 CDP 库:
 * 这类库要么停止维护(实测 cdt-java-client 最后发版 2021-05、cdp4j 最后发版 2019-12),要么是按
 * Chrome 里程碑发版的绑定(例如 Selenium 的 {@code selenium-devtools-v133},每升一次浏览器就要换一次
 * 依赖版本)。而这个项目需要的是「一个能连上任意版本 Chrome 的薄客户端」,自己写反而更小更稳。
 *
 * <p>
 * 职责有三件:
 * <ol>
 * <li><b>命令–回执配对</b>:每条命令分一个自增 id,回执按 id 唤醒等待方。见 {@link #send};</li>
 * <li><b>会话路由</b>:Chrome 的扁平模式({@code flatten:true})下,属于某个页签/子 frame 的消息会带一个
 * {@code sessionId},事件要按 sessionId 派发给对应的监听器,不能串台。见 {@link #on} / {@link CdpSession};</li>
 * <li><b>失败分型</b>:协议回 error、传输断了、发出去了没等到回执,是三种不同的失败,分别包装成
 * {@link CdpException} 的可判据形态。</li>
 * </ol>
 *
 * <p>
 * <b>线程安全</b>:{@code send} 可以从任意线程调用。事件回调在 WebSocket 读线程上执行 —— 回调里
 * 不要做阻塞的 CDP 同步调用(见 {@link CdpEventListener})。
 *
 * <p>
 * 本类**不依赖项目里的任何其它包**,可以整包搬出去单独用。
 */
public final class CdpConnection implements AutoCloseable {

  /** 默认的命令回执超时(毫秒):CDP 的普通命令都是毫秒级,2 秒还没回执基本就是出事了 */
  public static final long DEFAULT_TIMEOUT_MS = 20_000;

  /** 连接(握手)超时(毫秒) */
  public static final long DEFAULT_CONNECT_TIMEOUT_MS = 15_000;

  /** 调试输出的落点;默认丢弃。给排查用,生产不要挂 */
  private static volatile Consumer<String> debugSink = message -> {
  };

  private final WebSocket webSocket;
  private final AtomicLong nextId = new AtomicLong();
  private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
  /** 浏览器级(sessionId 为空的)监听器:事件名 -> 监听器列表;{@code *} 是所有事件 */
  private final Map<String, List<CdpEventListener>> browserListeners = new ConcurrentHashMap<>();
  /** 会话级监听器:sessionId -> (事件名 -> 监听器列表) */
  private final Map<String, Map<String, List<CdpEventListener>>> sessionListeners = new ConcurrentHashMap<>();
  /** 同一个 WebSocket 上到达的文本帧可能分片,{@code last=false} 时先拼在这里 */
  private final StringBuilder partial = new StringBuilder();

  private final long timeoutMs;
  private volatile boolean closed;
  private volatile String closedReason;

  private CdpConnection(WebSocket webSocket, long timeoutMs) {
    this.webSocket = webSocket;
    this.timeoutMs = timeoutMs;
  }

  /**
   * 连到一个已经在跑的 Chrome
   *
   * @param httpEndpoint 形如 {@code http://127.0.0.1:19222};内部会读它的 {@code /json/version}
   *                     拿到真正的 WebSocket 地址
   */
  public static CdpConnection connect(String httpEndpoint) {
    return connect(httpEndpoint, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
  }

  public static CdpConnection connect(String httpEndpoint, long connectTimeoutMs, long timeoutMs) {
    String wsUrl = browserWebSocketUrl(httpEndpoint, connectTimeoutMs);
    return connectToWebSocket(wsUrl, connectTimeoutMs, timeoutMs);
  }

  /** 直接给 WebSocket 地址({@code ws://.../devtools/browser/<id>}) */
  public static CdpConnection connectToWebSocket(String wsUrl, long connectTimeoutMs, long timeoutMs) {
    WebSocket.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build().newWebSocketBuilder();
    CompletableFuture<CdpConnection> opened = new CompletableFuture<>();
    CdpConnection[] holder = new CdpConnection[1];

    builder.buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
      @Override
      public void onOpen(WebSocket webSocket) {
        holder[0] = new CdpConnection(webSocket, timeoutMs);
        opened.complete(holder[0]);
        webSocket.request(1);
      }

      @Override
      public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        // 这里拿不到外围实例(匿名类建在静态方法里),所以状态一律通过 holder 上的实例访问
        CdpConnection conn = holder[0];
        if (conn != null) {
          conn.partial.append(data);
          if (last) {
            String text = conn.partial.toString();
            conn.partial.setLength(0);
            try {
              conn.handle(text);
            } catch (RuntimeException e) {
              debugSink.accept("处理 CDP 消息失败(已忽略):" + e + " 原文:" + brief(text));
            }
          }
        }
        webSocket.request(1);
        return null;
      }

      @Override
      public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        CdpConnection conn = holder[0];
        if (conn != null) {
          conn.closed = true;
          conn.closedReason = "浏览器关掉了 WebSocket(status=" + statusCode + ", reason=" + reason + ")";
          conn.failAllPending(conn.closedReason);
        }
        opened.completeExceptionally(new CdpException("WebSocket 被关闭:status=" + statusCode + " reason=" + reason));
        return null;
      }

      @Override
      public void onError(WebSocket webSocket, Throwable error) {
        CdpConnection conn = holder[0];
        if (conn != null) {
          conn.closed = true;
          conn.closedReason = "WebSocket 出错:" + error;
          conn.failAllPending(conn.closedReason);
        }
        opened.completeExceptionally(new CdpException("WebSocket 出错:" + error, error));
      }
    });

    try {
      return opened.get(connectTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new CdpException("连接 CDP 超时(" + connectTimeoutMs + "ms):" + wsUrl, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CdpException("连接 CDP 时被中断:" + wsUrl, e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw cause instanceof CdpException ? (CdpException) cause
          : new CdpException("连接 CDP 失败:" + cause, cause);
    }
  }

  /** 读 {@code /json/version},把 HTTP 端点换成 WebSocket 地址 */
  public static String browserWebSocketUrl(String httpEndpoint, long timeoutMs) {
    String base = httpEndpoint.endsWith("/") ? httpEndpoint.substring(0, httpEndpoint.length() - 1) : httpEndpoint;
    String url = base + "/json/version";
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs)).GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new CdpException("读 " + url + " 失败:HTTP " + response.statusCode());
      }
      JSONObject version = JSON.parseObject(response.body());
      String ws = version == null ? null : version.getString("webSocketDebuggerUrl");
      if (ws == null || ws.isBlank()) {
        throw new CdpException("读 " + url + " 成功,但里面没有 webSocketDebuggerUrl:" + brief(response.body()));
      }
      return ws;
    } catch (java.io.IOException e) {
      throw new CdpException("读 " + url + " 失败:" + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CdpException("读 " + url + " 时被中断", e);
    }
  }

  // ==================== 发命令 ====================

  /** 发一条浏览器级命令(不带 sessionId)并等回执 */
  public JSONObject send(String method) {
    return send(method, new JSONObject(), null, timeoutMs);
  }

  public JSONObject send(String method, JSONObject params) {
    return send(method, params, null, timeoutMs);
  }

  public JSONObject send(String method, JSONObject params, long timeoutMillis) {
    return send(method, params, null, timeoutMillis);
  }

  /**
   * 发一条命令并等回执
   *
   * @param sessionId 目标会话;null 表示浏览器级
   * @throws CdpException 协议回 error、传输断了、或超时
   */
  public JSONObject send(String method, JSONObject params, String sessionId, long timeoutMillis) {
    if (closed) {
      throw new CdpException("CDP 连接已经关闭" + (closedReason == null ? "" : "(" + closedReason + ")"));
    }
    long id = nextId.incrementAndGet();
    JSONObject message = new JSONObject();
    message.put("id", id);
    message.put("method", method);
    message.put("params", params == null ? new JSONObject() : params);
    if (sessionId != null) {
      message.put("sessionId", sessionId);
    }

    CompletableFuture<JSONObject> future = new CompletableFuture<>();
    pending.put(id, future);
    String text = JSON.toJSONString(message);
    debugSink.accept("--> " + brief(text));
    try {
      webSocket.sendText(text, true).get(Math.max(1000, timeoutMillis), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      pending.remove(id);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new CdpException("发送 CDP 命令失败:" + method + "(" + e.getMessage() + ")", e);
    }

    JSONObject response;
    try {
      response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.remove(id);
      throw new CdpException("等 CDP 命令回执超时(" + timeoutMillis + "ms):" + method
          + " —— 注意这不表示命令没生效", method, null, e);
    } catch (InterruptedException e) {
      pending.remove(id);
      Thread.currentThread().interrupt();
      throw new CdpException("等 CDP 命令回执时被中断:" + method, method, null, e);
    } catch (ExecutionException e) {
      pending.remove(id);
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw cause instanceof CdpException ? (CdpException) cause
          : new CdpException("等 CDP 命令回执失败:" + method + "(" + cause + ")", method, null, cause);
    }

    JSONObject error = response.getJSONObject("error");
    if (error != null) {
      Object rawCode = error.get("code");
      Integer code = rawCode instanceof Number ? ((Number) rawCode).intValue() : null;
      throw new CdpException("CDP 命令被拒绝:" + method + " -> " + error.getString("message")
          + (error.getString("data") == null ? "" : "(" + error.getString("data") + ")"), method, code, null);
    }
    return response.getJSONObject("result") == null ? new JSONObject() : response.getJSONObject("result");
  }

  // ==================== 事件 ====================

  /** 监听浏览器级事件。{@code event} 传 {@code *} 表示所有事件 */
  public void on(String event, CdpEventListener listener) {
    browserListeners.computeIfAbsent(event, key -> new CopyOnWriteArrayList<>()).add(listener);
  }

  public void off(String event, CdpEventListener listener) {
    List<CdpEventListener> listeners = browserListeners.get(event);
    if (listeners != null) {
      listeners.remove(listener);
    }
  }

  /** 监听某个会话的事件 */
  void onSession(String sessionId, String event, CdpEventListener listener) {
    sessionListeners.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
        .computeIfAbsent(event, key -> new CopyOnWriteArrayList<>()).add(listener);
  }

  void offSession(String sessionId, String event, CdpEventListener listener) {
    Map<String, List<CdpEventListener>> byEvent = sessionListeners.get(sessionId);
    if (byEvent == null) {
      return;
    }
    List<CdpEventListener> listeners = byEvent.get(event);
    if (listeners != null) {
      listeners.remove(listener);
      if (listeners.isEmpty()) {
        byEvent.remove(event);
      }
    }
    if (byEvent.isEmpty()) {
      sessionListeners.remove(sessionId);
    }
  }

  /** 会话结束时把它的监听器一并清掉,免得 sessionId 复用时串台 */
  void forgetSession(String sessionId) {
    sessionListeners.remove(sessionId);
  }

  private void handle(String text) {
    JSONObject message = JSON.parseObject(text);
    if (message == null) {
      return;
    }
    if (message.containsKey("id")) {
      Object rawId = message.get("id");
      Long id = rawId instanceof Number ? ((Number) rawId).longValue() : null;
      CompletableFuture<JSONObject> future = id == null ? null : pending.remove(id);
      if (future != null) {
        future.complete(message);
      }
      return;
    }
    String method = message.getString("method");
    if (method == null) {
      return;
    }
    debugSink.accept("<-- " + method + " " + brief(JSON.toJSONString(message.get("params"))));
    JSONObject params = message.getJSONObject("params");
    if (params == null) {
      params = new JSONObject();
    }
    String sessionId = message.getString("sessionId");
    if (sessionId != null) {
      dispatch(sessionListeners.get(sessionId), method, params);
    } else {
      dispatch(browserListeners, method, params);
    }
  }

  private static void dispatch(Map<String, List<CdpEventListener>> listeners, String method, JSONObject params) {
    if (listeners == null || listeners.isEmpty()) {
      return;
    }
    fire(listeners.get(method), params);
    fire(listeners.get("*"), params);
  }

  private static void fire(List<CdpEventListener> list, JSONObject params) {
    if (list == null) {
      return;
    }
    for (CdpEventListener listener : list) {
      try {
        listener.onEvent(params);
      } catch (RuntimeException e) {
        // 一个监听器炸了不能影响别的监听器,也不能让读线程死掉
        debugSink.accept("CDP 事件监听器抛异常(已忽略):" + e);
      }
    }
  }

  private void failAllPending(String reason) {
    for (Map.Entry<Long, CompletableFuture<JSONObject>> entry : pending.entrySet()) {
      CompletableFuture<JSONObject> future = pending.remove(entry.getKey());
      if (future != null) {
        future.completeExceptionally(new CdpException(reason));
      }
    }
  }

  public boolean isClosed() {
    return closed;
  }

  public String closedReason() {
    return closedReason;
  }

  public long timeoutMs() {
    return timeoutMs;
  }

  /** 未配对的在途命令数,用来发现「发了没人回」的泄漏 */
  public int pendingCount() {
    return pending.size();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    closedReason = "调用方主动关闭";
    try {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(3, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // 关不掉就算了:进程退出时连接自然断
    }
    try {
      webSocket.abort();
    } catch (RuntimeException ignored) {
      // 同上
    }
    failAllPending("CDP 连接已关闭");
  }

  private static String brief(String text) {
    if (text == null) {
      return "null";
    }
    String single = text.replaceAll("\\s+", " ");
    return single.length() <= 300 ? single : single.substring(0, 300) + "...";
  }
}
