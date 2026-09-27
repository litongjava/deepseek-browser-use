package nexus.io.ai.browser.json;

import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.json.IJsonFactory;
import nexus.io.tio.utils.json.Json;
import nexus.io.tio.utils.json.JsonUtils;

/**
 * 让本服务的 HTTP 响应不再输出 null 值字段
 *
 * <p>
 * 起因很实际:{@code /playwright/command} 每一条回执都带着 {@code "msg":null}、{@code "error":null},
 * 批量回执里更是每一步都带一份 —— 对读回执的模型与脚本来说全是噪声。
 *
 * <p>
 * <b>为什么不做成框架的全局配置</b>:框架的默认工厂是**静态、进程级**的,而同一个框架下会跑多个项目。
 * 把它改掉等于替别的项目做决定(尤其"必须显式传 null"的接口会直接坏掉)。框架已经提供了按调用点的能力
 * ({@code Json.getSkipNullJson()} / {@code JsonUtils.toSkipNullJson()}),缺的只是"我不想在每个序列化点
 * 各写一遍"这件事 —— 那就在**本项目**里解决。
 *
 * <p>
 * <b>怎么做到只影响本项目的响应</b>:把默认工厂包一层,只覆盖 {@link IJsonFactory#getJson()} 让它返回
 * 跳过 null 的实现。于是本项目里所有走 {@code Json.getJson()} 的序列化点(HTTP 响应体、controller 返回值)
 * 都一致地不输出 null,而**解析**路径({@code parse*} 全部委托给原工厂)与别的项目完全不受影响。
 *
 * <p>
 * 由此带来一个副作用要知道:用 {@code Json.getJson().toJson(..)} 拼**请求体**的代码也会跟着跳过 null。
 * 本服务没有依赖"显式 null"的请求,所以可以接受;若将来要排除某个调用点,直接用
 * {@link JsonUtils#toJson(Object)} 之外的原工厂实例显式序列化即可。
 *
 * <p>
 * 关掉它:配置项 {@code browser.json.skipNull=false}。
 */
public final class JsonResponses {

  /** 是否让响应跳过 null 值字段(默认 true) */
  public static final String KEY_SKIP_NULL = "browser.json.skipNull";

  private JsonResponses() {
  }

  /**
   * 按配置决定要不要装上「跳过 null」的默认工厂
   *
   * <p>
   * 在应用启动时调用一次即可。关掉开关时**什么都不做**,默认行为一点不变。
   *
   * @return 这次有没有装上(启动日志里回报它,便于确认配置到底生效没有)
   */
  public static boolean init() {
    boolean skipNull = skipNullEnabled();
    install(skipNull);
    return skipNull;
  }

  /** 配置说了算:只认 {@code true},其余(含未配置)都是「不跳过」 */
  public static boolean skipNullEnabled() {
    return Boolean.parseBoolean(EnvUtils.getStr(KEY_SKIP_NULL, "true"));
  }

  /**
   * 按显式给定的开关装(或不装)「跳过 null」的默认工厂
   *
   * <p>
   * 配置项的读取与"装不装"分开,是为了让这件事可测:配置来源有命令行、JVM 参数、环境变量与配置文件四条,
   * 测试里不该去伪造环境,直接给布尔值最干净。
   *
   * @return 是否处于「跳过 null」状态
   */
  public static boolean install(boolean skipNull) {
    if (!skipNull) {
      return false;
    }
    installSkipNullFactory();
    return true;
  }

  /**
   * 把默认工厂换成「跳过 null」的包装版
   *
   * <p>
   * 包装的对象是**当前**默认工厂(可能是配置项 {@code tio.json.provider} 指定的 fastjson / gson / jackson,
   * 也可能是别的代码已经装上的),所以不会把 provider 的选择弄丢。
   */
  public static void installSkipNullFactory() {
    Json.setDefaultJsonFactory(new SkipNullFactory(Json.getJsonFactory()));
  }

  /**
   * 只改输出、不改解析的包装工厂
   *
   * <p>
   * 关键点:解析一律委托给原工厂。跳过 null 只该影响"写出去的东西",读进来的报文怎么解析不受影响。
   */
  static final class SkipNullFactory implements IJsonFactory {

    private final IJsonFactory delegate;

    SkipNullFactory(IJsonFactory delegate) {
      this.delegate = delegate;
    }

    @Override
    public Json getJson() {
      return delegate.getSkipNullJson();
    }

    @Override
    public Json getSkipNullJson() {
      return delegate.getSkipNullJson();
    }
  }
}
