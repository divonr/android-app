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
