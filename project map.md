# Android LLM Chat App - Project Guide

This guide provides a concise overview of the project structure and file contents, designed to be easily understood by LLMs and new developers. The app is a multi-provider LLM chat client built with Jetpack Compose, following an MVVM architecture.

## 1. Project Structure Overview

The project's essential files are located primarily within the `app/src/main/` directory and the root-level Gradle scripts.

-   **Configuration & Build:** Gradle files (`/build.gradle.kts`, `/app/build.gradle.kts`, `gradle/libs.versions.toml`) and the app manifest.
-   **Core Logic (`app/src/main/java/com/example/ApI/`)**:
    -   `/data`: Handles all data operations, including models, networking, and local storage.
    -   `/tools`: Implements the function-calling (tools) capability for the LLM.
    -   `/ui`: Contains all Jetpack Compose UI elements, divided into screens, components, view models, and themes.
    -   `MainActivity.kt`: The single activity and entry point for the UI.
-   **Assets & Resources (`app/src/main/`)**:
    -   `/assets`: Contains static data files like `providers.json`.
    -   `/res`: Standard Android resources, including UI strings.

---

## 2. File Contents Index

This index details the purpose of each key file. To modify a feature, find the relevant file here.

### Configuration & Build

-   `app/build.gradle.kts`
    -   Defines app-specific configurations (SDK versions, application ID).
    -   Lists all dependencies for Jetpack Compose, Networking (Retrofit, OkHttp), Coroutines, Serialization, CameraX, and Security.

-   `gradle/libs.versions.toml`
    -   A centralized catalog for managing all project dependency versions.

