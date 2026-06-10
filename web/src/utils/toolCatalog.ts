/**
 * utils/toolCatalog.ts — id → display-name/group mapping for integration tools.
 *
 * Mirrors the tool `name` properties registered in the shared ToolRegistry
 * (DateTimeTool, PythonInterpreterTool, github/*, google/*) and
 * getIntegrationTitle() from ToolToggleDropdown.kt, so the chat-screen tools
 * dropdown shows the same labels and grouping as Android.
 */

export const GROUP_CONVERSATIONS_TOOL_ID = 'get_current_group_conversations'

const TOOL_NAMES: Record<string, string> = {
  get_date_time: 'Get Date and Time',
  python_interpreter: 'Python Code Interpreter',
  [GROUP_CONVERSATIONS_TOOL_ID]: 'Other conversations in group',
  // GitHub
  github_read_file: 'Read GitHub File',
  github_write_file: 'Write GitHub File',
  github_list_files: 'List GitHub Files',
  github_search_code: 'Search GitHub Code',
  github_create_branch: 'Create GitHub Branch',
  github_create_pr: 'Create GitHub Pull Request',
  github_get_repo_info: 'Get GitHub Repository Info',
  github_list_repositories: 'List GitHub Repositories',
  // Gmail
  gmail_send_email: 'Send Gmail Email',
  gmail_read_email: 'Read Gmail Email',
  gmail_search_emails: 'Search Gmail Emails',
  // Calendar
  calendar_create_event: 'Create Calendar Event',
  calendar_list_events: 'List Calendar Events',
  calendar_get_event: 'Get Calendar Event',
  // Drive
  drive_read_file: 'Read Drive File',
  drive_upload_file: 'Upload Drive File',
  drive_list_files: 'List Drive Files',
  drive_search_files: 'Search Drive Files',
  drive_create_folder: 'Create Drive Folder',
  drive_delete_file: 'Delete Drive File',
}

export function getToolName(toolId: string): string {
  return TOOL_NAMES[toolId] ?? toolId
}

/** Mirror of ToolToggleDropdown.kt getIntegrationTitle */
export function getIntegrationTitle(toolId: string): string {
  if (toolId.startsWith('github_')) return 'GitHub'
  if (toolId.startsWith('gmail_')) return 'Gmail'
  if (toolId.startsWith('calendar_')) return 'Calendar'
  if (toolId.startsWith('drive_')) return 'Drive'
  if (
    toolId === 'get_date_time' ||
    toolId === GROUP_CONVERSATIONS_TOOL_ID ||
    toolId === 'python_interpreter'
  ) return 'Built-in Tools'
  return 'Other'
}
