package nexus.io.ai.browser.validation;

import nexus.io.tio.utils.validator.ParameterValidator;

public final class ProfileValidator {
  private ProfileValidator() {
  }

  public static String name(String value) {
    String name = ParameterValidator.text(value, "name", 64);
    ParameterValidator.require(name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"),
        "Profile name must use letters, digits, underscores or hyphens");
    ParameterValidator.require(!name.matches("(?i:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])"), "Reserved profile name");
    return name;
  }

  public static String directory(String value) {
    String directory = value == null ? "Default" : ParameterValidator.text(value, "profileDirectory", 128);
    ParameterValidator.require(directory.matches("[A-Za-z0-9][A-Za-z0-9 _-]*"),
        "profileDirectory must be a single directory name");
    return directory;
  }

  public static String path(String value, String field) {
    return ParameterValidator.text(value, field, 4096);
  }
}
