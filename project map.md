### Project Guide

This document provides a high-level overview of the Android chat application's structure and functionality. It is designed to help developers quickly locate relevant code for understanding or modifying features.

#### 1. Project Overview & Structure

The application is a multi-provider LLM chat client built with Kotlin and Jetpack Compose. Its architecture follows the MVVM (Model-View-ViewModel) pattern.

*   `app/src/main/java/com/example/ApI/data/`: **Data Layer**. Handles all data operations.
    *   `model/`: Contains Kotlin data classes (`Chat`, `Message`, `Provider`, `ApiKey`) that define the application's data structures. These are serialized to/from JSON.
    *   `network/`: Contains `ApiService.kt`, responsible for making all HTTP requests to external LLM APIs.
    *   `repository/`: Contains `DataRepository.kt`, the single source of truth. It manages local JSON storage, orchestrates network calls, and contains core business logic.
*   `app/src/main/java/com/example/ApI/ui/`: **UI Layer**. Contains all UI-related code.
    *   `screen/`: Each file represents a major screen in the app (e.g., `ChatScreen.kt`, `ChatHistoryScreen.kt`).
    *   `components/`: Reusable Jetpack Compose components, primarily dialogs (`Dialogs.kt`, `FileSelectionDialog.kt`).
    *   `theme/`: Defines the app's visual style, including colors (`Color.kt`) and typography (`Type.kt`).
    *   `ChatViewModel.kt`: The central ViewModel that manages UI state and handles user interactions for all screens.
*   `app/src/main/assets/`: Contains `providers.json`, a critical configuration file defining API endpoints and models.
*   `app/src/main/res/`: Standard Android resources, including UI strings (`values/strings.xml`).

#### 2. File Contents Index

This index details the purpose and key contents of each important file.

##### Core Logic & State Management

*   **`ui/ChatViewModel.kt`**
    *   **Purpose**: The central brain of the UI. It manages the entire application state (`ChatUiState`) and handles all user actions.
    *   **Key Contents**:
        *   `uiState`: A `StateFlow` holding the complete UI state, including the current chat, message list, selected provider, and dialog visibility.
        *   `sendMessage()`: The primary function for sending a user's message. It updates the state, handles file uploads via the repository, and initiates the streaming API call.
        *   `sendBufferedBatch()`: Sends multiple user messages at once when "Multi-Message Mode" is enabled.
        *   `selectChat()`, `createNewChat()`: Functions to manage chat sessions.
        *   `selectProvider()`, `selectModel()`: Handle provider and model selection.
        *   `startEditingMessage()`, `finishEditingMessage()`, `resendFromMessage()`: Logic for editing, confirming, and resending messages.
        *   `loadInitialData()`: Loads user settings, API keys, and chat history on startup.
        *   Dialog management functions (`show...Dialog()`, `hide...Dialog()`).

*   **`data/repository/DataRepository.kt`**
    *   **Purpose**: The single source of truth for all application data. It abstracts data sources (local JSON files and network).
    *   **Key Contents**:
        *   `loadChatHistory()`, `saveChatHistory()`: Reads from and writes to `chat_history_{user}.json`.
        *   `loadApiKeys()`, `saveApiKeys()`: Manages `api_keys_{user}.json`.
        *   `loadAppSettings()`, `saveAppSettings()`: Manages `app_settings.json`.
        *   `sendMessageStreaming()`: Prepares messages for the API call. It ensures files are uploaded for the correct provider (`ensureFilesUploadedForProvider`) before calling `ApiService`.
        *   `uploadFile()`: A wrapper that calls provider-specific upload methods (`uploadFileToOpenAI`, `uploadFileToGoogle`, etc.).
        *   `generateConversationTitle()`: Constructs a prompt and calls an LLM to generate a title for a chat.

*   **`data/network/ApiService.kt`**
    *   **Purpose**: Handles all direct network communication with external LLM APIs (OpenAI, Poe, Google).
    *   **Key Contents**:
        *   `sendMessageStreaming()`: The main entry point. It delegates to provider-specific methods.
        *   `sendOpenAIMessageStreaming()`, `sendPoeMessageStreaming()`, `sendGoogleMessageStreaming()`: Each of these methods constructs the unique JSON request body and headers required by its respective provider, initiates the HTTP request, and parses the streaming (SSE) response.
        *   `StreamingCallback`: An interface used to stream partial responses, completion signals, or errors back to the `ChatViewModel`.

##### Data Models & Configuration

*   **`data/model/ChatHistory.kt` & `ApiKey.kt`**
    *   **Purpose**: Defines the schemas for all locally stored JSON data.
    *   **Key Contents**:
        *   `UserChatHistory`: The root object for a user's data.
        *   `Chat`: Represents a single conversation thread with its messages and metadata.
        *   `Message`: Represents a single user or assistant message, including text and attachments.
        *   `ChatGroup`: Defines a folder-like structure for organizing chats.
        *   `ApiKey`: Represents a single API key for a provider.
        *   `AppSettings`: Stores global settings like the current user, selected model, and feature toggles.

*   **`assets/providers.json` & `data/model/Provider.kt`**
    *   **Purpose**: `providers.json` is a configuration file defining how to interact with each LLM provider. `Provider.kt` contains the data classes used to parse this JSON.
    *   **Key Contents**:
        *   Defines the `base_url`, `request_type`, `headers`, and `body` structure for each provider's API.
        *   Lists the available `models` for each provider and their capabilities (e.g., `web_search`).
        *   Specifies the structure for file uploads (`upload_files_request`).

##### UI Screens (Composables)

*   **`MainActivity.kt`**
    *   **Purpose**: The app's single entry point.
    *   **Key Contents**:
        *   Sets up the edge-to-edge display and the main theme (`LLMApiTheme`).
        *   `LLMChatApp`: The root composable that acts as a navigator, displaying the correct screen based on `viewModel.currentScreen`.

*   **`ui/screen/ChatScreen.kt`**
    *   **Purpose**: Displays the main conversation view.
    *   **Key Contents**:
        *   `ChatScreen`: The main composable that builds the top bar, message list, and input area.
        *   `MessageBubble`: A composable that renders a single chat message, handling different styles for user and assistant. It also includes the logic for the long-press context menu (copy, edit, delete).
        *   `FilePreview`: Shows a thumbnail for attached files in the input area.
        *   The bottom input bar with text field, file attachment button, and send/edit buttons.

*   **`ui/screen/ChatHistoryScreen.kt`**
    *   **Purpose**: The main screen that lists all chats and groups.
    *   **Key Contents**:
        *   `ChatHistoryScreen`: The main composable that builds the top bar, floating action button, and the list of chats.
        *   `ChatHistoryItem` & `GroupItem`: Composables that render a single row for a chat or a group, respectively.
        *   `ChatContextMenu` & `GroupContextMenu`: The pop-up menus that appear on long-press to rename, delete, or manage group associations.

*   **`ui/screen/ApiKeysScreen.kt` & `UsernameScreen.kt` (Settings)**
    *   **Purpose**: These screens manage app settings.
    *   **Key Contents**:
        *   `ApiKeysScreen`: UI for adding, viewing, toggling, and deleting API keys for different providers.
        *   `UserSettingsScreen`: UI for changing global settings, such as enabling automatic title generation or multi-message mode.

*   **`ui/screen/GroupScreen.kt`**
    *   **Purpose**: Displays the contents of a single group/project, including its chats and associated files.
    *   **Key Contents**:
        *   `GroupScreen`: The main composable for the group view.
        *   `ProjectArea`: A special section that appears if a group is marked as a "project," allowing for a shared system prompt and file attachments for all chats within it.