package nexus.io.ai.browser.actions.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 会记录「读过哪些参数」的 JSONObject
 *
 * <p>
 * <b>为什么需要它</b>:命令表里的每个执行体各自读自己认识的键,<b>没有一份声明式的参数清单</b>,
 * 所以一个拼错的参数名只会被<b>静默丢弃</b>——回执照样 {@code ok:true},调用方以为自己的意图生效了。
 *
 * <p>
 * 实测踩过:想让人工待办活得久一点,凭直觉传了 {@code expiresInSeconds:3600},而这个命令只认
 * {@code timeoutSeconds},于是它按默认 300 秒建了待办,人还没看到请求就已经过期。
 * 回执里没有任何线索,只能靠回头翻源码才知道参数名写错了。
 *
 * <p>
 * <b>怎么工作的</b>:这里把执行体读参数时用到的取值方法全部重写一遍,读一个记一个;命令跑完之后
 * 拿 {@code keySet()} 减去「读过的键」,剩下的就是<b>传了但一次也没被用过</b>的参数。
 *
 * <p>
 * 注意不能简单重写 {@code get(Object)}:fastjson2 的 {@code getString}/{@code getInteger}
 * 这些方法是直接调 {@code super.get(key)} 的(绕过了子类对 {@code get} 的重写),所以只能逐个重写
 * 取值方法本身。{@link TrackedArgsTest} 用一个用例把这条前提钉住。
 */
public class TrackedArgs extends JSONObject {

  /**
   * 在任意命令上都会被读到的框架级参数
   *
   * <p>
   * 它们由分发层(重发许可、批次的异步开关)读取,不属于任何单个命令,所以不能被当成「没被用过」。
   */
  public static final Set<String> FRAMEWORK_PARAMS = Set.of("retryOnSpurious", "async");

  private final Set<String> readKeys = new LinkedHashSet<>();

  public TrackedArgs(JSONObject source) {
    if (source != null) {
      putAll(source);
    }
  }

  /** 执行体实际读过的键(按首次读取的先后顺序) */
  public Set<String> readKeys() {
    return Collections.unmodifiableSet(readKeys);
  }

  /** 传了但一次也没被读过的键 = 被静默忽略的参数 */
  public Set<String> unreadKeys() {
    Set<String> unread = new LinkedHashSet<>(keySet());
    unread.removeAll(readKeys);
    unread.removeAll(FRAMEWORK_PARAMS);
    return unread;
  }

  private void mark(String key) {
    if (key != null) {
      readKeys.add(key);
    }
  }

  @Override
  public String getString(String key) {
    mark(key);
    return super.getString(key);
  }

  @Override
  public Integer getInteger(String key) {
    mark(key);
    return super.getInteger(key);
  }

  @Override
  public Long getLong(String key) {
    mark(key);
    return super.getLong(key);
  }

  @Override
  public Double getDouble(String key) {
    mark(key);
    return super.getDouble(key);
  }

  @Override
  public Boolean getBoolean(String key) {
    mark(key);
    return super.getBoolean(key);
  }

  @Override
  public JSONArray getJSONArray(String key) {
    mark(key);
    return super.getJSONArray(key);
  }

  @Override
  public JSONObject getJSONObject(String key) {
    mark(key);
    return super.getJSONObject(key);
  }

  /**
   * 给「没被用过的参数」写一句能照着改的说明
   *
   * <p>
   * 只说「这个参数不认识」还不够:调用方真正需要的是<b>这个命令到底认哪些参数</b>。所以这里在报出
   * 陌生参数之后,紧接着把它实际用过的参数名列出来——两个清单一对照,不用再去翻文档。
   *
   * @param method   命令名,写进说明里方便定位
   * @param unread   没被读过的参数名
   * @param accepted 被读过的参数名(也就是这个命令真正认的参数)
   */
  public static String unknownParamNote(String method, Set<String> unread, Set<String> accepted) {
    StringBuilder sb = new StringBuilder();
    sb.append(method).append(" 忽略了这些参数:").append(String.join(", ", unread));
    sb.append("。它们没有被这个命令使用,多半是名字写错或该命令不支持——");
    if (accepted.isEmpty()) {
      sb.append("这个命令本次没有读取任何参数(它的参数可能全是可选的,也可能你根本没传对任何一个)。");
    } else {
      sb.append("本次实际用到的参数是:").append(String.join(", ", accepted)).append("。");
    }
    sb.append("完整参数表见技能文档 references/commands.md。");
    return sb.toString();
  }

  /** 便于单测与日志:把两类参数整理成可直接放进回执的形态 */
  public static JSONObject describe(List<String> unread, List<String> accepted) {
    return new JSONObject().fluentPut("unread", unread).fluentPut("accepted", accepted);
  }
}
