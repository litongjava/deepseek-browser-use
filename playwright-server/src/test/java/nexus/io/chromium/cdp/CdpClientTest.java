package nexus.io.chromium.cdp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;

/**
 * {@code nexus.io.chromium.cdp} 这个库的自测:自己拉一个 Chrome,把每一层都真跑一遍
 *
 * <p>
 * <b>刻意不依赖项目里的任何其它类</b>(不借 {@code ChromeLauncher}、不借 {@code ChromeBrowser}):
 * 这个包号称「可以整包搬出去单独用」,那就得有一条**只用它自己 + JDK** 的验证路径,否则「独立库」这句话
 * 没有任何东西在保证。所以这里自己探 Chrome、自己挑端口、自己拉进程、自己等端口通。
 *
 * <p>
 * 用临时 profile 目录:不碰开发机上那份真实登录态(与 README 里「测试必须用临时 profile」的规矩一致)。
 */
public class CdpClientTest {

  private static Process chrome;
  private static Path profileDir;
  private static String endpoint;

  @BeforeClass
  public static void startChrome() throws Exception {
    Path executable = findChrome();
    Assume.assumeTrue("这台机器上没有安装 Google Chrome,跳过", executable != null);

    profileDir = Files.createTempDirectory("cdp-client-test");
    int port = freePort();
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.add("--headless=new");
    command.add("--remote-debugging-port=" + port);
    command.add("--user-data-dir=" + profileDir.toAbsolutePath());
    command.add("--no-first-run");
    command.add("--no-default-browser-check");
    command.add("--disable-search-engine-choice-screen");
    command.add("--disable-breakpad");
    command.add("--disable-gpu");
    command.add("--disable-background-timer-throttling");
    command.add("--disable-renderer-backgrounding");
    command.add("--disable-backgrounding-occluded-windows");
    command.add("--remote-allow-origins=*");
    command.add("--window-size=1280,900");

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    chrome = builder.start();
    // 不读输出会把 Chrome 堵死;这里只要不堵,内容不关心
    Thread drain = new Thread(() -> drain(chrome), "cdp-test-chrome-output");
    drain.setDaemon(true);
    drain.start();

    endpoint = "http://127.0.0.1:" + port;
    waitForEndpoint(endpoint, 30_000);
  }

