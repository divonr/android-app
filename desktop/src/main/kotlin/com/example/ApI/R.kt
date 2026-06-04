package com.example.ApI

import androidx.compose.runtime.Composable

/**
 * Desktop shim for Android's R class.
 * Provides string resource IDs as constants with their actual string values.
 */
object R {
    object string {
        const val app_name = 0
        const val system_prompt = 1
        const val approve = 2
        const val cancel = 3
        const val new_chat = 4
        const val settings = 5
        const val user_settings = 6
        const val api_keys = 7
        const val export_chat_history = 8
        const val share_chat_title = 9
        const val save_to_downloads = 10
        const val chat_export_title = 11
        const val edit_button = 12
        const val share_button = 13
        const val no_content_to_export = 14
        const val export_success = 15
        const val export_failed = 16
        const val add_api_key = 17
        const val take_photo = 18
        const val upload_file = 19
        const val select_file_option = 20
        const val remove_file = 21
        const val via = 22
        const val send_message = 23
        const val type_message = 24
        const val copy = 25
        const val edit = 26
        const val resend = 27
        const val delete = 28
        const val provider_default_name = 29
        const val provider_openai = 30
        const val provider_poe = 31
        const val provider_google = 32
        const val provider_anthropic = 33
        const val provider_cohere = 34
        const val provider_openrouter = 35
        const val provider_llmstats = 36
        const val llmstats_model = 37
        const val create_custom_provider = 38
        const val edit_custom_provider = 39
        const val custom_providers = 40
        const val define_new_provider = 41
        const val provider_name = 42
        const val api_base_url = 43
        const val default_model = 44
        const val auth_header_name = 45
        const val auth_header_format = 46
        const val auth_format_hint = 47
        const val extra_headers = 48
        const val show_advanced = 49
        const val hide_advanced = 50
        const val add_header = 51
        const val create = 52
        const val camera_permission_required = 53
        const val storage_permission_required = 54
        const val error_loading_providers = 55
        const val error_sending_message = 56
        const val auto_generate_title = 57
        const val ai_api_call_note = 58
        const val select_model = 59
        const val auto_mode = 60
        const val update_title_on_extension = 61
        const val after_3_responses = 62
        const val openai_gpt5_nano = 63
        const val poe_gpt5_nano = 64
        const val google_gemini_flash_lite = 65
        const val anthropic_claude_haiku = 66
        const val cohere_command_r7b = 67
        const val openrouter_llama = 68
        const val import_chat_history = 69
        const val beta = 70
        const val import_warning = 71
        const val update_chat_name = 72
        const val update_chat_name_ai = 73
        const val delete_chat = 74
        const val delete_confirmation_title = 75
        const val delete_confirmation_message = 76
        const val enter_new_chat_name = 77
        const val chat_name = 78
        const val confirm = 79
        const val rename_success = 80
        const val multi_message_mode = 81
        const val multi_message_mode_explainer = 82
        const val reply_now = 83
        const val rename = 84
        const val chat_title = 85
        const val save = 86
        const val remote_sync_title = 87
        const val remote_sync_status_enabled = 88
        const val remote_sync_status_off = 89
        const val remote_sync_server_url = 90
        const val remote_sync_auth_token = 91
        const val remote_sync_api_keys = 92
        const val remote_sync_api_keys_warning = 93
        const val remote_sync_now = 94
        const val remote_sync_now_done = 95
        const val remote_sync_test = 96
        const val remote_sync_test_ok = 97
        const val remote_sync_test_fail = 98
    }
}

