/**
 * i18n/he.ts — Hebrew strings lifted verbatim from
 * app/src/main/res/values/strings.xml (99 strings).
 *
 * Usage:
 *   import { t }    from '../i18n/he'   // synchronous lookup
 *   import { useT } from '../i18n/he'   // hook (same, future-proof)
 */

const strings = {
  // App
  app_name: 'ApI',

  // Main screen
  system_prompt: 'System Prompt',
  approve: 'אישור',
  cancel: 'ביטול',
  new_chat: 'שיחה חדשה',
  settings: 'הגדרות',
  user_settings: 'הגדרות משתמש',
  api_keys: 'מפתחות API',
  export_chat_history: "יצוא היסטוריית צ'אט",
  share_chat_title: 'שיתוף שיחה',
  save_to_downloads: 'שמירה להורדות',
  chat_export_title: 'שיתוף שיחה',
  edit_button: 'עריכה',
  share_button: 'שיתוף',
  no_content_to_export: 'אין תוכן לייצוא',
  export_success: 'הקובץ נשמר בהצלחה בהורדות',
  export_failed: 'כשל בשמירת הקובץ',
  // Chat export dialog (mirrors ChatImportExportDialogs.kt hardcoded strings)
  close: 'סגור',
  chat_export_header: 'ייצוא שיחה',
  chat_export_editing_active: 'עריכה פעילה',
  chat_export_to_downloads: 'להורדות',
  share_link_copy: 'העתקה',
  share_link_update: 'עדכון',
  share_link_delete: 'מחיקה',
  share_link_copied: 'הקישור הועתק ללוח',
  share_link_updated: 'הקישור עודכן והועתק ללוח',
  share_link_deleted: 'הקישור הוסר בהצלחה',
  share_link_create_error: 'שגיאה ביצירת קישור: ',
  share_link_update_error: 'שגיאה בעדכון קישור: ',
  share_link_delete_error: 'שגיאה במחיקת קישור: ',
  add_api_key: 'הוסף מפתח API',
  take_photo: 'צילום תמונה',
  upload_file: 'העלאת קובץ',
  select_file_option: 'בחר אפשרות',
  remove_file: 'הסר קובץ',
  via: 'via',
  send_message: 'שלח הודעה',
  type_message: 'הקלד הודעה...',
  copy: 'העתק',
  edit: 'ערוך',
  resend: 'שלח שוב',
  delete: 'מחק',

  // Provider names
  provider_default_name: 'DefaultName',
  provider_openai: 'OpenAI',
  provider_poe: 'Poe',
  provider_google: 'Google',
  provider_anthropic: 'Anthropic',
  provider_cohere: 'Cohere',
  provider_openrouter: 'OpenRouter',
  provider_llmstats: 'LLM Stats',
  llmstats_model: 'gpt-5-nano via LLM Stats',

  // Custom Providers
  create_custom_provider: 'צור ספק מותאם אישית',
  edit_custom_provider: 'ערוך ספק מותאם אישית',
  custom_providers: 'ספקים מותאמים אישית',
  define_new_provider: 'הגדר ספק חדש...',
  provider_name: 'שם הספק',
  api_base_url: 'כתובת API בסיסית',
  default_model: 'שם מודל ברירת מחדל',
  auth_header_name: 'שם כותרת אימות',
  auth_header_format: 'פורמט כותרת אימות',
  auth_format_hint: 'השתמש ב-{key} כמקום שמור למפתח API',
  extra_headers: 'כותרות נוספות (אופציונלי)',
  show_advanced: 'הצג הגדרות מתקדמות',
  hide_advanced: 'הסתר הגדרות מתקדמות',
  add_header: 'הוסף כותרת',
  create: 'צור',

  // Permissions and errors
  camera_permission_required: 'נדרשת הרשאת מצלמה',
  storage_permission_required: 'נדרשת הרשאת אחסון',
  error_loading_providers: 'שגיאה בטעינת ספקי השירות',
  error_sending_message: 'שגיאה בשליחת ההודעה',

  // User Settings
  auto_generate_title: 'קבע את שם התצוגה לשיחה חדשה באמצעות AI',
  ai_api_call_note: 'תבוצע קריאת API למודל קטן וזול',
  select_model: 'בחר מודל',
  auto_mode: 'אוטומטי',
  update_title_on_extension: 'עדכן את השם אם השיחה מתארכת',
  after_3_responses: 'לאחר 3 תגובות מהמודל',
  openai_gpt5_nano: 'gpt-5-nano via OpenAI',
  poe_gpt5_nano: 'gpt-5-nano via POE',
  google_gemini_flash_lite: 'gemini-2.5-flash-lite via Google',
  anthropic_claude_haiku: 'claude-3-haiku via Anthropic',
  cohere_command_r7b: 'command-r7b via Cohere',
  openrouter_llama: 'llama-3.1-8b via OpenRouter',

  // Import chat history
  import_chat_history: "ייבוא היסטוריית צ'אט מקובץ...",
  beta: 'beta',
  import_warning: 'שימו לב! בשלב זה שיחות נטענות ללא הקבצים המצורפים',

  // Chat Context Menu
  update_chat_name: 'עדכן שם שיחה',
  update_chat_name_ai: 'עדכן שם שיחה באמצעות AI',
  delete_chat: 'מחק',
  delete_confirmation_title: 'מחיקת שיחה',
  delete_confirmation_message: 'השיחה תימחק ולא תהיה אפשרות לשחזר אותה!',
  enter_new_chat_name: 'הכנס שם חדש לשיחה',
  chat_name: 'שם השיחה',
  confirm: 'אישור',
  rename_success: 'השם עודכן בהצלחה',

  // Multi-message mode
  multi_message_mode: 'מצב הודעות מרובות',
  multi_message_mode_explainer:
    "במצב זה, ניתן לשלוח כמה הודעות ברצף לאותו הצ'אט ורק לאחר לחיצה על כפתור \"השב\" המודל יענה ויתייחס לכולן ביחד.\nלא יחולו חיובים נוספים במצב זה, כי השאילתה נשלחת פעם אחת למודל עם כל ההודעות במקביל",
  reply_now: 'השב',

  // Chat History Screen
  rename: 'שנה שם',
  chat_title: 'כותרת השיחה',
  save: 'שמור',

  // Remote Sync
  remote_sync_title: 'סנכרון מרחוק (Remote Sync)',
  remote_sync_status_enabled: 'סנכרון פעיל',
  remote_sync_status_off: 'כבוי',
  remote_sync_server_url: 'כתובת שרת',
  remote_sync_auth_token: 'טוקן אימות',
  remote_sync_api_keys: 'סנכרן גם מפתחות API',
  remote_sync_api_keys_warning: 'מפתחות ה-API שלך יועלו לשרת שלך (מוצפנים במנוחה).',
  remote_sync_now: 'סנכרן עכשיו',
  remote_sync_now_done: 'נשלח!',
  remote_sync_test: 'בדוק חיבור',
  remote_sync_test_ok: 'תקין ✓',
  remote_sync_test_fail: 'נכשל ✗',

  // Login / auth (web-only additions)
  password: 'סיסמה',
  sign_in: 'כניסה',
  sign_in_loading: 'מתחבר...',
  invalid_password: 'סיסמה שגויה. אנא נסה שוב.',
  sign_out: 'התנתקות',

  // Google Sign-In (Step 6)
  sign_in_with_google: 'כניסה עם Google',
  google_auth_error_invalid_state: 'שגיאת אבטחה. אנא נסה להתחבר שוב.',
  google_auth_error_token_exchange_failed: 'כשל בהתחברות ל-Google. אנא נסה שוב.',
  google_auth_error_token_invalid: 'הטוקן אינו תקין. אנא נסה שוב.',
  google_auth_error_not_allowed: 'כתובת האימייל שלך אינה מורשית לגשת לאפליקציה.',
  google_auth_error_sync_unavailable: 'שירות הסנכרון אינו זמין. אנא פנה למנהל המערכת.',
  account_email_label: 'אימייל',
  logout: 'התנתק',

  // Chat History screen — search (R1)
  search_placeholder: 'חיפוש בשיחות...',
  search_enter_hint: 'הזן מילות חיפוש למציאת שיחות',
  search_content_hint: 'החיפוש כולל כותרות שיחות, תוכן הודעות ושמות קבצים',
  no_results_found: 'לא נמצאו תוצאות',
  try_different_search: 'נסה מילות חיפוש אחרות',
  no_chats_yet: 'אין עדיין שיחות',
  start_new_chat_hint: 'לחץ על + כדי להתחיל שיחה חדשה',
  close_search: 'סגור חיפוש',

  // Chat context menu extras (R1)
  share_chat: 'שיתוף שיחה...',
  add_to_group: 'הוסף לקבוצה',
  remove_from_group: 'הסר מקבוצה',
  create_new_group: 'צור קבוצה חדשה',

  // Group context menu (R1)
  rename_group: 'שנה שם קבוצה',
  make_project: 'הפוך לפרויקט',
  delete_group_scatter: 'מחק קבוצה ופזר שיחות',

  // Group dialogs (R1)
  group_name_label: 'שם הקבוצה',
  new_group_name_label: 'שם הקבוצה החדש',
  create_group_title: 'צור קבוצה חדשה',
  delete_group_title: 'מחיקת קבוצה',
  delete_group_message: 'כל השיחות בקבוצה זו יפוזרו ויתבטלו מהקבוצה.',

  // Chat screen chrome (R2)
  search_in_conversation: 'חפש בשיחה...',
  exit_search_mode: 'יציאה מחיפוש',
  thinking_budget: 'עוצמת חשיבה',
  thinking_budget_title: 'תקציב חשיבה',
  thinking_none: 'ללא חשיבה',
  thinking_low: 'נמוך',
  thinking_medium: 'בינוני',
  thinking_high: 'גבוה',
  thinking_max: 'מרבי',
  thinking_off: 'כבוי',
  temperature_label: 'טמפרטורה',
  temperature_default: 'ברירת מחדל',
  temperature_reset: 'איפוס לברירת מחדל',
  tools_label: 'כלים',
  text_direction_label: 'כיוון טקסט',
  all_tools: 'כל הכלים',
  no_tools_available: 'אין כלים זמינים',
  quick_settings_expand: 'פתח הגדרות מהירות',
  quick_settings_collapse: 'סגור הגדרות מהירות',
  model_selector_title: 'בחירת מודל',
  custom_model_enter: 'או הכנס שם מדויק:',
  custom_model_placeholder: 'הכנס שם מדויק...',
  no_providers_available: 'אין ספקים זמינים. הוסף מפתח API בהגדרות.',
  starred_tab: 'מועדפים',
  quick_access_title: 'גישה מהירה',
  starred_hint: 'כאן יופיעו המודלים ששמרת בכוכב',
  add_to_starred: 'הוסף למועדפים',
  remove_from_starred: 'הסר ממועדפים',
  sort_by_price: 'לפי מחיר',
  sort_newest_first: 'מהחדש לישן',
  sort_recommended: 'מומלץ',
  use_custom_model: 'שימוש במודל:',

  // API Keys screen (R4)
  delete_api_key_title: 'מחיקת מפתח API',
  delete_api_key_body: 'פעולה זו תמחק את המפתח ולא ניתן יהיה לשחזר אותו!',
  key_status_active: 'פעיל',
  key_status_inactive: 'לא פעיל',
  get_api_key: 'קבל מפתח API',
  reorder_up: 'העלה',
  reorder_down: 'הורד',
  full_custom_providers: 'ספקים מותאמים אישית (מלאים)',
  define_full_provider: 'הגדר ספק מלא',
  edit_full_provider: 'ערוך ספק מלא',
  openai_compatible_label: 'ספק תומך פורמט OpenAI',
  streaming_confirmed_label: 'יש לאשר שהבקשה מוגדרת במצב סטרימינג מופעל',
  body_template_section: 'מבנה גוף הבקשה - חלק קבוע',
  request_tab: 'בקשה',
  response_tab: 'תגובה',
  model_tab: 'מודל',
  provider_api_key: 'מפתח API (אופציונלי)',
  model_names_label: 'שמות מודלים (שורה לכל מודל)',
  request_template_label: 'תבנית בקשה (JSON, אופציונלי)',
  response_mapping_label: 'מיפוי תגובה (JSON, אופציונלי)',
  select_provider: 'בחר ספק',
  custom_name_optional: 'שם מותאם (אופציונלי)',
  custom_name_placeholder: 'למשל: מפתח עבודה, מפתח אישי',
  delete_provider_title: 'מחיקת ספק',
  delete_provider_body: 'הספק יימחק ולא ניתן יהיה לשחזר אותו',
  invalid_json: 'JSON לא תקין',

  // Settings screen (R5)
  advanced_settings: 'הגדרות מתקדמות',
  current_user_label: 'משתמש נוכחי',

  // Child lock (R5)
  child_lock_mode: 'מצב נעילת ילדים',
  child_lock_hours_hint: 'הגדר שעות בהן האפליקציה אינה פעילה',
  child_lock_setup_title: 'הגדרת נעילת ילדים',
  child_lock_setup_warning: 'להפעלת מצב נעילת ילדים, הקלידו סיסמה. זכרו את הסיסמה! לא תהיה אפשרות לשחרר את נעילת הילדים ללא הסיסמה',
  child_lock_disable_title: 'שחרור נעילת ילדים',
  child_lock_disable_body: 'הקלד את הסיסמה כדי לשחרר את נעילת הילדים:',
  child_lock_password_label: 'סיסמה',
  child_lock_password_placeholder: 'הקלד סיסמה',
  child_lock_from: 'משעה:',
  child_lock_until: 'עד שעה:',
  child_lock_locked_title: 'האפליקציה נעולה',
  child_lock_until_time_prefix: 'האפליקציה נעולה עד לשעה',
  child_lock_available_msg: 'האפליקציה תהיה זמינה מחוץ לשעות הנעילה',
  child_lock_wrong_password: 'סיסמה שגויה',
  child_lock_enter_password_to_unlock: 'הזן סיסמה לביטול הנעילה',
  child_lock_unlock: 'בטל נעילה',

  // Time picker (R5)
  time_picker_title: 'בחר שעה',
  time_picker_quick_mode: 'בחירה מהירה',
  time_picker_precise_mode: 'בחירה מדויקת',
  time_picker_hours: 'שעות',
  time_picker_minutes: 'דקות',
  time_picker_common_hours: 'שעות נפוצות:',
  time_picker_minutes_label: 'דקות:',

  // Chat body + input (R3)
  thoughts_seconds: 'שניות',
  editing_message: 'עריכת הודעה',
  regenerate: 'צור מחדש',
  delete_from_here: 'מחק מכאן',
  empty_chat_message: 'שלח הודעה ראשונה...',
  tool_executing: 'מבצע...',
  tool_completed: 'הושלם',
  tool_failed: 'נכשל',
  stop_streaming: 'עצור',
  confirm_edit: 'אישור עריכה',
  confirm_edit_and_resend: 'אישור ושלח',
  attach_file: 'צרף קובץ',
  web_search_toggle: 'חיפוש ברשת',
  copy_code: 'העתק',
  copied: 'הועתק!',
  previous_variant: 'ענף קודם',
  next_variant: 'ענף הבא',
  scroll_to_top: 'גלול למעלה',
  scroll_to_bottom: 'גלול למטה',

  // ── R6: Group/Project screen ────────────────────────────────────────────────
  group_project_mode: 'פרויקט',
  group_instructions: 'הוראות',
  group_no_instructions: 'לא הוגדרו הוראות',
  group_files: 'קבצים',
  group_add_files_placeholder: 'הוסיפו קבצים...',
  group_add_file: 'הוסף קובץ',
  group_no_chats: 'אין עדיין שיחות בקבוצה זו',
  group_add_chat_hint: 'לחץ לחיצה ארוכה על שיחה כדי להוסיפה לקבוצה',
  group_not_found: 'הקבוצה לא נמצאה',
  group_new_chat: 'שיחה חדשה בקבוצה',
  group_save_prompt: 'שמור הוראות',

  // ── R6: Skills screen ───────────────────────────────────────────────────────
  skills_title: 'סקילים',
  skills_empty_title: 'אין סקילים מותקנים',
  skills_empty_hint: 'סקילים מרחיבים את יכולות המודל עם ידע ומומחיות ייעודיים. ייבא סקיל קיים או צור חדש.',
  skill_import_from_text: 'ייבוא מטקסט',
  skill_import_text_title: 'ייבוא סקיל מטקסט',
  skill_import_text_hint: 'הדבק את תוכן ה-SKILL.md כולל ה-frontmatter:',
  skill_create_new: 'יצירת סקיל חדש',
  skill_create_name_label: 'שם (אנגלית, מקפים)',
  skill_create_desc_label: 'תיאור',
  skill_delete_title: 'מחיקת סקיל',
  skill_delete_confirm: 'מחיקה',
  skill_files_count: 'קבצים',

  // ── R6: Skill editor ────────────────────────────────────────────────────────
  skill_editor_add_file: 'הוספת קובץ',
  skill_editor_saved: 'נשמר ✓',
  skill_editor_save_error: 'שגיאה בשמירה',
  skill_editor_not_found: 'הסקיל לא נמצא',
  skill_editor_new_file_title: 'הוספת קובץ חדש',
  skill_editor_file_name: 'שם קובץ',

  // ── R6: Integrations screen ─────────────────────────────────────────────────
  integrations_title: 'כלים חיצוניים',
  integrations_tools_section: 'כלים זמינים',
  integration_datetime: 'תאריך ושעה',
  integration_datetime_desc: 'מאפשר למודל לקבל את התאריך והשעה הנוכחיים במכשיר',
  integration_group_conv: 'שיחות בקבוצה',
  integration_group_conv_desc: 'אפשר למודל לראות שיחות אחרות באותה הקבוצה',
  integration_python: 'מפרש Python',
  integration_python_desc: 'הרצת קוד Python עם pandas, numpy, matplotlib לניתוח נתונים',
  integration_github_title: 'חיבור ל-GitHub',
  integration_github_desc: 'אפשר למודל לעבוד עם קוד ב-GitHub',
  integration_github_connected_as: 'מחובר כ-',
  integration_github_tools_title: 'כלי GitHub כלולים:',
  integration_google_title: 'חיבור ל-Google Workspace',
  integration_google_desc: 'גישה ל-Gmail, Calendar ו-Drive',
  integration_google_connected_as: 'מחובר כ-',
  integration_google_services_title: 'שירותים מופעלים:',
  integration_gmail_desc: 'קריאה, שליחה וחיפוש אימיילים',
  integration_calendar_desc: 'רשימת אירועים, יצירה וצפייה',
  integration_drive_desc: 'רשימה, קריאה, העלאה ומחיקה',
  integration_connect: 'התחבר',
  integration_disconnect: 'נתק',

  // ── R6: Logs screen ─────────────────────────────────────────────────────────
  logs_title: 'לוגים',
  logs_clear: 'נקה',
  logs_no_logs: 'No logs yet',

  // ── R6: Welcome screen ──────────────────────────────────────────────────────
  welcome_title: 'ברוכים הבאים!',
  welcome_subtitle: 'כדי להתחיל, תצטרכו מפתח API. לחצו על שם הספק כדי לקבל מפתח.',
  welcome_free_section: 'מומלץ להתחיל כאן - ספקים אלו ניתן לקבל מפתח ניסיון ללא תשלום',
  welcome_paid_section: 'ספקים נוספים - תשלום מינימלי 5$ לקבלת מפתח פעיל',
  welcome_have_api_key: 'יש לי מפתח API >',
  welcome_skip_screen: 'אל תציגו מסך זה שוב',
} as const

export type StringKey = keyof typeof strings

/**
 * Synchronous string lookup.
 * @example t('new_chat') // => 'שיחה חדשה'
 */
export function t(key: StringKey): string {
  return strings[key]
}

/**
 * React hook that returns the same `t()` function.
 * Keeps the API open for a future dynamic locale switch without changing call sites.
 */
export function useT(): (key: StringKey) => string {
  return t
}

export default strings