  @AfterClass
  public static void stopChrome() {
    if (chrome != null) {
      chrome.destroy();
      try {
        if (!chrome.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
          chrome.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        chrome.destroyForcibly();
      }
    }
    deleteRecursively(profileDir);
  }

  /** 一个页面上的完整往返:导航、求值、标题、截图、异步表达式 */
  @Test
  public void pageRoundTripWorks() {
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      assertTrue("应当报出真实浏览器版本", browser.product().startsWith("Chrome/"));
      assertNotNull("协议版本应当取得到", browser.version().getString("protocolVersion"));

      try (CdpPage page = browser.newPage()) {
        page.navigate("data:text/html,<title>cdp round trip</title><h1 id='h'>hello</h1>");
        assertEquals("cdp round trip", page.title());
        assertEquals("hello", String.valueOf(page.evaluate("document.getElementById('h').textContent")));

        // 纯 JS 运算:验证 returnByValue 真的把值解开了,而不是只给一个远程对象句柄
        Object sum = page.evaluate("1 + 1");
        assertEquals("1+1 应当解开成 2", 2, ((Number) sum).intValue());

        // awaitPromise:服务端靠这个能力读 fetch 的结果,必须真的可用
        assertEquals("ok", page.evaluate("(async () => { await new Promise(r => setTimeout(r, 30)); return 'ok'; })()"));

        byte[] png = page.screenshot();
        assertTrue("截图应当非空", png.length > 100);
        assertEquals("应当是 PNG 魔数", 0x89, png[0] & 0xFF);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);

        assertTrue("地址栏应当是那个 data URL", page.url().startsWith("data:text/html"));
      }
    }
  }

  /** 会话不能串台:两个页签各自求值,拿到的是自己的值 */
  @Test
  public void sessionsAreIsolated() {
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      try (CdpPage first = browser.newPage(); CdpPage second = browser.newPage()) {
        first.navigate("data:text/html,<title>first</title>");
        second.navigate("data:text/html,<title>second</title>");
        assertEquals("first", first.title());
        assertEquals("second", second.title());
        assertFalse("两个页签是不同的 target", first.targetId().equals(second.targetId()));
      }
    }
  }

  /** 浏览器级设置(PDF 那条路丢掉的下载目录、以及权限)必须真的发得出去、收得到成功回执 */
  @Test
  public void browserLevelSettingsAreAccepted() throws IOException {
    Path downloadDir = Files.createTempDirectory("cdp-test-downloads");
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      browser.setDownloadBehavior(downloadDir);
      // 这里是 CDP 自己的 PermissionType 名字,不是 Playwright 的 clipboard-read / clipboard-write ——
      // 两者不是一套,照抄 Playwright 会得到 "Unknown permission type"(见下面的 negative 用例)
      browser.grantPermissions(List.of("clipboardReadWrite", "clipboardSanitizedWrite", "notifications"), null);
      browser.setAutoAttach(false);
      assertFalse("不该有待配对的在途命令", browser.connection().pendingCount() > 0);
    } finally {
      deleteRecursively(downloadDir);
    }
  }

  /**
   * Playwright 的权限名在 CDP 上会被拒 —— 这条用例把这个坑钉住
   *
   * <p>
   * 「照抄 Playwright 的 {@code clipboard-read}」是个很容易犯、而且**后果静默**的错误:一旦被
   * try/catch 吃掉,表现就是剪贴板类按钮点了没反应,站点侧完全看不出是权限问题。所以这里不只验证
   * 正确名字能过,还要验证错误名字**确实会失败**。
   */
  @Test
  public void playwrightPermissionNamesAreRejectedByCdp() {
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      try {
        browser.grantPermissions(List.of("clipboard-read"), null);
        fail("CDP 不认 Playwright 的 clipboard-read,这里应当失败");
      } catch (CdpException e) {
        assertTrue("应当是协议级拒绝:" + e.getMessage(), e.isProtocolError());
        assertTrue("报错里应当说清是权限名不认识:" + e.getMessage(), e.getMessage().contains("permission"));
      }
    }
  }

  /** target 列表能解析出来,且页签都认得对 */
  @Test
  public void targetsAreListed() {
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      try (CdpPage page = browser.newPage()) {
        List<CdpTarget> pages = browser.pages();
        assertTrue("至少应当能看到刚建的这个页签", pages.stream().anyMatch(p -> p.targetId().equals(page.targetId())));
        assertTrue("页签的类型应当是 page", pages.stream().allMatch(CdpTarget::isPage));
      }
    }
  }

  /** 失败要分得清:页面里抛异常 vs 协议级拒绝(方法不存在),调用方才能决定怎么办 */
  @Test
  public void failuresAreTyped() {
    try (CdpBrowser browser = CdpBrowser.connect(endpoint)) {
      try (CdpPage page = browser.newPage()) {
        page.navigate("data:text/html,<title>typed</title>");
        try {
          page.evaluate("(() => { throw new Error('boom-in-page'); })()");
          fail("页面里抛异常时应当抛 CdpException");
        } catch (CdpException e) {
          assertFalse("页面内异常不是协议级拒绝", e.isProtocolError());
          assertTrue("异常信息里应当带得上页面自己的报错:" + e.getMessage(), e.getMessage().contains("boom-in-page"));
        }
      }
      try {
        browser.connection().send("NoSuchDomain.noSuchMethod");
        fail("不存在的方法应当抛 CdpException");
      } catch (CdpException e) {
        assertTrue("应当是协议级拒绝", e.isProtocolError());
        assertNotNull("应当带上协议错误码", e.code());
        assertEquals("NoSuchDomain.noSuchMethod", e.method());
      }
    }
  }

  /** 连接对象自己也要能说清「还活着吗、有多少在途命令」 */
  @Test
  public void connectionReportsItsOwnState() {
    CdpConnection connection = CdpConnection.connect(endpoint);
    try {
      assertFalse(connection.isClosed());
      assertEquals(0, connection.pendingCount());
      JSONObject version = connection.send("Browser.getVersion");
      assertTrue(version.getString("product").startsWith("Chrome/"));
    } finally {
      connection.close();
    }
    assertTrue("关掉之后应当自报已关闭", connection.isClosed());
  }

  // ==================== 测试自己的启动辅助(刻意不借用项目代码) ====================

  private static Path findChrome() {
    List<String> candidates = new ArrayList<>();
    String localAppData = System.getenv("LOCALAPPDATA");
    String programFiles = System.getenv("ProgramFiles");
    String programFilesX86 = System.getenv("ProgramFiles(x86)");
    if (programFiles != null) {
      candidates.add(programFiles + "\\Google\\Chrome\\Application\\chrome.exe");
    }
    if (programFilesX86 != null) {
      candidates.add(programFilesX86 + "\\Google\\Chrome\\Application\\chrome.exe");
    }
    if (localAppData != null) {
      candidates.add(localAppData + "\\Google\\Chrome\\Application\\chrome.exe");
    }
    candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
    candidates.add("/usr/bin/google-chrome");
    candidates.add("/usr/bin/google-chrome-stable");
    for (String candidate : candidates) {
      Path path = Path.of(candidate);
      if (Files.isRegularFile(path)) {
        return path;
      }
    }
    return null;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  private static void waitForEndpoint(String endpoint, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/json/version"))
        .timeout(Duration.ofSeconds(2)).GET().build();
    Exception last = null;
    while (System.currentTimeMillis() < deadline) {
      if (!chrome.isAlive()) {
        throw new IllegalStateException("Chrome 在调试端口就绪之前就退出了");
      }
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body().contains("webSocketDebuggerUrl")) {
          return;
        }
      } catch (IOException e) {
        last = e;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("等 Chrome 的调试端口超时:" + endpoint
        + (last == null ? "" : "(" + last.getMessage() + ")"));
  }

  private static void drain(Process process) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      while (reader.readLine() != null) {
        // 读干净即可
      }
    } catch (IOException ignored) {
      // 进程退出时读会失败,正常
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Chrome 还占着的文件删不掉就算了,临时目录交给系统回收
        }
      }
    } catch (IOException ignored) {
      // 同上
    }
  }
}
