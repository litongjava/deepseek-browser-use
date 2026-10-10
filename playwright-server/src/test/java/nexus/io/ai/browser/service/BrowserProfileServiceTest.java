package nexus.io.ai.browser.service;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.io.IOException;
import nexus.io.ai.browser.json.JsonResponses;
import nexus.io.tio.utils.json.Json;
import nexus.io.model.body.RespBodyVo;
import com.alibaba.fastjson2.JSONArray;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.alibaba.fastjson2.JSONObject;
import com.jfinal.kit.Kv;
import nexus.io.ai.browser.model.*;
import nexus.io.ai.browser.actions.registry.CommandTable;
import nexus.io.tio.utils.validator.ParameterValidationException;

public class BrowserProfileServiceTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private BrowserProfileService service;
  private Path source;
  private Path named;

  @Before public void setup() throws Exception {
    source = temporary.newFolder("source").toPath();
    named = temporary.newFolder("named").toPath();
    Files.createDirectory(source.resolve("Profile 2"));
    Files.writeString(source.resolve("Profile 2/Preferences"), "{\"profile\":{\"name\":\"Original\"}}");
    Files.writeString(source.resolve("Profile 2/Cookies"), "synthetic-test-data");
    Files.writeString(source.resolve("Local State"), "{\"profile\":{\"last_used\":\"Profile 2\",\"info_cache\":{\"Profile 2\":{\"name\":\"Original\"},\"Default\":{}}}}");
    System.setProperty("browser.profiles.dir", named.toString());
    System.setProperty(ChromeBrowser.KEY_USER_DATA_DIR, source.toString());
    ChromeBrowser.resetForTests();
    service = new BrowserProfileService();
  }

  @After public void cleanup() {
    System.clearProperty("browser.profiles.dir");
    System.clearProperty(ChromeBrowser.KEY_USER_DATA_DIR);
    ChromeBrowser.resetForTests();
  }

  private CloneProfileResult cloneNamed(String name) {
    return service.cloneProfile(new CloneProfileRequest(name, source.toString(), "Profile 2"));
  }

  @Test public void cloneCopiesOnlyRequestedProfileAndRenamesCopy() throws Exception {
    String original = Files.readString(source.resolve("Profile 2/Preferences"));
    String originalState = Files.readString(source.resolve("Local State"));
    CloneProfileResult result = cloneNamed("litongjava");
    BrowserProfile profile = result.profile();
    assertEquals("managed", profile.source());
    assertEquals("Default", profile.profileDirectory());
    assertEquals(original, Files.readString(source.resolve("Profile 2/Preferences")));
    assertEquals(originalState, Files.readString(source.resolve("Local State")));
    assertEquals("synthetic-test-data", Files.readString(named.resolve("litongjava/Default/Cookies")));
    JSONObject preferences = JSONObject.parseObject(Files.readString(named.resolve("litongjava/Default/Preferences")));
    assertEquals("litongjava", preferences.getJSONObject("profile").getString("name"));
    JSONObject state = JSONObject.parseObject(Files.readString(named.resolve("litongjava/Local State")));
    assertEquals("Default", state.getJSONObject("profile").getString("last_used"));
    assertEquals(1, state.getJSONObject("profile").getJSONObject("info_cache").size());
    assertTrue(service.listProfiles().stream().anyMatch(p -> "litongjava".equals(p.name()) && p.selectable()));
    assertEquals(profile.userDataDir(), service.resolve(new ProfileSelection("litongjava", null, null)).userDataDir());
  }

  @Test public void existingDestinationIsNeverReplaced() throws Exception {
    cloneNamed("same");
    Files.writeString(named.resolve("same/sentinel"), "keep");
    try {
      cloneNamed("same");
      fail("Expected destination conflict");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("already exists"));
    }
    assertEquals("keep", Files.readString(named.resolve("same/sentinel")));
  }

  @Test public void lockMarkersRejectCopyWithoutCreatingDestination() throws Exception {
    Files.writeString(source.resolve("SingletonLock"), "locked");
    try {
      cloneNamed("locked");
      fail("Expected lock rejection");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("lock"));
    }
    assertFalse(Files.exists(named.resolve("locked")));
  }

  @Test public void rejectsUnknownNamesAndInvalidSelections() {
    try {
      service.resolve(new ProfileSelection("missing", null, null));
      fail("Unknown profile must not be created");
    } catch (ParameterValidationException expected) {
      assertFalse(Files.exists(named.resolve("missing")));
    }
    for (ProfileSelection selection : new ProfileSelection[] {
        new ProfileSelection("../escape", null, null), new ProfileSelection("x", source.toString(), null),
        new ProfileSelection(null, null, "Default"), new ProfileSelection(null, source.toString(), "../Default")}) {
      try {
        service.resolve(selection);
        fail("Invalid selection accepted");
      } catch (ParameterValidationException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test public void symlinkCopyFailsWhenPlatformPermitsLinks() throws Exception {
    Path link = source.resolve("Profile 2/link");
    try {
      Files.createSymbolicLink(link, source.resolve("Local State"));
    } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
      Assume.assumeNoException(unsupported);
    }
    try {
      cloneNamed("linked");
      fail("Symbolic link accepted");
    } catch (IllegalStateException expected) {
      assertFalse(Files.exists(named.resolve("linked")));
    }
  }

  @Test public void activeTasksRejectProfileSwitchBeforeBrowserLaunch() throws Exception {
    cloneNamed("one");
    cloneNamed("two");
    BrowserProfile one = service.resolve(new ProfileSelection("one", null, null));
    PlaywrightService.SharedBrowser shared = new PlaywrightService.SharedBrowser(Path.of(one.userDataDir()),
        ChromeBrowser.executablePath(), true, false, false, null, null, BrowserChoice.CHROME);
    shared.selectedProfile = one;
    assertTrue(shared.matches(false, BrowserChoice.CHROME, one));
    assertFalse(shared.matches(false, BrowserChoice.CHROME,
        service.resolve(new ProfileSelection("two", null, null))));
    Field current = PlaywrightService.class.getDeclaredField("sharedBrowser");
    current.setAccessible(true);
    Field tasks = PlaywrightService.class.getDeclaredField("INSTANCES");
    tasks.setAccessible(true);
    @SuppressWarnings("unchecked") Map<Long, BrowserInstance> instances = (Map<Long, BrowserInstance>) tasks.get(null);
    Object previous = current.get(null);
    current.set(null, shared);
    instances.put(123L, new BrowserInstance(123L, null, null, Path.of(one.userDataDir()), null));
    try {
      new PlaywrightService().start(124L, false, "chrome", null, new ProfileSelection("two", null, null));
      fail("Switch while active must fail");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("profile"));
    } finally {
      instances.remove(123L);
      current.set(null, previous);
    }
  }

  @Test public void commandLineOwnershipDistinguishesDefaultAndCustomRoots() {
    assertFalse(BrowserProfileService.commandUsesRoot("chrome.exe --type=renderer", source, true));
    assertTrue(BrowserProfileService.commandUsesRoot("chrome.exe", source, true));
    assertFalse(BrowserProfileService.commandUsesRoot("chrome.exe", source, false));
    assertTrue(BrowserProfileService.commandUsesRoot("chrome.exe \"--user-data-dir=" + source + "\"", source, false));
    assertTrue(BrowserProfileService.commandUsesRoot("chrome.exe --user-data-dir=\"" + source + "\"", source, false));
    assertFalse(BrowserProfileService.commandUsesRoot("chrome.exe --user-data-dir=\"" + named + "\"", source, true));
  }

  @Test public void interruptedCloneDoesNotPublishDestination() {
    Thread.currentThread().interrupt();
    try {
      cloneNamed("cancelled");
      fail("Interrupted copy must fail");
    } catch (IllegalStateException expected) {
      assertFalse(Files.exists(named.resolve("cancelled")));
    } finally {
      Thread.interrupted();
    }
  }

  @Test public void cacheAndTransientLocksAreExcluded() throws Exception {
    Files.createDirectory(source.resolve("Profile 2/Cache"));
    Files.writeString(source.resolve("Profile 2/Cache/disposable"), "cache");
    Files.writeString(source.resolve("Profile 2/LOCK"), "");
    cloneNamed("clean");
    assertFalse(Files.exists(named.resolve("clean/Default/Cache")));
    assertFalse(Files.exists(named.resolve("clean/Default/LOCK")));
    assertTrue(Files.exists(source.resolve("Profile 2/Cache/disposable")));
  }

  @Test public void productionJsonSerializesProfileListsAndCloneAsObjects() throws Exception {
    boolean previous = JsonResponses.active();
    try {
      JsonResponses.init();
      PlaywrightService playwright = new PlaywrightService();
      RespBodyVo cloned = playwright.cloneProfile(
          new CloneProfileRequest("wire", source.toString(), "Profile 2"));
      String cloneJson = Json.getJson().toJson(cloned);
      JSONObject cloneData = JSONObject.parseObject(cloneJson).getJSONObject("data");
      assertTrue(cloneData.get("profile") instanceof JSONObject);
      assertEquals("wire", cloneData.getJSONObject("profile").getString("name"));
      assertTrue(cloneData.getLongValue("copiedFiles") > 0);
      String listJson = Json.getJson().toJson(playwright.listProfiles());
      JSONArray profiles = JSONObject.parseObject(listJson).getJSONObject("data").getJSONArray("profiles");
      assertFalse(profiles.isEmpty());
      for (Object profile : profiles) {
        assertTrue("Profiles must be JSON objects, not record strings", profile instanceof JSONObject);
      }
      assertFalse(listJson.contains("BrowserProfile["));
    } finally {
      Json.installSkipNull(previous);
    }
  }

  @Test public void localStateDisplayNameOverridesPreferences() throws Exception {
    JSONObject state = JSONObject.parseObject(Files.readString(source.resolve("Local State")));
    state.getJSONObject("profile").getJSONObject("info_cache").getJSONObject("Profile 2").put("name", "Preferred Name");
    Files.writeString(source.resolve("Local State"), state.toJSONString());
    BrowserProfile profile = service.listProfiles().stream()
        .filter(p -> p.source().equals("chrome") && p.profileDirectory().equals("Profile 2")).findFirst().orElseThrow();
    assertEquals("Preferred Name", profile.displayName());
  }

  @Test public void profileCommandsAreRegisteredAndValidationUsesEnvelope() {
    assertNotNull(CommandTable.get("list_profiles"));
    assertNotNull(CommandTable.get("clone_profile"));
    RespBodyVo response = new ActionService(new PlaywrightService()).execute(null, "clone_profile", new JSONObject());
    assertFalse(response.isOk());
    assertEquals("INVALID_ARGUMENT", ((Kv) response.getData()).getStr("errorCode"));
  }
}
