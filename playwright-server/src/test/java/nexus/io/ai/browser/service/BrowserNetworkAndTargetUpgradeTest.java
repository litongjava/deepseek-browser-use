package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpServer;

import nexus.io.ai.browser.upload.UploadStore;
import nexus.io.model.body.RespBodyVo;

/**
 * 「Chrome 的网络错误页」与「选择器命中多个」的回归测试
 *
 * <p>两件事都来自 2026-10-01 用 {@code deepseek-browser-use} 往 X 发一条推时真踩到的坑:
 *
 * <ul>
 * <li><b>导航失败被报成 {@code ACTION_TIMEOUT},方向全错</b>:本机开着 v2ray 的 TUN,第一次
 * {@code go_to_url} 到 x.com 时回执只有一句 {@code Timeout 30000ms exceeded},而真相是
 * <b>Chrome 停在自己的网络错误页上</b>({@code ERR_NETWORK_CHANGED},TUN 网卡抖动),页面上
 * 一个字的正文都没有。当时只能另跑一次 {@code get_browser_state},从错误页的正文里把
 * {@code ERR_NETWORK_CHANGED} 读出来。现在导航失败会自己探这张错误页,给出
 * {@code errorCode:NETWORK_ERROR} + {@code data.netError} + 可重试与退避建议。</li>
 * <li><b>{@code upload_file} 命中多个 file input 却不说</b>:X 的投稿页上
 * {@code input[data-testid='fileInput']} 匹配到 2 个(弹窗里一个、后面的内联编辑器一个),
 * 而且**两个都是 0×0 隐藏** —— 动作类命令那套「优先挑可见的」在这里无从判断,只能按文档顺序取。
 * 这次恰好蒙对;顺序反过来就会把图片静默塞进另一个编辑器,而回执里连 {@code matched} 都没有。
 * 现在回执报 {@code matched} / {@code chosenIndex} / {@code visibleMatched},并且可以传 {@code nth} 指定。</li>
 * </ul>
 *
 * <p>顺带钉住 {@code is_visible} / {@code is_enabled} / {@code is_checked} 与
 * {@code get_element_text} 同形(index 与 selector 二选一):同一批里前者能按选择器问、
 * 后者回「缺少参数 index」,是同一族命令里的两套规则。
 */
public class BrowserNetworkAndTargetUpgradeTest {

  private static PlaywrightService service;
  private static Long id;
  private static HttpServer server;
  private static String base;
  private static Path profileDir;
  private static Path uploadDir;
  private static Path markFile;

  /** 一个连不上的端口:用来让 Chrome 渲染出他自己的网络错误页 */
  private static String refusedUrl;

  /**
   * fixture:两个**先于可见内容出现在文档里**的隐藏 file input(模拟 X 那种「同名控件有两份」)
   * 加一组状态元素(禁用的按钮、勾上的复选框、隐藏的 span)
   */
  private static final String PAGE = """
      <html><head><meta charset="utf-8"><title>network-target</title></head><body>
        <div class="dlg" style="display:none">
          <input id="firstFile" class="pick" type="file" accept=".jpg">
        </div>
        <div class="dlg" style="display:none">
          <input id="secondFile" class="pick" type="file" accept=".jpg">
        </div>
        <div id="firstGot">none</div>
        <div id="secondGot">none</div>
        <button id="enabledBtn" type="button">能点的按钮</button>
        <button id="disabledBtn" type="button" disabled>禁用的按钮</button>
        <input id="agree" type="checkbox" checked>
        <span id="hiddenSpan" style="display:none">看不见</span>
        <script>
          document.getElementById('firstFile').addEventListener('change', function (e) {
            document.getElementById('firstGot').textContent =
              (e.target.files && e.target.files[0]) ? e.target.files[0].name : 'none';
          });
          document.getElementById('secondFile').addEventListener('change', function (e) {
            document.getElementById('secondGot').textContent =
              (e.target.files && e.target.files[0]) ? e.target.files[0].name : 'none';
          });
        </script>
      </body></html>
      """;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-network-profile");
    uploadDir = Files.createTempDirectory("browser-use-network-upload");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    System.setProperty(UploadStore.KEY_DIR, uploadDir.toString());
    ChromeBrowser.resetForTests();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();

    // 拿一个**真的没人监听**的高位端口(不要用 1 这类低位端口:Chrome 会把它们当 unsafe port,
    // 报的是 ERR_UNSAFE_PORT,那就不是「连不上」这条路径了)
    try (ServerSocket probe = new ServerSocket(0)) {
      refusedUrl = "http://127.0.0.1:" + probe.getLocalPort() + "/";
    }

