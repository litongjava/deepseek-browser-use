package nexus.io.ai.browser.validation;

import java.util.Locale;
import java.util.Set;

import com.microsoft.playwright.options.WaitUntilState;
import nexus.io.tio.utils.validator.ParameterValidator;

public final class NavigationValidator {
  private NavigationValidator() {
  }

  public static WaitUntilState stage(String value) {
    String stage = value == null ? "domcontentloaded" : value.trim().toLowerCase(Locale.ROOT);
    ParameterValidator.require(Set.of("commit", "domcontentloaded", "load", "networkidle").contains(stage),
        "waitUntil must be commit, domcontentloaded, load or networkidle");
    return WaitUntilState.valueOf(stage.toUpperCase(Locale.ROOT));
  }

  public static void timeout(Integer value) {
    ParameterValidator.require(value == null || (value >= 1 && value <= 120000),
        "timeoutMs must be between 1 and 120000");
  }
}
