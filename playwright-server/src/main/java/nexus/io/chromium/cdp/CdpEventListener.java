package nexus.io.chromium.cdp;

import com.alibaba.fastjson2.JSONObject;

/**
 * 一个 CDP 事件的监听器
 *
 * <p>
 * 回调在**读 WebSocket 的那条线程**上执行,所以实现里不要做同步阻塞调用(尤其不要再发一条
 * 同步 CDP 命令并等结果)——那会把后续所有事件都堵在后面。要做费时的事请丢给线程池。
 */
@FunctionalInterface
public interface CdpEventListener {

  /**
   * @param params 事件的 {@code params};事件没有参数时是一个空对象(不是 null)
   */
  void onEvent(JSONObject params);
}
