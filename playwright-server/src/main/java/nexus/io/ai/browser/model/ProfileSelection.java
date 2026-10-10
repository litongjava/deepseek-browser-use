package nexus.io.ai.browser.model;

public record ProfileSelection(String profile, String userDataDir, String profileDirectory) {
  public boolean explicit() {
    return profile != null || userDataDir != null || profileDirectory != null;
  }
}
