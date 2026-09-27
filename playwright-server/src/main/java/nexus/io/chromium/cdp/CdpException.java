package nexus.io.chromium.cdp;

/**
 * CDP 调用失败
 *
 * <p>
 * 三种来源分得清,调用方才能决定「重试 / 换一条路 / 直接失败」:
 * <ul>
 * <li>{@code method} 非空、{@code code} 非空:浏览器回了 {@code error}(协议级拒绝,例如方法不存在、
 * 参数不合法、没有权限);</li>
 * <li>{@code method} 为空:传输层问题(WebSocket 断了、连接超时、响应超时、浏览器进程没了);</li>
 * <li>{@code cause} 是 {@link java.util.concurrent.TimeoutException}:命令发出去了但没等到回执 ——
 * <b>不表示没生效</b>,调用方不能当成失败随便重发(尤其点击 / 提交类)。</li>
 * </ul>
 */
public class CdpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 出错的 CDP 方法名;传输层错误时为 null */
  private final String method;

  /** 浏览器返回的协议错误码;传输层错误时为 null */
  private final Integer code;

  public CdpException(String message) {
    this(message, null, null, null);
  }

  public CdpException(String message, Throwable cause) {
    this(message, null, null, cause);
  }

  public CdpException(String message, String method, Integer code, Throwable cause) {
    super(message, cause);
    this.method = method;
    this.code = code;
  }

  public String method() {
    return method;
  }

  public Integer code() {
    return code;
  }

  /** 是不是「发出去没等到回执」——这类失败**不能**当成没生效 */
  public boolean isTimeout() {
    return getCause() instanceof java.util.concurrent.TimeoutException;
  }

  /** 是不是协议级拒绝(浏览器明确回了 error) */
  public boolean isProtocolError() {
    return code != null;
  }
}
