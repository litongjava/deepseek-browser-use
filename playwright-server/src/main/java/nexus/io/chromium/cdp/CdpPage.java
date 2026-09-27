package nexus.io.chromium.cdp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.alibaba.fastjson2.JSONObject;

/**
 * 页面级 CDP 门面:导航、求值、截图、UA 覆盖、原生弹窗
 *
 * <p>
 * 一个 {@link CdpPage} 对应一个 target(通常是一个页签)上的一条 {@link CdpSession}。所有命令都发在
 * 这个会话里,所以 {@code Runtime.evaluate} 里的 {@code document} 就是**这个页面自己的**文档 —— 这也正是
 * 跨域 iframe 必须按 frame 开会话的原因(顶层会话里 {@code document} 永远是顶层的)。
 *
 * <p>
 * <b>求值约定</b>:{@link #evaluate} 走 {@code returnByValue:true} + {@code awaitPromise:true},所以
 * 可以直接写 {@code (async () => (await fetch(u)).json())()} 这种异步表达式,拿回的是**已经解开的
 * JSON 值**(不是 RemoteObject 句柄)。返回值必须 JSON 可序列化;DOM 节点拿不到,请先转成
 * {@code textContent} / {@code outerHTML} / {@code value}。
 */
public final class CdpPage implements AutoCloseable {

  private final CdpSession session;
  private volatile boolean closed;

  CdpPage(CdpSession session) {
    this.session = session;
  }

  /** 打开必需的两个域:不 enable,{@code Page.navigate} 能跑但拿不到 loadEventFired */
  void init() {
    session.send("Page.enable");
    session.send("Runtime.enable");
  }

  public CdpSession session() {
    return session;
  }

  public String targetId() {
    return session.targetId();
  }

  public String sessionId() {
    return session.sessionId();
  }

  // ==================== 导航 ====================

  public CdpPage navigate(String url) {
    return navigate(url, session.connection().timeoutMs());
  }

  /**
   * 导航到指定地址并等它加载完
   *
   * <p>
   * 「加载完」分两步:先等 {@code Page.loadEventFired}(准确、不轮询),再确认
   * {@code document.readyState === 'complete'}。**load 事件等不到不算失败** —— 下载、204、
   * 长连接页面本来就不会触发它,这时以 readyState 为准,而不是把一个正常页面判死。
   *
   * @throws CdpException 只有导航本身被拒绝(例如地址非法、被策略拦)才抛
   */
  public CdpPage navigate(String url, long timeoutMillis) {
    CompletableFuture<Void> loaded = new CompletableFuture<>();
    CdpEventListener listener = params -> loaded.complete(null);
    session.on("Page.loadEventFired", listener);
    try {
      JSONObject params = new JSONObject();
      params.put("url", url);
      JSONObject result = session.send("Page.navigate", params, timeoutMillis);
      String errorText = result.getString("errorText");
      if (errorText != null && !errorText.isBlank()) {
        throw new CdpException("导航被拒绝:" + url + " -> " + errorText);
      }
      try {
        loaded.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        // 这类页面不会触发 loadEventFired,交给下面的 readyState 判断
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException ignored) {
        // 监听器里不会失败,这里只为语法完整
      }
    } finally {
      session.off("Page.loadEventFired", listener);
    }
    waitForReadyState(timeoutMillis);
    return this;
  }

