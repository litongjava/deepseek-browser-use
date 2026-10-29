package nexus.io.ai.browser.service;

import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScreenshotPathTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void restartedTaskSkipsExistingEvidenceAndFailedAllocations() throws Exception {
    Path directory = folder.getRoot().toPath();
    Files.writeString(directory.resolve("shot-1.png"), "old evidence");
    Files.writeString(directory.resolve("shot-2.png"), "older evidence");
    AtomicInteger sequence = new AtomicInteger();
    assertEquals(directory.resolve("shot-3.png").toString(), PlaywrightService.nextShotPath(directory, sequence));
    assertEquals(directory.resolve("shot-4.png").toString(), PlaywrightService.nextShotPath(directory, sequence));
    assertEquals("old evidence", Files.readString(directory.resolve("shot-1.png")));
  }
}
