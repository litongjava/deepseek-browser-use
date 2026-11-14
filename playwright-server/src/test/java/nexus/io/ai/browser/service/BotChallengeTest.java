package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;

import nexus.io.model.body.RespBodyVo;

/**
 * A Cloudflare interstitial is reported as such, on local data URLs that copy its markers.
 *
 * <p>A headless browser opening the Cloudflare dashboard got "Just a moment..." with an ordinary
 * success receipt, so the caller only learned from the page text that it was held at a bot check.
 */
public class BotChallengeTest {

  private static PlaywrightService service;
  private static ActionService actions;
  private static Long id;
  private static Path profileDir;

  @BeforeClass
  public static void start() throws Exception {
    profileDir = Files.createTempDirectory("browser-use-challenge-profile");
    System.setProperty(ChromeBrowser.KEY_PROFILE_DIR, profileDir.toString());
    ChromeBrowser.resetForTests();
    service = new PlaywrightService();
    actions = new ActionService(service);
    id = service.start(null, true);
  }

  @AfterClass
  public static void stop() throws Exception {
    if (id != null) {
      service.close(id);
    }
    System.clearProperty(ChromeBrowser.KEY_PROFILE_DIR);
    ChromeBrowser.resetForTests();
    if (profileDir != null) {
      try (java.util.stream.Stream<Path> paths = Files.walk(profileDir)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void interstitialIsReportedOnNavigationAndInState() {
    Kv data = open("<title>Just a moment...</title><div id=challenge-running>Performing security verification</div>");
    Kv challenge = (Kv) data.get("challenge");
    assertEquals(ActionError.BOT_CHALLENGE, challenge.getStr("errorCode"));
    assertEquals("cloudflare", challenge.getStr("vendor"));
    assertTrue(data.getStr("warning"), data.getStr("warning").contains("start --display"));
    assertEquals(Boolean.TRUE, challenge.get("headless"));

    RespBodyVo state = actions.execute(id, "get_browser_state", new JSONObject());
    assertTrue(state.getMsg(), state.isOk());
    assertEquals(ActionError.BOT_CHALLENGE, ((Kv) ((Kv) state.getData()).get("challenge")).getStr("errorCode"));
  }

  @Test
  public void ordinaryPagesAreNotReported() {
    Kv data = open("<title>Sign in</title><form><input name=user>"
        + "<div class=cf-turnstile data-sitekey=x></div><button>Log in</button></form>");
    assertNull(data.get("challenge"));
    assertNull(data.get("warning"));
  }

  private static Kv open(String html) {
    JSONObject params = new JSONObject();
    params.put("url", "data:text/html," + html);
    RespBodyVo result = actions.execute(id, "go_to_url", params);
    assertTrue(result.getMsg(), result.isOk());
    return (Kv) result.getData();
  }
}
