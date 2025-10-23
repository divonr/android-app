# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android multi-provider LLM chat client built with Kotlin and Jetpack Compose, following MVVM architecture. The app allows users to chat with multiple AI providers (OpenAI, Google Gemini, Poe) with support for file attachments, streaming responses, and chat organization.

## Build Commands

```bash
# Build the project (assembles and runs tests)
./gradlew build

# Assemble debug APK
./gradlew assembleDebug

# Assemble release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test
./gradlew testDebugUnitTest

# Run lint checks
./gradlew lint
./gradlew lintDebug

# Clean build artifacts
./gradlew clean

# Install and run on connected device
./gradlew installDebug
```

## Architecture

### Core MVVM Structure

- **ChatViewModel** (`ui/ChatViewModel.kt`): Central brain managing all UI state via `ChatUiState` StateFlow. Handles user actions, chat lifecycle, provider/model selection, and message editing/resending.

- **DataRepository** (`data/repository/DataRepository.kt`): Single source of truth. Manages local JSON storage (chat_history_{user}.json, api_keys_{user}.json, app_settings.json) and orchestrates network calls. Handles file uploads per provider via `ensureFilesUploadedForProvider()` before API calls.

- **ApiService** (`data/network/ApiService.kt`): Direct network communication with LLM APIs. Implements provider-specific streaming methods (`sendOpenAIMessageStreaming`, `sendGoogleMessageStreaming`, `sendPoeMessageStreaming`) that parse SSE responses and callback to ViewModel.

### Provider Configuration

`app/src/main/assets/providers.json` defines API endpoints, request structure, headers, body format, and available models for each provider. This is the configuration source for all provider integrations.

### Data Models

- **ChatHistory.kt**: `UserChatHistory`, `Chat`, `Message`, `ChatGroup` - defines JSON schemas for local storage
- **ApiKey.kt**: `ApiKey`, `AppSettings` - user settings and API key management
- **Provider.kt**: Data classes for parsing providers.json (base_url, request_type, models with capabilities)

### Streaming Implementation

All providers use Server-Sent Events (SSE) for streaming:

- **OpenAI**: Listen for `response.output_text.delta` events with `delta` field. See `openai_streaming_guide.md` for complete event lifecycle.
- **Google Gemini**: Parse `data: ` prefixed JSON chunks, concatenate `text` from `parts` arrays until `finishReason: STOP`. See `google_streaming_guide.md`.
- Each provider has custom JSON request structure defined in providers.json that ApiService constructs.

### File Upload Flow

1. ViewModel calls `repository.sendMessageStreaming()`
2. Repository calls `ensureFilesUploadedForProvider()` to upload files if needed
3. Provider-specific upload methods store file IDs/URIs in attachment objects
4. ApiService constructs API requests with file references per provider format

## UI Screens

- **ChatScreen.kt**: Main conversation view with MessageBubble (includes long-press context menu), FilePreview, and input bar. Handles both regular and streaming message rendering.
- **ChatHistoryScreen.kt**: Lists chats and groups with context menus for rename/delete/group management.
- **GroupScreen.kt**: Group/project view with ProjectArea for shared system prompts and file attachments.
- **ApiKeysScreen.kt**: API key management interface.
- **UsernameScreen.kt**: Global app settings (auto title generation, multi-message mode).

## Text Direction Handling

The app supports RTL/LTR text direction toggling using Unicode control characters (LRE \u202A, RLE \u202B, PDF \u202C) implemented in `TextDirectionUtils.kt`. This forces text direction regardless of content language. See `RTL_LTR_FIX_SUMMARY.md` for implementation details.

## Data Storage

All user data stored as JSON files in app storage:
- `chat_history_{username}.json`: Per-user chat history with messages and attachments
- `api_keys_{username}.json`: API keys for providers
- `app_settings.json`: Global settings (current user, selected model, feature flags)

File structure example in `chat_history_example.json`.

## Key Dependencies

- Jetpack Compose for UI with Material3
- OkHttp/Retrofit for networking
- Kotlinx Serialization for JSON
- CommonMark for markdown rendering (with GFM tables/strikethrough extensions)
- Coil for image loading
- AndroidX Security Crypto for secure storage
- CameraX for camera features

## Important Notes

- The `StreamingCallback` interface (`data/model/StreamingCallback.kt`) is used throughout for streaming message updates from ApiService → Repository → ViewModel → UI.
- Message editing creates new versions that can be resent from that point (`resendFromMessage()` in ViewModel).
- Groups act as folders with optional "project" mode that adds shared context (system prompt + files) to all chats within.
- Provider selection determines which upload method and API format to use - this is all abstracted through providers.json configuration.
