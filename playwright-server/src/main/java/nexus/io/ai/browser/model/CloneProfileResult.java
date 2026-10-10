package nexus.io.ai.browser.model;

import com.jfinal.kit.Kv;

public record CloneProfileResult(BrowserProfile profile, long copiedFiles, long copiedBytes, String warning) {
  public Kv toKv() {
    return Kv.by("profile", profile.toKv()).set("copiedFiles", copiedFiles)
        .set("copiedBytes", copiedBytes).set("warning", warning);
  }
}
