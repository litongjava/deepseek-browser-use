package nexus.io.ai.browser.actions.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * å‘½ä»¤æ ‡å¿—ä½çš„**å”¯ä¸€å£°æ˜Žå¤„**ã€‚
 *
 * <p>
 * ä»¥å‰è¿™äº›æ ‡å¿—æ•£åœ¨ {@code ActionService} çš„å››å¼ æ‰‹å†™åå•é‡Œ(PAGE_CHANGING / SPURIOUS_RETRY_SAFE /
 * SPURIOUS_VERIFIABLE / LONG_RUNNING),è€Œä¸€å¼ åå•åªå›žç­”ä¸€ä¸ªé—®é¢˜,äºŽæ˜¯ä¸€æ¡å‘½ä»¤ä¼šåŒæ—¶å‡ºçŽ°åœ¨ä¸¤ä¸‰å¼ åå•ä¸Šã€‚
 * åŽæžœæ˜¯:åŠ ä¸€æ¡å‘½ä»¤è¦æ”¹ 5-6 å¤„(åå• Ã—4 + æµ‹è¯•è¡¨ + æŠ€èƒ½æ–‡æ¡£),æ¼æŽ‰ä¸€å¤„**ä¸ä¼šæŠ¥é”™**,åªä¼šåœ¨è¿è¡Œæ—¶
 * æ‚„æ‚„æ¢è¡Œä¸º â€”â€” é‚£æ¡å‘½ä»¤ä¸å†è‡ªåŠ¨æˆªå›¾ã€ä¸å†é‡å‘,æˆ–è€…è¶…æ—¶æŒ‰ 90 ç§’è€Œä¸æ˜¯ 15 åˆ†é’Ÿç®—ã€‚
 *
 * <p>
 * çŽ°åœ¨:ä¸€æ¡å‘½ä»¤ä¸€è¡Œ,å†™æ¸…å®ƒæœ‰å“ªäº›æ ‡å¿—;å››å¼ åå•ç”± {@link #namesWith} æ´¾ç”Ÿã€‚
 * åŠ å‘½ä»¤åªæ”¹è¿™é‡Œä¸€å¤„ã€‚æ´¾ç”Ÿç»“æžœç”± {@code CommandSetGoldenTest} ç”¨**æ”¹åŠ¨å‰å¯¼å‡ºçš„å¿«ç…§**é€å­—é’‰ä½ â€”â€”
 * æˆå‘˜å˜äº†æµ‹è¯•å°±ä¼šå¤±è´¥,è€Œä¸æ˜¯ç­‰åˆ°çº¿ä¸Šæ‰å‘çŽ°ç•™è¯æ²¡äº†ã€‚
 *
 * <p>
 * æ ‡å¿—çš„å«ä¹‰(ä¸ºä»€ä¹ˆæŸæ¡å‘½ä»¤è¯¥å¸¦æŸä¸ªæ ‡å¿—,åˆ¤æ–­ä¾æ®å†™åœ¨ {@code ActionService} å¯¹åº”åå•çš„ javadoc é‡Œ):
 * <ul>
 * <li>{@link Flag#PAGE_CHANGING} â€”â€” åŠ¨ä½œåŽè¦åšè‡ªåŠ¨æˆªå›¾ç•™è¯;</li>
 * <li>{@link Flag#RETRY_SAFE} â€”â€” ä¼ªæ•…éšœ(äº‹ä»¶æ³µä¼ªæ•…éšœ)åŽå¯ä»¥æ”¾å¿ƒé‡å‘;</li>
 * <li>{@link Flag#VERIFIABLE} â€”â€” ä¼ªæ•…éšœåŽèƒ½è¯»å›žç»“æžœè‡ªè¡Œæ ¸å¯¹;</li>
 * <li>{@link Flag#LONG_RUNNING} â€”â€” é•¿ä»»åŠ¡,è¶…æ—¶æŒ‰ç¡¬ä¸Šé™ç®—è€Œä¸æ˜¯æ™®é€šä¸Šé™ã€‚</li>
 * </ul>
 */
public final class CommandFlags {

  /** ä¸€æ¡å‘½ä»¤èƒ½å¸¦çš„æ ‡å¿— */
  public enum Flag {
    PAGE_CHANGING,
    RETRY_SAFE,
    VERIFIABLE,
    LONG_RUNNING
  }

  /**
   * ä¸åœ¨å‘½ä»¤è¡¨é‡Œã€ç”± handler ç›´æŽ¥è·¯ç”±çš„ç«¯ç‚¹ã€‚
   *
   * <p>
   * {@code commands} æ˜¯æ‰¹é‡å…¥å£,ç”± {@code ActionService.batchExecute} ç›´æŽ¥å¤„ç†,
   * {@code CommandTableTest} é‡Œæœ‰ä¸€æ¡æ–­è¨€ä¸“é—¨é’‰ä½ã€Œå®ƒä¸è¯¥å‡ºçŽ°åœ¨å‘½ä»¤è¡¨é‡Œã€ã€‚æ‰€ä»¥å®ƒå‡ºçŽ°åœ¨æ ‡å¿—å£°æ˜Žé‡Œ
   * æ˜¯æ­£ç¡®çš„ â€”â€” ä½†å®ƒ**å¿…é¡»**å¸¦ç€ {@code LONG_RUNNING},å¦åˆ™æ‰¹é‡ä¼šå¤±åŽ» 15 åˆ†é’Ÿç¡¬è¶…æ—¶å…œåº•,
   * æ‚„æ‚„é€€å›ž 90 ç§’æ™®é€šè¶…æ—¶ã€‚
   *
   * <p>
   * è¿™ä¸ªé›†åˆè¦ä¿æŒæœ€å°:æ¯å¤šä¸€é¡¹,å°±è¯´æ˜Žã€Œå‘½ä»¤è¡¨æ˜¯å”¯ä¸€çš„æ–¹æ³•åˆ†å‘å¤„ã€è¿™æ¡å‰æåˆå°‘ä¸€åˆ†ã€‚
   */
  public static final Set<String> ROUTED_OUTSIDE_COMMAND_TABLE = Set.of("commands");

  private static final Map<String, Set<Flag>> DECLARED = declare();

  private CommandFlags() {
  }

  private static void flag(Map<String, Set<Flag>> map, String command, Flag... flags) {
    Set<Flag> assigned = map.computeIfAbsent(command, key -> new LinkedHashSet<>());
    Collections.addAll(assigned, flags);
  }

  private static Map<String, Set<Flag>> declare() {
    Map<String, Set<Flag>> map = new LinkedHashMap<>();
    flag(map, "ask_user", Flag.LONG_RUNNING);
    flag(map, "bring_to_front", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "check_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "clear_console_logs", Flag.RETRY_SAFE);
    flag(map, "clear_dialog", Flag.RETRY_SAFE);
    flag(map, "clear_text", Flag.PAGE_CHANGING);
    flag(map, "click_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "click_element_by_role", Flag.PAGE_CHANGING);
    flag(map, "click_element_by_selector", Flag.PAGE_CHANGING);
    flag(map, "click_element_by_text", Flag.PAGE_CHANGING);
    flag(map, "close_modal", Flag.PAGE_CHANGING);
    flag(map, "close_other_tabs", Flag.PAGE_CHANGING);
    flag(map, "close_tab", Flag.PAGE_CHANGING);
    flag(map, "commands", Flag.LONG_RUNNING);
    flag(map, "diff_dom_text", Flag.RETRY_SAFE);
    flag(map, "double_click_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "download_image", Flag.LONG_RUNNING);
    flag(map, "drag_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "execute_js", Flag.PAGE_CHANGING);
    flag(map, "extract_markdown", Flag.RETRY_SAFE);
    flag(map, "extract_structured_data", Flag.RETRY_SAFE);
    flag(map, "find_text", Flag.RETRY_SAFE);
    flag(map, "focus_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "get_browser_state", Flag.RETRY_SAFE);
    flag(map, "get_config", Flag.RETRY_SAFE);
    flag(map, "get_console_logs", Flag.RETRY_SAFE);
    flag(map, "get_cookies", Flag.RETRY_SAFE);
    flag(map, "get_dialog", Flag.RETRY_SAFE);
    flag(map, "get_dropdown_options", Flag.RETRY_SAFE);
    flag(map, "get_element_attribute", Flag.RETRY_SAFE);
    flag(map, "get_element_box", Flag.RETRY_SAFE);
    flag(map, "get_element_count", Flag.RETRY_SAFE);
    flag(map, "get_element_html", Flag.RETRY_SAFE);
    flag(map, "get_element_listeners", Flag.RETRY_SAFE);
    flag(map, "get_element_screenshot", Flag.RETRY_SAFE);
    flag(map, "get_element_text", Flag.RETRY_SAFE);
    flag(map, "get_element_value", Flag.RETRY_SAFE);
    flag(map, "get_form_state", Flag.RETRY_SAFE);
    flag(map, "get_human_input", Flag.LONG_RUNNING);
    flag(map, "get_interactive_map", Flag.RETRY_SAFE);
    flag(map, "get_job", Flag.RETRY_SAFE);
    flag(map, "get_js_dialog", Flag.RETRY_SAFE);
    flag(map, "get_local_storage", Flag.RETRY_SAFE);
    flag(map, "get_modals", Flag.RETRY_SAFE);
    flag(map, "get_page_snapshot", Flag.RETRY_SAFE);
    flag(map, "get_requests", Flag.RETRY_SAFE);
    flag(map, "get_response_body", Flag.RETRY_SAFE);
    flag(map, "get_tabs", Flag.RETRY_SAFE);
    flag(map, "get_title", Flag.RETRY_SAFE);
    flag(map, "get_url", Flag.RETRY_SAFE);
    flag(map, "go_back", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "go_forward", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "go_to_url", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "hover_and_click", Flag.PAGE_CHANGING);
    flag(map, "hover_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "input_text", Flag.PAGE_CHANGING);
    flag(map, "input_text_by_label", Flag.PAGE_CHANGING);
    flag(map, "input_text_by_selector", Flag.PAGE_CHANGING);
    flag(map, "is_checked", Flag.RETRY_SAFE);
    flag(map, "is_enabled", Flag.RETRY_SAFE);
    flag(map, "is_visible", Flag.RETRY_SAFE);
    flag(map, "key_down", Flag.PAGE_CHANGING);
    flag(map, "key_up", Flag.PAGE_CHANGING);
    flag(map, "list_frames", Flag.RETRY_SAFE);
    flag(map, "list_jobs", Flag.RETRY_SAFE);
    flag(map, "list_methods", Flag.RETRY_SAFE);
    flag(map, "list_recipes", Flag.RETRY_SAFE);
    flag(map, "list_tables", Flag.RETRY_SAFE);
    flag(map, "list_tasks", Flag.RETRY_SAFE);
    flag(map, "mouse_click", Flag.PAGE_CHANGING);
    flag(map, "mouse_click_by_selector", Flag.PAGE_CHANGING);
    flag(map, "mouse_down", Flag.PAGE_CHANGING);
    flag(map, "mouse_move", Flag.PAGE_CHANGING);
    flag(map, "mouse_up", Flag.PAGE_CHANGING);
    flag(map, "mouse_wheel", Flag.PAGE_CHANGING);
    flag(map, "navigate", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "new_tab", Flag.PAGE_CHANGING, Flag.VERIFIABLE);
    flag(map, "ocr_image", Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "pdf", Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "reload", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "request_human_input", Flag.LONG_RUNNING);
    flag(map, "run_recipe", Flag.LONG_RUNNING);
    flag(map, "screenshot", Flag.RETRY_SAFE);
    flag(map, "scroll", Flag.PAGE_CHANGING);
    flag(map, "scroll_to_text", Flag.PAGE_CHANGING);
    flag(map, "select_dropdown_option", Flag.PAGE_CHANGING);
    flag(map, "send_keys", Flag.PAGE_CHANGING);
    flag(map, "press_key", Flag.PAGE_CHANGING);
    flag(map, "set_cookie", Flag.PAGE_CHANGING);
    flag(map, "set_credentials", Flag.PAGE_CHANGING);
    flag(map, "set_dialog_behavior", Flag.RETRY_SAFE);
    flag(map, "set_headers", Flag.RETRY_SAFE);
    flag(map, "set_local_storage", Flag.PAGE_CHANGING);
    flag(map, "set_media", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "set_offline", Flag.RETRY_SAFE);
    flag(map, "set_viewport", Flag.PAGE_CHANGING, Flag.RETRY_SAFE);
    flag(map, "submit_human_input", Flag.LONG_RUNNING);
    flag(map, "switch_tab", Flag.PAGE_CHANGING);
    flag(map, "switch_tab_by_url", Flag.PAGE_CHANGING);
    flag(map, "type_text", Flag.PAGE_CHANGING);
    flag(map, "uncheck_element_by_index", Flag.PAGE_CHANGING);
    flag(map, "upload_file", Flag.PAGE_CHANGING);
    flag(map, "wait", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_count", Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_element", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_function", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_idle", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_load", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_response", Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_stable", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_text", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    flag(map, "wait_for_url", Flag.PAGE_CHANGING, Flag.RETRY_SAFE, Flag.LONG_RUNNING);
    return Collections.unmodifiableMap(map);
  }

  /** å¸¦æŸä¸ªæ ‡å¿—çš„å…¨éƒ¨å‘½ä»¤å(æ´¾ç”Ÿç»“æžœ,ä¸å¯å˜) */
  public static Set<String> namesWith(Flag flag) {
    Set<String> names = new TreeSet<>();
    for (Map.Entry<String, Set<Flag>> entry : DECLARED.entrySet()) {
      if (entry.getValue().contains(flag)) {
        names.add(entry.getKey());
      }
    }
    return Collections.unmodifiableSet(names);
  }

  /** å£°æ˜Žé‡Œå‡ºçŽ°è¿‡çš„å…¨éƒ¨å‘½ä»¤å(å« {@link #ROUTED_OUTSIDE_COMMAND_TABLE} ä¹‹å¤–çš„ä¸€åˆ‡) */
  public static Set<String> declaredNames() {
    return Collections.unmodifiableSet(new TreeSet<>(DECLARED.keySet()));
  }
}
