# 参数定义（自动生成）

由 CommandTable / CommandSchema 生成；运行时使用 `dsb run list_methods -p details=true -p filter=wait_for_function --json` 查询。

`!` 表示必填，`|` 表示至少提供其中一个，逗号分隔不同必选组。类型必须匹配 JSON；*Ms 为毫秒，*Seconds 为秒。

公共参数：commandTimeoutMs:integer sensitive:boolean expect:object precondition:object readback:string snapshotVersion:integer retryOnSpurious:boolean async:boolean。等待时间范围及断言规则见 [命令可靠性](command-reliability.md)。未声明的参数仍可能通过 unknownParams 提示，不代表支持。

| 方法 | 参数 | 必选组 | 枚举 |
| --- | --- | --- | --- |
| `attach` | endpoint:string! targetId:string url:string |  | {} |
| `targets` | endpoint:string! |  | {} |
| `get_diagnostics` |  |  | {} |
| `cancel_command` | commandId:string! |  | {} |
| `start` | headless:boolean browser:string networkRecording:boolean fakeMedia:boolean profile:string userDataDir:string profileDirectory:string display:string ephemeral:boolean |  | {} |
| `list_profiles` |  |  | {} |
| `delete_profile` | name:string! |  | {} |
| `clone_profile` | name:string sourceUserDataDir:string sourceProfileDirectory:string |  | {} |
| `close` | closeBrowser:boolean |  | {} |
| `shutdown` |  |  | {} |
| `navigate` | url:string! waitUntil:string timeoutMs:integer |  | {waitUntil=[load, domcontentloaded, networkidle, commit]} |
| `go_to_url` | url:string! waitUntil:string timeoutMs:integer |  | {waitUntil=[load, domcontentloaded, networkidle, commit]} |
| `go_back` |  |  | {} |
| `go_forward` |  |  | {} |
| `reload` |  |  | {} |
| `get_url` |  |  | {} |
| `get_title` |  |  | {} |
| `get_browser_state` | highlight:boolean viewportExpansion:integer includeElements:boolean maxElements:integer includeFrames:boolean strictSnapshot:boolean screenshot:boolean |  | {} |
| `list_frames` | refresh:boolean |  | {} |
| `wait` | seconds:integer! |  | {} |
| `click_element_by_index` | index:integer! mode:string timeoutMs:integer expectPopup:boolean popupTimeoutMs:integer |  | {} |
| `double_click_element_by_index` | index:integer! mode:string |  | {} |
| `hover_element_by_index` | index:integer! |  | {} |
| `focus_element_by_index` | index:integer! |  | {} |
| `check_element_by_index` | index:integer! mode:string |  | {} |
| `uncheck_element_by_index` | index:integer! mode:string |  | {} |
| `input_text` | index:integer! text:string! mode:string |  | {} |
| `type_text` | index:integer! text:string! |  | {} |
| `upload_file` | trigger:string path:string timeoutMs:integer frame:string responseUrl:string contentBase64:string url:string index:integer selector:string filename:string contentType:string nth:integer | index / selector / trigger,path / contentBase64 / url | {} |
| `drag_element_by_index` | index:integer! targetIndex:integer! |  | {} |
| `send_keys` | keys:string! |  | {} |
| `keyboard_type` | text:string! selector:string frame:string delayMs:integer |  | {} |
| `press_key` | key:string keys:string | key / keys | {} |
| `key_down` | keys:string! |  | {} |
| `key_up` | keys:string! |  | {} |
| `get_dropdown_options` | index:integer! |  | {} |
| `select_dropdown_option` | index:integer! text:string! |  | {} |
| `scroll` | down:boolean numPages:integer! index:integer |  | {} |
| `scroll_to_text` | text:string! |  | {} |
| `get_element_text` | index:integer selector:string canvasOnly:boolean frame:string | index / selector | {} |
| `get_element_html` | index:integer! |  | {} |
| `get_element_value` | index:integer! |  | {} |
| `get_element_attribute` | index:integer! name:string! |  | {} |
| `get_element_listeners` | index:integer selector:string frame:string | index / selector | {} |
| `get_element_count` | selector:string! frame:string |  | {} |
| `get_element_box` | index:integer! |  | {} |
| `is_visible` | index:integer selector:string frame:string | index / selector | {} |
| `is_enabled` | index:integer selector:string frame:string | index / selector | {} |
| `is_checked` | index:integer selector:string frame:string | index / selector | {} |
| `click_element_by_selector` | selector:string! mode:string timeoutMs:integer frame:string nth:integer expectPopup:boolean popupTimeoutMs:integer |  | {} |
| `input_text_by_selector` | selector:string! text:string! mode:string frame:string nth:integer |  | {} |
| `click_element_by_text` | text:string! mode:string exact:boolean timeoutMs:integer expectPopup:boolean popupTimeoutMs:integer |  | {} |
| `click_element_by_role` | role:string! name:string mode:string scope:string frame:string nth:integer exact:boolean timeoutMs:integer expectPopup:boolean popupTimeoutMs:integer |  | {} |
| `input_text_by_label` | label:string! text:string! mode:string |  | {} |
| `clear_text` | index:integer selector:string | index / selector | {} |
| `hover_and_click` | index:integer selector:string hoverDelayMs:integer mode:string expectPopup:boolean popupTimeoutMs:integer | index / selector | {} |
| `get_tabs` |  |  | {} |
| `wait_for_new_tab` | baseline:integer timeoutMs:number timeoutSeconds:number |  | {} |
| `new_tab` | url:string |  | {} |
| `switch_tab` | pageIndex:integer! |  | {} |
| `switch_tab_by_url` | url:string! |  | {} |
| `close_tab` | pageIndex:integer! |  | {} |
| `close_other_tabs` | pageIndex:integer |  | {} |
| `bring_to_front` | pageIndex:integer |  | {} |
| `wait_for_element` | selector:string! frame:string timeoutMs:number timeoutSeconds:number |  | {} |
| `wait_for_text` | text:string! timeoutMs:number timeoutSeconds:number |  | {} |
| `wait_for_url` | url:string! timeoutMs:number timeoutSeconds:number |  | {} |
| `wait_for_load` | state:string timeoutMs:number timeoutSeconds:number |  | {state=[load, domcontentloaded, networkidle]} |
| `wait_for_function` | expression:string! timeoutMs:number timeoutSeconds:number |  | {} |
| `wait_for_idle` | quietMs:integer selector:string text:string timeoutMs:number timeoutSeconds:number |  | {} |
| `wait_for_stable` | selector:string quietMs:integer timeoutMs:number timeoutSeconds:number requireContent:boolean contentGraceMs:integer |  | {} |
| `wait_for_count` | selector:string! min:integer max:integer equals:integer timeoutMs:number timeoutSeconds:number |  | {} |
| `mouse_move` | x:number! y:number! |  | {} |
| `mouse_down` | button:string |  | {} |
| `mouse_up` | button:string |  | {} |
| `mouse_wheel` | deltaY:number! |  | {} |
| `mouse_click` | x:number! y:number! button:string clickCount:integer |  | {} |
| `mouse_click_by_selector` | selector:string! button:string clickCount:integer timeoutMs:integer |  | {} |
| `screenshot` | path:string fullPage:boolean index:integer selector:string clipX:number clipY:number clipWidth:number clipHeight:number inline:boolean timeoutMs:integer force:boolean fallbackToViewport:boolean |  | {} |
| `get_element_screenshot` | index:integer selector:string path:string inline:boolean frame:string timeoutMs:integer force:boolean | index / selector | {} |
| `pdf` | path:string |  | {} |
| `get_cookies` | url:string |  | {} |
| `set_cookie` | name:string! value:string! url:string |  | {} |
| `clear_cookies` |  |  | {} |
| `get_local_storage` | key:string |  | {} |
| `set_local_storage` | key:string! value:string |  | {} |
| `clear_local_storage` |  |  | {} |
| `set_viewport` | width:integer! height:integer! |  | {} |
| `set_geolocation` | latitude:number! longitude:number! |  | {} |
| `set_offline` | offline:boolean |  | {} |
| `set_headers` | headersJson:string! |  | {} |
| `set_credentials` | username:string! password:string! |  | {} |
| `set_media` | colorScheme:string! |  | {} |
| `grant_permissions` | permissions:array! origin:string |  | {} |
| `get_dialog` | consume:boolean |  | {} |
| `clear_dialog` |  |  | {} |
| `get_js_dialog` | consume:boolean |  | {} |
| `clear_js_dialog` |  |  | {} |
| `set_dialog_behavior` | dismiss:boolean |  | {} |
| `get_modals` |  |  | {} |
| `close_modal` | which:string title:string button:string |  | {} |
| `get_console_logs` | level:string limit:integer |  | {} |
| `get_page_diagnostics` | limit:integer |  | {} |
| `clear_console_logs` |  |  | {} |
| `route` | urlPattern:string! action:string body:string status:integer contentType:string |  | {} |
| `unroute` | urlPattern:string |  | {} |
| `get_requests` | filter:string resourceType:string limit:integer since:integer failedOnly:boolean |  | {} |
| `wait_for_response` | urlPattern:string! maxChars:integer lookBackSeconds:integer timeoutMs:number timeoutSeconds:number |  | {} |
| `get_response_body` | filter:string index:integer maxChars:integer requestId:string |  | {} |
| `get_page_snapshot` | includeConsole:boolean includeRequests:boolean requestFilter:string |  | {} |
| `diff_dom_text` | highlight:boolean viewportExpansion:integer |  | {} |
| `get_interactive_map` |  |  | {} |
| `get_form_state` | selector:string includeHidden:boolean max:integer |  | {} |
| `extract_structured_data` | query:string extractLinks:boolean |  | {} |
| `extract_markdown` | selector:string frame:string includeLinks:boolean maxChars:integer nth:integer |  | {} |
| `find_text` | text:string! regex:boolean contextChars:integer maxMatches:integer selector:string frame:string |  | {} |
| `list_tables` | frame:string |  | {} |
| `download_image` | index:integer selector:string frame:string filename:string | index / selector | {} |
| `execute_js` | body:string bodyFile:string vars:object frame:string timeoutMs:integer | body / bodyFile | {} |
| `list_methods` | filter:string details:boolean |  | {} |
| `get_config` |  |  | {} |
| `list_tasks` |  |  | {} |
| `cleanup` | scope:string olderThanHours:integer keepLatest:integer dryRun:boolean |  | {} |
| `list_recipes` |  |  | {} |
| `run_recipe` | name:string! vars:object stopOnError:boolean |  | {} |
| `get_job` | jobId:string! includeResult:boolean |  | {} |
| `cancel_job` | jobId:string! |  | {} |
| `list_jobs` | limit:integer |  | {} |
| `request_human_input` | steps:array prompt:string index:integer selector:string timeoutSeconds:integer expiresAt:integer expiresInSeconds:integer ocr:boolean ocrLanguage:string inline:boolean frame:string |  | {} |
| `ask_user` | questions:array! timeoutSeconds:integer expiresAt:integer expiresInSeconds:integer |  | {} |
| `submit_human_input` | requestId:string! answer:string stepId:string answers:object |  | {} |
| `get_human_input` | requestId:string! timeoutSeconds:integer |  | {} |
| `ocr_image` | path:string index:integer selector:string frame:string language:string |  | {} |