  /** 等 {@code document.readyState == 'complete'};等不到只返回 false,不抛异常 */
  public boolean waitForReadyState(long timeoutMillis) {
    long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
    while (System.currentTimeMillis() < deadline) {
      try {
        Object state = evaluate("document.readyState");
        if ("complete".equals(state)) {
          return true;
        }
      } catch (CdpException ignored) {
        // 页面正在换文档:执行上下文还没建好,正常,继续轮询
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** 轮询一个表达式直到它为真;超时返回 false(不抛 —— 「没等到」本就是一种结果) */
  public boolean waitForFunction(String expression, long timeoutMillis) {
    long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
    while (System.currentTimeMillis() < deadline) {
      try {
        Object value = evaluate(expression);
        if (value instanceof Boolean bool && bool) {
          return true;
        }
        if (value instanceof Number number && number.doubleValue() != 0) {
          return true;
        }
        if (value instanceof String text && !text.isBlank() && !"false".equals(text)) {
          return true;
        }
      } catch (CdpException ignored) {
        // 换文档期间求值会失败,继续等
      }
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  // ==================== 读取 ====================

  public String url() {
    Object value = evaluate("location.href");
    return value == null ? null : String.valueOf(value);
  }

  public String title() {
    Object value = evaluate("document.title");
    return value == null ? null : String.valueOf(value);
  }

  public String content() {
    Object value = evaluate("document.documentElement ? document.documentElement.outerHTML : ''");
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * 在这个页面里求值
   *
   * @return 已经解开的 JSON 值(String / Number / Boolean / JSONObject / JSONArray / null)
   * @throws CdpException 页面里抛了异常({@code exceptionDetails})
   */
  public Object evaluate(String expression) {
    return evaluate(expression, session.connection().timeoutMs());
  }

  public Object evaluate(String expression, long timeoutMillis) {
    JSONObject params = new JSONObject();
    params.put("expression", expression);
    params.put("returnByValue", true);
    params.put("awaitPromise", true);
    JSONObject result = session.send("Runtime.evaluate", params, timeoutMillis);
    JSONObject exception = result.getJSONObject("exceptionDetails");
    if (exception != null) {
      String text = exception.getString("text");
      JSONObject thrown = exception.getJSONObject("exception");
      String description = thrown == null ? null : thrown.getString("description");
      throw new CdpException("页面里求值抛异常:" + (text == null ? "" : text)
          + (description == null ? "" : " " + description) + " | 表达式:" + brief(expression));
    }
    JSONObject remote = result.getJSONObject("result");
    return remote == null ? null : remote.get("value");
  }

  // ==================== 截图 ====================

  /** 整页截图(PNG 字节) */
  public byte[] screenshot() {
    return screenshot(null, true);
  }

  /**
   * 截图并落盘
   *
   * @param target 落盘路径;null 表示只要字节
   */
  public byte[] screenshot(Path target, boolean fullPage) {
    JSONObject params = new JSONObject();
    params.put("format", "png");
    if (fullPage) {
      params.put("captureBeyondViewport", true);
    }
    JSONObject result = session.send("Page.captureScreenshot", params, 60_000);
    String base64 = result.getString("data");
    if (base64 == null) {
      throw new CdpException("Page.captureScreenshot 没有返回 data");
    }
    byte[] bytes = Base64.getDecoder().decode(base64);
    if (target != null) {
      try {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.write(target, bytes);
      } catch (IOException e) {
        throw new CdpException("截图落盘失败:" + target + "(" + e.getMessage() + ")", e);
      }
    }
    return bytes;
  }

  // ==================== 覆盖 / 弹窗 ====================

  /** 覆盖 UA(内置 Chromium 要伪装成 Chrome 时用;本机 Chrome 不要覆写,否则站点侧看不出真身) */
  public void setUserAgentOverride(String userAgent) {
    JSONObject params = new JSONObject();
    params.put("userAgent", userAgent);
    session.send("Emulation.setUserAgentOverride", params);
  }

  /** 监听原生弹窗({@code alert} / {@code confirm} / {@code prompt} / {@code beforeunload}) */
  public void onDialog(CdpEventListener listener) {
    session.on("Page.javascriptDialogOpening", listener);
  }

  /**
   * 处理原生弹窗
   *
   * <p>
   * <b>原生弹窗会阻塞页面</b>:不处理的话后续所有求值都停在那里。所以要么注册
   * {@link #onDialog} 自动处理,要么在需要时显式调这里。
   */
  public void handleDialog(boolean accept, String promptText) {
    JSONObject params = new JSONObject();
    params.put("accept", accept);
    if (promptText != null) {
      params.put("promptText", promptText);
    }
    session.send("Page.handleJavaScriptDialog", params);
  }

  // ==================== 收尾 ====================

  /** 关掉这个页签(会连 target 一起关) */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      session.close();
    } finally {
      try {
        session.connection().send("Target.closeTarget", targetParams(session.targetId()));
      } catch (RuntimeException ignored) {
        // target 可能已经被页面自己关了 / 浏览器已经退出:对调用方来说结果一样
      }
    }
  }

  private static JSONObject targetParams(String targetId) {
    JSONObject params = new JSONObject();
    params.put("targetId", targetId);
    return params;
  }

  private static String brief(String text) {
    if (text == null) {
      return "null";
    }
    String single = text.replaceAll("\\s+", " ");
    return single.length() <= 200 ? single : single.substring(0, 200) + "...";
  }
}