-   `app/src/main/AndroidManifest.xml`
    -   Declares necessary permissions: `INTERNET`, `CAMERA`, `READ/WRITE_STORAGE`.
    -   Defines **`MainActivity`** as the main entry point.
    -   Configures intent filters to handle shared files (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
    -   Sets up a `FileProvider` for secure file sharing.

### Data Layer

-   `app/src/main/assets/providers.json`
    -   **Crucial instruction file.** Defines the API specifications for each LLM provider (OpenAI, Poe, Google).
    -   Contains base URLs, request/response formats, models, and file upload specifications. **This file should not be modified unless an API changes.**

-   `app/src/main/java/com/example/ApI/data/model/`
    -   **`ApiKey.kt`**: Defines `ApiKey`, `AppSettings`, `TitleGenerationSettings`, and `ChildLockSettings` data classes. These models represent the core user settings.
    -   **`ChatHistory.kt`**: Defines the main data structures for conversations: `UserChatHistory`, `Chat`, `Message`, `Attachment`, and `ChatGroup`.
    -   **`Provider.kt`**: Defines data classes (`Provider`, `Model`, `ApiRequest`, etc.) that map directly to the structure of `providers.json`.
    -   **`ModelSerializer.kt`**: A custom serializer for handling the flexible `Model` structure in `providers.json` (can be a simple string or a complex object).
    -   **`UiState.kt`**: Defines all state holder data classes for the UI, including `ChatUiState`, `Screen` navigation states, and states for context menus and dialogs.

-   `app/src/main/java/com/example/ApI/data/repository/DataRepository.kt`
    -   **Single source of truth for all app data.**
    -   **Chat Management**: Loads, saves, creates, and deletes chats and groups from local JSON files (`chat_history_[user].json`).
    -   **API Key Management**: Loads, saves, and reorders API keys from `api_keys_[user].json`.
    -   **Settings**: Loads and saves `AppSettings` from `app_settings.json`.
    -   **File Handling**: Manages local file storage for attachments and handles file uploads to the appropriate provider API.
    -   **Search**: Implements logic for searching through chat titles, content, and filenames.
    -   **Title Generation**: Contains the logic to call an LLM API to generate a title for a conversation.

-   `app/src/main/java/com/example/ApI/data/network/ApiService.kt`
    -   **Handles all network communication.**
    -   Contains functions (`sendMessage`, `sendMessageStreaming`) that build and execute HTTP requests to the LLM providers based on the specifications in `providers.json`.
    -   Implements logic to parse both standard and streaming (Server-Sent Events) responses.
    -   Handles provider-specific request body construction and response parsing.

-   `app/src/main/java/com/example/ApI/data/PasswordEncryption.kt`
    -   Provides utility functions for encrypting and decrypting the child lock password.
    -   Uses `EncryptedSharedPreferences` for secure storage.

### Tools / Function Calling

-   `app/src/main/java/com/example/ApI/tools/Tool.kt`
    -   Defines the core interfaces and data classes for the function-calling feature: `Tool`, `ToolExecutionResult`, `ToolSpecification`, `ToolCall`, and `ToolCallInfo`.

-   `app/src/main/java/com/example/ApI/tools/ToolRegistry.kt`
    -   A singleton that holds all available tools in the app.
    -   Provides methods to get tool specifications formatted for each provider and to execute a tool call requested by an LLM.

-   `app/src/main/java/com/example/ApI/tools/DateTimeTool.kt`
    -   An example implementation of a `Tool` that provides the current date and time.

### UI Layer & State Management

-   `app/src/main/java/com/example/ApI/ui/ChatViewModel.kt`
    -   **The central ViewModel for the application.**
    -   Holds the UI state (`ChatUiState`) and manages all user interactions.
    -   **Core Logic**: Sending messages, creating/selecting chats, managing API keys, handling file selections and uploads, managing search state.
    -   **UI Events**: Shows/hides all dialogs and context menus.
    -   **State Management**: Updates and exposes `ChatUiState` to the UI.
    -   **Feature Logic**: Handles Child Lock activation, multi-message mode, and title generation triggers.

-   `app/src/main/java/com/example/ApI/MainActivity.kt`
    -   The app's single `Activity`.
    -   Sets up the theme and the main composable `LLMChatApp`.
    -   **Navigation**: Contains the primary `when` statement that routes to different screens based on `viewModel.currentScreen`.
    -   **Intent Handling**: Processes incoming shared files or text from other apps.
    -   **Back Handling**: Manages system back button behavior.

-   `app/src/main/java/com/example/ApI/ui/screen/`
    -   **`ChatScreen.kt`**: The main chat interface. Displays messages, the input field, file attachments, and tool call results. Handles all user interactions within a chat.
    -   **`ChatHistoryScreen.kt`**: The main screen. Displays the list of all chats and groups. Includes search functionality and context menus for managing chats.
    -   **`GroupScreen.kt`**: Displays chats within a specific group and manages project-specific features like shared files and system prompts.
    -   **`ApiKeysScreen.kt`**: UI for adding, deleting, toggling, and reordering API keys for different providers.
    -   **`UsernameScreen.kt` (UserSettingsScreen)**: UI for managing application settings, including multi-message mode, title generation, and child lock.
    -   **`ChildLockScreen.kt`**: The lock screen displayed when the child lock feature is active.
    -   **`IntegrationsScreen.kt`**: UI for enabling or disabling available tools (function calling).

-   `app/src/main/java/com/example/ApI/ui/components/`
    -   Contains reusable UI components used across different screens.
    -   **`Dialogs.kt`**: Defines various AlertDialogs (e.g., `SystemPromptDialog`, `ProviderSelectorDialog`, `AddApiKeyDialog`).
    -   **`ChatHistoryDialog.kt`**: Contains the side panel for chat history and associated dialogs for renaming/deleting chats.
    -   **`FileSelectionDialog.kt`**: UI for choosing between taking a photo and selecting a file.
    -   **`ContextMenu.kt`**: A generic context menu component.

-   `app/src/main/java/com/example/ApI/ui/theme/`
    -   **`Theme.kt`**: Defines the `ApITheme` with a dark color scheme.
    -   **`Color.kt`**: Defines the application's color palette.
    -   **`Type.kt`**: Defines the application's typography styles.

### Testing

-   `app/src/test/java/com/example/ApI/ApiKeyReorderTest.kt`
    -   Unit tests to verify the logic for reordering API keys in a list.