    markFile = uploadDir.resolve("图样.jpg");
    Files.write(markFile, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 });

    service = new PlaywrightService();
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() throws Exception {
    if (id != null) {
      try {
        service.close(id);
      } catch (RuntimeException ignored) {
        // 收尾,关不掉不影响结论
      }
    }
    if (server != null) {
      server.stop(0);
    }
    for (String key : List.of(ChromeBrowser.KEY_PROFILE_DIR, UploadStore.KEY_DIR)) {
      System.clearProperty(key);
    }
    ChromeBrowser.resetForTests();
    deleteTree(profileDir);
    deleteTree(uploadDir);
  }

  private static void deleteTree(Path root) {
    if (root == null) {
      return;
    }
    // 关掉任务之后浏览器进程还要收尾一小会儿,profile 目录里的 sqlite 日志此刻可能仍被占着
    // (实测 C:\...\Temp\...\first_party_sets.db-journal: being used by another process)。
    // 清的是系统临时目录,删不掉就算了 —— 绝不能让一个收尾动作把用例判成失败。
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (Exception ignored) {
          // 见上
        }
      }
    } catch (Exception ignored) {
      // 见上
    }
  }

  private static Kv data(RespBodyVo response) {
    assertTrue("命令应当成功,实际:" + response.getMsg(), response.isOk());
    Object data = response.getData();
    return data instanceof Kv ? (Kv) data : new Kv();
  }

  private static int intOf(Kv kv, String key) {
    Object value = kv.get(key);
    return value instanceof Number ? ((Number) value).intValue() : -1;
  }

  private static String textOf(String selector) {
    return String.valueOf(TestFlakeGuard.retry("read " + selector,
        () -> service.getInstance(id).page.textContent(selector)));
  }

  /** 每次用例前把页面加载回来 */
  private void open() {
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        service.getInstance(id).page.navigate(base);
        service.getInstance(id).page.waitForTimeout(200);
        return;
      } catch (PlaywrightException e) {
        // 上一轮「打不开的地址」留下的错误页可能比 navigate 的异常**晚一拍**才提交,
        // 于是这一条导航被判成 "interrupted by another navigation to chrome-error://…"。
        // 这是测试夹具的复位动作,等它落定再来一次(不是把真实失败吞掉:三次都失败照样抛)
        if (attempt == 3) {
          throw e;
        }
        service.getInstance(id).page.waitForTimeout(500);
      }
    }
  }

  /**
   * 碰过「打不开的地址」之后,让错误页的提交落定
   *
   * <p>实测:导航到连不上的端口时,异常比 {@code chrome-error://chromewebdata/} 这次提交来得早,
   * 于是紧接着的下一条导航会被它打断。不睡这一下,后面每条用例都会随机挂。
   */
  private void settleAfterNetworkFailure() {
    service.getInstance(id).page.waitForTimeout(700);
  }

  // ==================== 一、Chrome 自己的网络错误页 ====================

  /**
   * 连不上的地址必须报成 {@code NETWORK_ERROR},而不是「等待元素可操作超时」
   *
   * <p>老回执只有 {@code Timeout 30000ms exceeded} + {@code errorCode:ACTION_TIMEOUT}:
   * 调用方(尤其是看不到画面的 agent)会顺着「超时」去查选择器、去重试,而这一页根本没有内容。
   */
  @Test
  public void unreachableAddressIsReportedAsNetworkError() {
    open();
    RespBodyVo response = service.goToUrl(id, refusedUrl);
    settleAfterNetworkFailure();

    assertFalse("打不开的地址应当失败", response.isOk());
    assertTrue("要报 NETWORK_ERROR,实际:" + response.getMsg(), response.getMsg().contains("NETWORK_ERROR"));
    assertFalse("不能再报成 ACTION_TIMEOUT,实际:" + response.getMsg(),
        response.getMsg().contains("ACTION_TIMEOUT"));

    Kv data = response.getData() instanceof Kv ? (Kv) response.getData() : new Kv();
    assertEquals("errorCode 要能被程序读到", ActionError.NETWORK_ERROR, data.getStr("errorCode"));
    assertNotNull("要给出 Chrome 的错误码,免得调用方自己猜", data.getStr("netError"));
    assertTrue("错误码形如 ERR_xxx,实际:" + data.getStr("netError"), data.getStr("netError").startsWith("ERR_"));
    assertTrue("提示里要带上错误码,实际:" + response.getMsg(),
        response.getMsg().contains(data.getStr("netError")));
    assertEquals("这是本机网络/代理的瞬时状态,不是页面语义决定的:要标成可重试", true, data.get("retryable"));
    assertEquals("退避建议不能是 0", true, ((Number) data.get("retryAfterMs")).longValue() > 0);
    assertEquals("要留下原始请求地址(错误页会把 urlAfter 变成 chrome-error://)",
        refusedUrl, data.getStr("urlRequested"));
  }

  /**
   * 探测本身:导航失败之后,页面上能读到 Chrome 自己渲染的错误码
   *
   * <p>这是「异常里没有 {@code net::ERR_xxx}」时唯一的证据来源(实测 TUN 抖动那次,异常只是一句
   * {@code Timeout 30000ms exceeded},真相只在页面上)。所以单独钉一次,不依赖异常文本。
   *
   * <p><b>为什么要轮询</b>:错误页的提交比 {@code navigate} 抛异常**晚一拍** —— 全量跑的时候
   * (机器更忙)紧跟着探一次会读到 {@code about:blank},单独跑却每次都过。生产代码正是靠
   * 4 次 × 400ms 的探测扛住这件事的,这里也照做,否则这就是一条「单独绿、全量红」的假用例。
   */
  @Test
  public void chromeErrorPageIsDetectedFromThePageItself() {
    open();
    try {
      TestFlakeGuard.retry("navigate to refused", () -> service.getInstance(id).page.navigate(refusedUrl));
    } catch (PlaywrightException expected) {
      // 导航本来就会失败,这里就是要它停在错误页上
    }
    PlaywrightService.ChromeNetworkFailure failure = null;
    for (int attempt = 1; attempt <= 10 && failure == null; attempt++) {
      failure = PlaywrightService.detectChromeNetworkFailure(service.getInstance(id).page);
      if (failure == null) {
        service.getInstance(id).page.waitForTimeout(200);
      }
    }
    settleAfterNetworkFailure();
    assertNotNull("这是一张 Chrome 网络错误页,必须能探到", failure);
    assertEquals("要读出 Chrome 的错误码", "ERR_CONNECTION_REFUSED", failure.netError);
  }

  /** 正常页面不能被误判成网络错误页(探测是只读的,但误判会把好回执变成坏回执) */
  @Test
  public void normalPageIsNotMistakenForAnErrorPage() {
    open();
    assertNull("正常页面上不该报网络错误页",
        PlaywrightService.detectChromeNetworkFailure(service.getInstance(id).page));
  }

  /** 能打开的地址照旧按成功返回,不要多挂一个 netError 出来 */
  @Test
  public void reachableAddressStaysASuccess() {
    open();
    // 用一个和当前地址不同的路径:同址导航会变成 reload,掩盖「这次导航到底成功没有」
    RespBodyVo response = service.goToUrl(id, base + "/ok");
    assertTrue("能打开的地址必须成功,实际:" + response.getMsg(), response.isOk());
    Kv data = data(response);
    assertNull("成功回执里不该有 netError", data.getStr("netError"));
    assertNull("成功回执里不该有 errorCode", data.getStr("errorCode"));
  }

  // ==================== 二、upload_file 命中多个 file input ====================

  /**
   * 选择器命中多个隐藏 file input 时,回执必须说清「匹配到几个、用了第几个」
   *
   * <p>老回执里只有 {@code target:selector=...},调用方连「这里存在歧义」都不知道。
   */
  @Test
  public void uploadReportsWhichTwinItPicked() {
    open();
    Kv result = data(service.uploadFile(id, null, "input.pick", markFile.toString(), null));

    assertEquals("这个选择器确实命中两个隐藏的 file input", 2, intOf(result, "matched"));
    assertEquals("没有传 nth 时按文档顺序取第一个", 0, intOf(result, "chosenIndex"));
    assertEquals("两个都是隐藏的,可见的 0 个", 0, intOf(result, "visibleMatched"));
    assertNotNull("要说明这次是怎么挑的、以及怎么精确指定", result.getStr("selectorNote"));
    assertTrue("提示里要给出 nth 这条出路,实际:" + result.getStr("selectorNote"),
        result.getStr("selectorNote").contains("nth"));
    assertEquals("文件要真的落到第一个 input 上", "图样.jpg", textOf("#firstGot"));
    assertEquals("不能顺手也塞给第二个", "none", textOf("#secondGot"));
  }

  /**
   * {@code nth} 要能把文件送进**另一个**隐藏副本
   *
   * <p>这是这个能力存在的理由:X 那种「两个 file input 都是 0×0」的页面上,可见性挑选没有意义,
   * 只能靠序号。回读也必须跟着序号走({@code elementResolveScript} 以前写死
   * {@code document.querySelector},永远只认第一个)。
   */
  @Test
  public void nthSendsTheFileToTheOtherHiddenTwin() {
    open();
    Kv result = data(service.uploadFile(id, null, "input.pick", markFile.toString(), null, null, 1));

    assertEquals(2, intOf(result, "matched"));
    assertEquals("要按调用方指定的序号定位", 1, intOf(result, "chosenIndex"));
    assertEquals(true, result.get("explicitNth"));
    assertEquals("文件要落到第二个 input 上", "图样.jpg", textOf("#secondGot"));
    assertEquals("第一个不该被动到", "none", textOf("#firstGot"));
  }

  @Test
  public void nthOutOfRangeIsReportedWithTheTotal() {
    open();
    RespBodyVo response = service.uploadFile(id, null, "input.pick", markFile.toString(), null, null, 5);
    assertFalse("越界的 nth 应当失败", response.isOk());
    assertTrue("要说清越界与实际命中数,实际:" + response.getMsg(),
        response.getMsg().contains("越界") && response.getMsg().contains("2"));
  }

  /** 选择器一个都没匹配到时当场失败(报 ELEMENT_NOT_FOUND),不要等满可操作性超时 */
  @Test
  public void uploadSelectorMatchingNothingFailsFast() {
    open();
    long begin = System.currentTimeMillis();
    RespBodyVo response = service.uploadFile(id, null, "#no-such-file-input", markFile.toString(), null);
    long elapsed = System.currentTimeMillis() - begin;

    assertFalse(response.isOk());
    assertTrue("要报 ELEMENT_NOT_FOUND,实际:" + response.getMsg(),
        response.getMsg().contains("ELEMENT_NOT_FOUND"));
    assertTrue("要当场失败,实际耗时 " + elapsed + "ms", elapsed < 2_000);
  }

  // ==================== 三、is_* 与 get_element_text 同形 ====================

  @Test
  public void isEnabledAcceptsSelector() {
    open();
    Kv enabled = data(service.isEnabled(id, null, "#enabledBtn", null));
    assertEquals(true, enabled.get("enabled"));
    assertEquals("按选择器定位时要说清定位到了什么", "选择器 #enabledBtn", enabled.getStr("target"));

    Kv disabled = data(service.isEnabled(id, null, "#disabledBtn", null));
    assertEquals("禁用的按钮要如实回 false", false, disabled.get("enabled"));
  }

  @Test
  public void isVisibleAcceptsSelector() {
    open();
    assertEquals(false, data(service.isVisible(id, null, "#hiddenSpan", null)).get("visible"));
    assertEquals(true, data(service.isVisible(id, null, "#enabledBtn", null)).get("visible"));
  }

  @Test
  public void isCheckedAcceptsSelector() {
    open();
    assertEquals(true, data(service.isChecked(id, null, "#agree", null)).get("checked"));
  }

  /** 按索引那条路照旧(不能为了加 selector 把老用法弄坏) */
  @Test
  public void indexBasedStateReadStillWorks() {
    open();
    // 状态读取类的索引来自**快照**(没有快照时服务端只会回「请先调用 get_browser_state」),
    // 所以这里先取一次快照,再从元素清单里按 DOM id 找出那个按钮的索引
    service.getBrowserState(id, Boolean.FALSE, 0, Boolean.TRUE, 200, Boolean.FALSE, Boolean.FALSE);
    Kv map = data(service.getInteractiveMap(id));
    Object elements = map.get("elements");
    assertTrue("元素清单应当是数组,实际:" + elements, elements instanceof List);
    int index = -1;
    for (Object raw : (List<?>) elements) {
      if (raw instanceof Kv && "enabledBtn".equals(((Kv) raw).getStr("id"))) {
        index = intOf((Kv) raw, "index");
        break;
      }
    }
    assertTrue("快照里应当有 #enabledBtn", index >= 0);

    Kv result = data(service.isEnabled(id, index));
    assertEquals(true, result.get("enabled"));
    assertEquals("index=" + index, result.getStr("target"));
  }

  @Test
  public void stateReadWithoutIndexOrSelectorIsRejected() {
    RespBodyVo response = service.isEnabled(id, null, null, null);
    assertFalse(response.isOk());
    assertTrue("要说清二选一,实际:" + response.getMsg(),
        response.getMsg().contains("index") && response.getMsg().contains("selector"));
  }

  @Test
  public void stateReadOnMissingSelectorIsReported() {
    open();
    RespBodyVo response = service.isVisible(id, null, "#not-here", null);
    assertFalse(response.isOk());
    assertTrue("没匹配到要说清是「没匹配到」,实际:" + response.getMsg(),
        response.getMsg().contains("没匹配到元素"));
  }
}