private val stringValues = mapOf(
    R.string.app_name to "ApI",
    R.string.system_prompt to "System Prompt",
    R.string.approve to "OK",
    R.string.cancel to "Cancel",
    R.string.new_chat to "New chat",
    R.string.settings to "Settings",
    R.string.user_settings to "User Settings",
    R.string.api_keys to "API Keys",
    R.string.export_chat_history to "Export Chat History",
    R.string.share_chat_title to "Share Chat",
    R.string.save_to_downloads to "Save to Downloads",
    R.string.chat_export_title to "Share Chat",
    R.string.edit_button to "Edit",
    R.string.share_button to "Share",
    R.string.no_content_to_export to "No content to export",
    R.string.export_success to "File saved successfully",
    R.string.export_failed to "Failed to save file",
    R.string.add_api_key to "Add API Key",
    R.string.take_photo to "Take Photo",
    R.string.upload_file to "Upload File",
    R.string.select_file_option to "Select Option",
    R.string.remove_file to "Remove File",
    R.string.via to "via",
    R.string.send_message to "Send Message",
    R.string.type_message to "Type a message...",
    R.string.copy to "Copy",
    R.string.edit to "Edit",
    R.string.resend to "Resend",
    R.string.delete to "Delete",
    R.string.provider_default_name to "DefaultName",
    R.string.provider_openai to "OpenAI",
    R.string.provider_poe to "Poe",
    R.string.provider_google to "Google",
    R.string.provider_anthropic to "Anthropic",
    R.string.provider_cohere to "Cohere",
    R.string.provider_openrouter to "OpenRouter",
    R.string.provider_llmstats to "LLM Stats",
    R.string.llmstats_model to "gpt-5-nano via LLM Stats",
    R.string.create_custom_provider to "Create Custom Provider",
    R.string.edit_custom_provider to "Edit Custom Provider",
    R.string.custom_providers to "Custom Providers",
    R.string.define_new_provider to "Define new provider...",
    R.string.provider_name to "Provider Name",
    R.string.api_base_url to "Base API URL",
    R.string.default_model to "Default Model Name",
    R.string.auth_header_name to "Auth Header Name",
    R.string.auth_header_format to "Auth Header Format",
    R.string.auth_format_hint to "Use {key} as placeholder for API key",
    R.string.extra_headers to "Extra Headers (optional)",
    R.string.show_advanced to "Show Advanced Settings",
    R.string.hide_advanced to "Hide Advanced Settings",
    R.string.add_header to "Add Header",
    R.string.create to "Create",
    R.string.camera_permission_required to "Camera permission required",
    R.string.storage_permission_required to "Storage permission required",
    R.string.error_loading_providers to "Error loading providers",
    R.string.error_sending_message to "Error sending message",
    R.string.auto_generate_title to "Auto-generate chat title using AI",
    R.string.ai_api_call_note to "An API call will be made to a small model",
    R.string.select_model to "Select Model",
    R.string.auto_mode to "Auto",
    R.string.update_title_on_extension to "Update title when chat extends",
    R.string.after_3_responses to "After 3 model responses",
    R.string.openai_gpt5_nano to "gpt-5-nano via OpenAI",
    R.string.poe_gpt5_nano to "gpt-5-nano via POE",
    R.string.google_gemini_flash_lite to "gemini-2.5-flash-lite via Google",
    R.string.anthropic_claude_haiku to "claude-3-haiku via Anthropic",
    R.string.cohere_command_r7b to "command-r7b via Cohere",
    R.string.openrouter_llama to "llama-3.1-8b via OpenRouter",
    R.string.import_chat_history to "Import chat history from file...",
    R.string.beta to "beta",
    R.string.import_warning to "Note: attachments are not imported at this stage",
    R.string.update_chat_name to "Update chat name",
    R.string.update_chat_name_ai to "Update chat name with AI",
    R.string.delete_chat to "Delete",
    R.string.delete_confirmation_title to "Delete Chat",
    R.string.delete_confirmation_message to "The chat will be deleted and cannot be recovered!",
    R.string.enter_new_chat_name to "Enter new chat name",
    R.string.chat_name to "Chat Name",
    R.string.confirm to "Confirm",
    R.string.rename_success to "Name updated successfully",
    R.string.multi_message_mode to "Multi-message mode",
    R.string.multi_message_mode_explainer to "In this mode, you can send multiple messages in sequence to the same chat, and only after clicking the Reply button will the model respond to all of them together.",
    R.string.reply_now to "Reply",
    R.string.rename to "Rename",
    R.string.chat_title to "Chat Title",
    R.string.save to "Save",
    R.string.remote_sync_title to "Remote Sync",
    R.string.remote_sync_status_enabled to "Enabled — syncing in background",
    R.string.remote_sync_status_off to "Disabled",
    R.string.remote_sync_server_url to "Server URL",
    R.string.remote_sync_auth_token to "Auth Token",
    R.string.remote_sync_api_keys to "Also sync API keys",
    R.string.remote_sync_api_keys_warning to "Sends your API keys to the sync server. Only enable if you trust and control the server.",
    R.string.remote_sync_now to "Sync now",
    R.string.remote_sync_now_done to "Synced!",
    R.string.remote_sync_test to "Test connection",
    R.string.remote_sync_test_ok to "OK ✓",
    R.string.remote_sync_test_fail to "Failed ✗"
)

/** Desktop replacement for Android's stringResource(). */
@Composable
fun stringResource(id: Int): String = stringValues[id] ?: "???"

/** Desktop replacement for Android's stringResource() with format args. */
@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String {
    val template = stringValues[id] ?: "???"
    return try { String.format(template, *formatArgs) } catch (e: Exception) { template }
}

/** Non-composable version for use outside composables. */
fun getString(id: Int): String = stringValues[id] ?: "???"
