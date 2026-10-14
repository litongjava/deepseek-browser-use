package nexus.io.ai.browser.service;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.jfinal.kit.Kv;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.BrowserContext;
import com.sun.net.httpserver.HttpServer;

import nexus.io.model.body.RespBodyVo;

/** Visible browser tests on local fixtures and an isolated profile. */
public class BrowserObservationDefaultsTest {
  private static PlaywrightService service;
  private static Long id;
  private static HttpServer server;
  private static ExecutorService workers;
  private static final CountDownLatch releaseImage = new CountDownLatch(1);
  private static final CountDownLatch imageStarted = new CountDownLatch(1);
  private static String base;

  @BeforeClass
  public static void start() throws Exception {
    Path profile = Files.createTempDirectory("dsb-observation-defaults-");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profile.toString());
    System.clearProperty(PlaywrightService.KEY_CAPTURE_ENABLED);
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    id = service.start(null, false, "chrome", false);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext("/slow-image", exchange -> {
      imageStarted.countDown();
      try {
        releaseImage.await(15, TimeUnit.SECONDS);
        exchange.sendResponseHeaders(204, -1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.createContext("/", exchange -> {
      byte[] html = "<html><body><button id='ready'>Ready</button><img src='/slow-image'></body></html>"
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(200, html.length);
      exchange.getResponseBody().write(html);
      exchange.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterClass
  public static void stop() {
    releaseImage.countDown();
    if (service != null) {
      service.shutdown();
    }
    if (server != null) {
      server.stop(0);
    }
    if (workers != null) {
      workers.shutdownNow();
    }
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    System.clearProperty(PlaywrightService.KEY_CAPTURE_ENABLED);
    ChromeBrowser.resetForTests();
  }

  private static Kv data(RespBodyVo result) {
    assertTrue(result.getMsg(), result.isOk());
    return (Kv) result.getData();
  }

  private static Page page() {
    return service.getInstance(id).page;
  }

  @Test
  public void highlightOnlyChangesDrawingAndCleansListeners() {
    page().setContent("""
        <html><body><button id="target" style="cursor:pointer" onclick="window.clicked=true">
        <span style="cursor:pointer">Click me</span></button><input id="field"></body></html>
        """);
    page().evaluate("""
        () => {
          const add = window.addEventListener.bind(window), remove = window.removeEventListener.bind(window);
          window.testListeners = new Set();
          window.addEventListener = (type, fn, options) => {
            if (type === 'scroll' || type === 'resize') window.testListeners.add(fn);
            return add(type, fn, options);
          };
          window.removeEventListener = (type, fn, options) => {
            window.testListeners.delete(fn);
            return remove(type, fn, options);
          };
        }
        """);
    Kv plain = data(service.getBrowserState(id, null, -1));
    assertEquals(0, page().locator("#playwright-highlight-container").count());
    Kv drawn = data(service.getBrowserState(id, true, -1));
    assertEquals(plain.getStr("text"), drawn.getStr("text"));
    assertEquals(1, page().locator("#playwright-highlight-container").count());
    Number count = (Number) page().evaluate("window.testListeners.size");
    assertTrue(count.intValue() > 0);
    for (int i = 0; i < 3; i++) {
      data(service.getBrowserState(id, true, -1));
      assertEquals(count, page().evaluate("window.testListeners.size"));
    }
    Kv again = data(service.getBrowserState(id, false, -1));
    assertEquals(plain.getStr("text"), again.getStr("text"));
    assertEquals(0, page().locator("#playwright-highlight-container").count());
    assertEquals(0, ((Number) page().evaluate("window.testListeners.size")).intValue());
    int index = service.getInstance(id).domState.getSelectorMap().entrySet().stream()
        .filter(e -> "button".equals(e.getValue().getTagName())).findFirst().orElseThrow().getKey();
    data(service.clickElementByIndex(id, index));
    assertEquals(true, page().evaluate("window.clicked"));
  }

  @Test
  public void screenshotsRequireOptInAndKeepTextEvidence() {
    page().setContent("<html><body><button>Capture fixture</button></body></html>");
    Kv plain = data(service.getBrowserState(id, null, 0));
    assertNull(plain.get("screenshot"));
    assertEquals("not-requested", plain.getStr("screenshot_skipped"));
    assertTrue(Files.isRegularFile(Path.of(plain.getStr("state_file").substring(1))));
    Kv shot = data(service.getBrowserState(id, false, 0, false, null, false, false, true));
    assertNotNull(shot.get("screenshot"));
    assertTrue(Files.isRegularFile(Path.of(shot.getStr("screenshot_path"))));
    assertFalse(PlaywrightService.captureEnabled());
    assertNull(data(service.getBrowserState(id, false, 0)).get("screenshot"));
  }

  @Test
  public void idleBrowserAndDataPageAreRetainedWithoutBeingClaimed() {
    page().setContent("<html><body>User result to keep</body></html>");
    BrowserContext original = service.getInstance(id).context;
    BrowserInstance finished = service.getInstance(id);
    Page resultPage = page();
    Kv closed = data(service.close(id));
    assertEquals(true, closed.get("browserRetained"));
    assertEquals(true, closed.get("pagesRetained"));
    assertFalse(resultPage.isClosed());
    assertTrue(finished.listenerCleanup.isEmpty());
    int logCount = finished.consoleLogs.size();
    resultPage.evaluate("console.log('user browsing after completion')");
    assertEquals(logCount, finished.consoleLogs.size());
    Page popup = resultPage.waitForPopup(() -> resultPage.evaluate("window.open('about:blank')"));
    assertTrue(finished.pages.isEmpty());
    Page userTab = original.newPage();
    userTab.setContent("User opened this tab after completion");
    try {
      service.start(null, true, "chrome", false);
      fail("Changing configuration must require explicit browser closure");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("explicitly close"));
    }
    assertFalse(resultPage.isClosed());
    id = service.start(null, false, "chrome", false);
    assertSame(original, service.getInstance(id).context);
    assertNotSame(resultPage, page());
    assertNotSame(popup, page());
    assertNotSame(userTab, page());
    assertTrue(resultPage.innerText("body").contains("User result to keep"));
    try {
      service.restartContext(service.getInstance(id));
      fail("Context settings must not destroy retained about:blank data pages");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("User pages are open"));
    }
    assertFalse(resultPage.isClosed());
    Long other = service.start(null, false, "chrome", false);
    assertFalse(service.close(id, true).isOk());
    assertNotNull(service.getInstance(id));
    data(service.close(other));
  }

  @Test
  public void navigationStageDoesNotWaitForUnneededImages() throws Exception {
    try {
      Kv navigated = data(service.goToUrl(id, base, "domcontentloaded", 5000));
      assertEquals("domcontentloaded", navigated.getStr("waitUntil"));
      assertEquals("Ready", page().innerText("#ready"));
      assertTrue(imageStarted.await(5, TimeUnit.SECONDS));
      assertNotEquals("complete", page().evaluate("document.readyState"));
      RespBodyVo invalid = new ActionService(service).execute(id, "go_to_url", new JSONObject(Map.of("url", base, "waitUntil", "bad")));
      assertFalse(invalid.isOk());
    } finally {
      releaseImage.countDown();
    }
    assertEquals("load", data(service.goToUrl(id, base, "load", 5000)).getStr("waitUntil"));
  }

  @Test
  public void explicitCloseReleasesAnAlreadyFinishedBrowser() {
    Page oldPage = page();
    data(service.close(id));
    assertFalse(oldPage.isClosed());
    assertEquals(false, data(service.close(id, true)).get("browserRetained"));
    assertTrue(oldPage.isClosed());
    id = service.start(null, false, "chrome", false);
  }
}
