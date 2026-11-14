package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * A headed browser on an X display beside a headless one, on the same service.
 *
 * <p>Headless mode and the display are fixed when a browser launches, so a headed start next to a
 * running headless browser used to be refused and needed a second service port. Needs the local
 * Chrome and the X display {@code :1} (a VNC session); skipped without them.
 */
public class SideBrowserTest {

  private static final String DISPLAY = ":1";
  private static PlaywrightService service;
  private static ActionService actions;
  private static Path profileDir;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-side-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    actions = new ActionService(service);
  }

  @AfterClass
  public static void stop() throws Exception {
    if (service != null) {
      service.shutdown();
    }
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
    for (Path dir : List.of(profileDir, profileDir.resolveSibling(profileDir.getFileName() + "-display-1"))) {
      if (dir != null && Files.exists(dir)) {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
          for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
          }
        }
      }
    }
  }

  @Test
  public void keysAndProfilesTellBrowsersApart() {
    assertEquals("headless", PlaywrightService.browserKey(true, ":1"));
    assertEquals("display::1", PlaywrightService.browserKey(false, ":1"));
    Path side = PlaywrightService.sideProfileDir("display::1");
    assertEquals(profileDir.getFileName() + "-display-1", side.getFileName().toString());
    assertEquals(profileDir.toAbsolutePath().getParent(), side.getParent());
  }

  @Test
  public void staleLocksOfADeadBrowserAreCleared() throws Exception {
    Path dir = Files.createTempDirectory("browser-use-side-lock");
    try {
      // A lock naming a process that no longer exists, as a killed Chrome leaves it.
      Files.createSymbolicLink(dir.resolve("SingletonLock"), Path.of("dsb-test-host-999999999"));
      Files.createSymbolicLink(dir.resolve("SingletonCookie"), Path.of("1"));
      PlaywrightService.clearStaleLocks(dir);
      assertFalse(Files.exists(dir.resolve("SingletonLock"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
      assertFalse(Files.exists(dir.resolve("SingletonCookie"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void headedTaskOnADisplayRunsBesideAHeadlessOne() {
    assumeTrue("needs the X display " + DISPLAY, Files.exists(Path.of("/tmp/.X11-unix/X1")));
    assumeTrue("needs the local Chrome", ChromeBrowser.executablePath() != null);
    long headless = service.start(901L, true);
    JSONObject headed = new JSONObject();
    headed.put("display", DISPLAY);
    headed.put("headless", false);
    RespBodyVo started = actions.execute(902L, "start", headed);
    assertTrue(started.getMsg(), started.isOk());

    Kv quiet = service.browserInfo(headless);
    Kv shown = service.browserInfo(902L);
    assertEquals(Boolean.TRUE, quiet.get("headless"));
    assertEquals(Boolean.FALSE, shown.get("headless"));
    assertEquals(DISPLAY, shown.get("display"));
    assertNotSame(quiet.get("profileDir"), shown.get("profileDir"));

    Kv listed = (Kv) service.listTasks().getData();
    List<?> sides = (List<?>) listed.get("sideBrowsers");
    assertEquals(1, sides.size());
    assertEquals("display::1", ((Kv) sides.get(0)).get("key"));

    // Both work independently.
    for (long id : new long[] { headless, 902L }) {
      JSONObject script = new JSONObject();
      script.put("body", "1 + " + id);
      RespBodyVo result = actions.execute(id, "execute_js", script);
      assertTrue(result.getMsg(), result.isOk());
    }

    // A second headed start on the same display joins the side browser.
    RespBodyVo joined = actions.execute(903L, "start", headed);
    assertTrue(joined.getMsg(), joined.isOk());
    assertEquals(shown.get("profileDir"), service.browserInfo(903L).get("profileDir"));

    // closeBrowser refuses while another task shares the side browser, then closes only it.
    assertFalse(service.close(902L, true).isOk());
    assertTrue(service.close(903L, false).isOk());
    RespBodyVo closed = service.close(902L, true);
    assertTrue(closed.getMsg(), closed.isOk());
    assertTrue(PlaywrightService.SIDE_BROWSERS.isEmpty());

    // The headless task is untouched.
    JSONObject script = new JSONObject();
    script.put("body", "document.readyState");
    RespBodyVo still = actions.execute(headless, "execute_js", script);
    assertTrue(still.getMsg(), still.isOk());
    assertNotNull(service.browserInfo(headless));
    assertTrue(service.close(headless, false).isOk());
  }
}
