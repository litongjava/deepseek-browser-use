package nexus.io.ai.browser.service;

/**
 * A command refused before any side effect, so the caller knows that nothing happened and can act on the hint.
 */
public class CommandRefusedException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final String hint;

  public CommandRefusedException(String code, String message, String hint) {
    super(message);
    this.code = code;
    this.hint = hint;
  }

  public String code() {
    return code;
  }

  public String hint() {
    return hint;
  }
}
