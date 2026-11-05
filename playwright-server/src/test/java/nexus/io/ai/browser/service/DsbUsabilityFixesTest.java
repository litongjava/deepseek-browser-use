package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import javax.imageio.ImageIO;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import nexus.io.ai.browser.actions.registry.CommandSchema;
import nexus.io.ai.browser.util.CredentialRedactor;

public class DsbUsabilityFixesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void temporaryProfileCleanupDeletesContentAndIsIdempotent() throws Exception {
    Path root = temporary.newFolder().toPath();
    Files.createDirectory(root.resolve("Default"));
    Files.writeString(root.resolve("Default").resolve("Cookies"), "test-only");
    assertTrue(PlaywrightService.deleteEphemeral(root));
    assertFalse(Files.exists(root));
    assertTrue(PlaywrightService.deleteEphemeral(root));
  }

  @Test
  public void ephemeralProfileHistoryStaysNewWhenTasksReuseIt() throws Exception {
    Path root = temporary.newFolder().toPath();
    Files.createDirectory(root.resolve("Default"));
    PlaywrightService.SharedBrowser browser = new PlaywrightService.SharedBrowser(
        root, null, true, false, false, null, null, BrowserChoice.CHROME);
    browser.ephemeralDir = root;
    PlaywrightService.applyProfileHistory(browser, Set.of(root.toAbsolutePath().normalize().toString()));
    assertEquals(false, browser.profileSeenBefore);
    PlaywrightService.applyProfileHistory(browser, Set.of(root.toAbsolutePath().normalize().toString()));
    assertEquals(false, browser.profileSeenBefore);
    assertFalse(Files.exists(root.resolve(PlaywrightService.PROFILE_MARKER)));
    String note = PlaywrightService.profileLoginNote(browser);
    assertTrue(note, note.contains("临时 profile"));
    assertTrue(note, note.contains("最后一个任务关闭后删除"));
    assertFalse(note, note.contains("之前用过"));
  }

  @Test
  public void persistentProfileHistoryChangesOnlyOnNextBrowserLaunch() throws Exception {
    Path root = temporary.newFolder().toPath();
    PlaywrightService.SharedBrowser first = new PlaywrightService.SharedBrowser(
        root, null, true, false, false, null, null, BrowserChoice.CHROME);
    PlaywrightService.applyProfileHistory(first, Set.of());
    assertEquals(false, first.profileSeenBefore);
    PlaywrightService.applyProfileHistory(first, Set.of(root.toString()));
    assertEquals(false, first.profileSeenBefore);
    PlaywrightService.SharedBrowser next = new PlaywrightService.SharedBrowser(
        root, null, true, false, false, null, null, BrowserChoice.CHROME);
    PlaywrightService.applyProfileHistory(next, Set.of());
    assertEquals(true, next.profileSeenBefore);
    assertEquals("chrome", next.previousProfileBrowser);
    assertTrue(PlaywrightService.profileLoginNote(next).contains("目录历史不代表网站已登录"));
  }

  @Test
  public void encodedImageDimensionsDoNotConfusePixelsWithCssViewport() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", bytes);
    Kv diagnostic = Kv.by("viewport", Kv.by("width", 20).set("height", 10));
    CapturePolicy.describeImage(bytes.toByteArray(), diagnostic);
    assertEquals(30, ((Kv) diagnostic.get("image")).getInt("width").intValue());
    assertEquals(20, ((Kv) diagnostic.get("image")).getInt("height").intValue());
    assertEquals(20, ((Kv) diagnostic.get("viewport")).getInt("width").intValue());
    Kv invalid = new Kv();
    CapturePolicy.describeImage(new byte[] {1, 2, 3}, invalid);
    assertFalse(invalid.containsKey("image"));
  }

  @Test
  public void failedCaptureDoesNotProbeAndUnavailableObservationPreservesCapture() {
    int[] calls = {0};
    Page page = (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[] {Page.class},
        (proxy, method, args) -> {
          if (method.getName().equals("isClosed")) {
            return false;
          }
          if (method.getName().equals("waitForFunction")) {
            calls[0]++;
            assertEquals(250, ((Page.WaitForFunctionOptions) args[2]).timeout.intValue());
            throw new PlaywrightException("observation unavailable");
          }
          return null;
        });
    BrowserInstance instance = new BrowserInstance(42L, null, page, null, null);
    Kv capture = Kv.by("stage", "circuit-open");
    Kv data = Kv.by("capture", capture);
    PlaywrightService.describeCapture(instance, data, false);
    assertEquals(0, calls[0]);
    capture.set("stage", "complete");
    PlaywrightService.describeCapture(instance, data, true);
    assertEquals(1, calls[0]);
    assertEquals("complete", capture.get("stage"));
    assertEquals(false, ((Kv) capture.get("observation")).get("available"));
  }

  @Test
  public void redactorSurvivesLongQuotedValues() {
    String huge = "x".repeat(2_000_000);
    String text = "console: token \"apiKey\":\"" + huge + "\" and \"note\":\"" + huge + "\"";
    String redacted = CredentialRedactor.redactBody(text, "***");
    assertNotNull(redacted);
    assertFalse(redacted.contains("\"apiKey\":\"xxx"));
  }

  @Test
  public void redactorStillMasksEscapedPairs() {
    String redacted = CredentialRedactor.redactBody("not json \"password\":\"a\\\"b\" \"other\":\"keep\"", "***");
    assertFalse(redacted.contains("a\\\"b"));
    assertTrue(redacted.contains("keep"));
  }

  @Test
  public void numericTextParameterIsAccepted() {
    JSONObject args = new JSONObject();
    args.put("text", 2026);
    List<String> errors = CommandSchema.validate("wait_for_text", args, "wait_for_text");
    assertTrue(errors.toString(), errors.isEmpty());
    assertEquals("2026", args.get("text"));
  }

  @Test
  public void decimalTextKeepsItsSpelling() {
    JSONObject args = JSONObject.parseObject("{\"text\":1.10}");
    assertTrue(CommandSchema.validate("wait_for_text", args, "wait_for_text").isEmpty());
    assertEquals("1.10", args.get("text"));
  }

  @Test
  public void popupTimeoutIsBounded() {
    JSONObject args = new JSONObject();
    args.put("selector", "#a");
    args.put("popupTimeoutMs", 999999);
    assertFalse(CommandSchema.validate("click_element_by_selector", args, "c").isEmpty());
  }

  @Test
  public void jsErrorsBecomePlainMessages() {
    Map<String, Object> reason = new LinkedHashMap<>();
    reason.put("reason", new IllegalStateException("TypeError: Failed to fetch\n    at window.fetch (x.js:1:1)"));
    List<Object> settled = new ArrayList<>();
    settled.add(reason);
    settled.add(1);
    Object plain = PlaywrightService.plainJsValue(settled, 0);
    Object error = ((Map<?, ?>) ((List<?>) plain).get(0)).get("reason");
    assertEquals("TypeError: Failed to fetch", ((Kv) error).getStr("error"));
    assertEquals(1, ((List<?>) plain).get(1));
  }

  @Test
  public void refusedCommandCarriesItsCodeAndHint() {
    CommandRefusedException refused = new CommandRefusedException(ActionError.BROWSER_CONFIG_CONFLICT, "differs", "use --port");
    assertEquals(ActionError.BROWSER_CONFIG_CONFLICT, refused.code());
    assertEquals("use --port", refused.hint());
    assertTrue(refused instanceof IllegalStateException);
  }
}
