package nexus.io.ai.browser.model;

import com.jfinal.kit.Kv;

public record BrowserProfile(String name, String userDataDir, String profileDirectory, String source,
    boolean selectable, String reason, String displayName) {
  public Kv toKv() {
    return Kv.by("name", name).set("userDataDir", userDataDir).set("profileDirectory", profileDirectory)
        .set("source", source).set("selectable", selectable).set("reason", reason).set("displayName", displayName);
  }
}
