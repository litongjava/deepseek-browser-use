package nexus.io.chromium.cdp;

import com.alibaba.fastjson2.JSONObject;

/**
 * 一个 CDP 会话:连到**某一个 target**(页签 / OOPIF / worker)上的一条逻辑通道
 *
 * <p>
 * Chrome 的扁平模式({@code Target.attachToTarget} 带 {@code flatten:true})下,不再需要把消息里再套一层
 * {@code Target.sendMessageToTarget};所有命令和事件都直接走同一条 WebSocket,靠 {@code sessionId} 区分。
 * 这个类就是把那个 sessionId 固定下来,免得每个调用点都要自己带。
 *
 * <p>
 * <b>为什么必须按 target 分会话</b>:跨域 iframe(OOPIF)在 Chrome 里是**独立的 renderer 进程**,它是
 * 自己的 target、有自己的执行上下文。在顶层会话里发 {@code Runtime.evaluate},{@code document} 是顶层
 * 文档的 —— 要进 iframe 内部求值,只能用属于那个 frame 的会话(或在该会话里指定 contextId)。
 */
public final class CdpSession implements AutoCloseable {

  private final CdpConnection connection;
  private final String sessionId;
  private final String targetId;
  private volatile boolean detached;

  CdpSession(CdpConnection connection, String sessionId, String targetId) {
    this.connection = connection;
    this.sessionId = sessionId;
    this.targetId = targetId;
  }

  public String sessionId() {
    return sessionId;
  }

  public String targetId() {
    return targetId;
  }

  public CdpConnection connection() {
    return connection;
  }

  public boolean isDetached() {
    return detached;
  }

  public JSONObject send(String method) {
    return send(method, new JSONObject());
  }

  public JSONObject send(String method, JSONObject params) {
    if (detached) {
      throw new CdpException("会话已经断开(target=" + targetId + "),不能再发 " + method);
    }
    return connection.send(method, params, sessionId, connection.timeoutMs());
  }

  public JSONObject send(String method, JSONObject params, long timeoutMillis) {
    if (detached) {
      throw new CdpException("会话已经断开(target=" + targetId + "),不能再发 " + method);
    }
    return connection.send(method, params, sessionId, timeoutMillis);
  }

  public void on(String event, CdpEventListener listener) {
    connection.onSession(sessionId, event, listener);
  }

  public void off(String event, CdpEventListener listener) {
    connection.offSession(sessionId, event, listener);
  }

  /** 从 target 上摘掉这个会话(不动 target 本身);重复调用无害 */
  @Override
  public void close() {
    if (detached) {
      return;
    }
    detached = true;
    try {
      JSONObject params = new JSONObject();
      params.put("sessionId", sessionId);
      connection.send("Target.detachFromTarget", params);
    } catch (RuntimeException ignored) {
      // target 已经没了 / 连接已经断了,都不影响我们这边的状态
    } finally {
      connection.forgetSession(sessionId);
    }
  }
}
