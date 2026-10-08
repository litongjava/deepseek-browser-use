package nexus.io.chromium.cdp;

import com.alibaba.fastjson2.JSONObject;

/**
 * 一个 target 的概要信息({@code Target.getTargets} 里的一条)
 *
 * <p>
 * 只保留排查与路由用得上的字段:类型、地址、标题、以及它属于哪个浏览器上下文。CDP 原样返回的
 * {@code targetInfo} 里还有 {@code attached}、{@code openerId}、{@code browserContextId} 等,
 * 需要时用 {@link #raw()} 取。
 */
public record CdpTarget(String targetId, String type, String url, String title, String browserContextId,
    boolean attached, JSONObject raw) {

  /** 是不是一个网页页签(我们只把这类当作「页面」) */
  public boolean isPage() {
    return "page".equals(type);
  }

  public static CdpTarget from(JSONObject info) {
    return new CdpTarget(info.getString("targetId"), info.getString("type"), info.getString("url"),
        info.getString("title"), info.getString("browserContextId"), Boolean.TRUE.equals(info.get("attached")),
        info);
  }

  @Override
  public String toString() {
    return type + "[" + targetId + "] " + url;
  }
}
