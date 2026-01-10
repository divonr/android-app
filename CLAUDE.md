# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android multi-provider LLM chat client built with Kotlin and Jetpack Compose, following MVVM architecture. The app allows users to chat with multiple AI providers (OpenAI, Google Gemini, Poe etc.) with support for file attachments, streaming responses, and chat organization.

## Build, commit and push

Here is Termux enviroent, and therefore gradle isn't available here and you can't build.
Hence, you should not try to build. Instead, the user wants you to commit and push after each task you finish.
Commit messages must be short and clean, without crediting yourself. One sentence is recommended, when relevant.

## Architecture

### Core MVVM Structure

- **ChatViewModel** (`ui/ChatViewModel.kt`): Central brain managing all UI state via `ChatUiState` StateFlow. Handles user actions, chat lifecycle, provider/model selection, and message editing/resending.

- **DataRepository** (`data/repository/DataRepository.kt`): Single source of truth. Manages local JSON storage (chat_history_{user}.json, api_keys_{user}.json, app_settings.json) and orchestrates network calls. Handles file uploads per provider via `ensureFilesUploadedForProvider()` before API calls.

- **LLMApiService** (`data/network/LLMApiService.kt`): Thin coordinator that routes messages to provider-specific implementations. Each provider has its own class in `data/network/providers/`:
  - `OpenAIProvider.kt` - OpenAI API with tool calling and thinking
  - `GoogleProvider.kt` - Google Gemini with thinking support
  - `AnthropicProvider.kt` - Anthropic Claude with tool calling
  - `PoeProvider.kt` - Poe API with tool result handling
  - `CohereProvider.kt` - Cohere v2 API
  - `OpenRouterProvider.kt` - OpenRouter (OpenAI-compatible)
  - `BaseProvider.kt` - Abstract base class with common utilities.

### Data Models

- **ChatHistory.kt**: `UserChatHistory`, `Chat`, `Message`, `ChatGroup` - defines JSON schemas for local storage
- **ApiKey.kt**: `ApiKey`, `AppSettings` - user settings and API key management
- **Provider.kt**: Data classes for parsing providers.json (base_url, request_type, models with capabilities)

### Streaming Implementation

All providers use Server-Sent Events (SSE) for streaming. Each provider class in `data/network/providers/` handles its own SSE parsing:

- **OpenAI**: Listen for `response.output_text.delta` events with `delta` field. See `openai_streaming_guide.md` for complete event lifecycle.
- **Google Gemini**: Parse `data: ` prefixed JSON chunks, concatenate `text` from `parts` arrays until `finishReason: STOP`. See `google_streaming_guide.md`.
- Each provider extends `BaseProvider` and implements `sendMessage()` with provider-specific request building and SSE parsing.
- See `ADDING_NEW_PROVIDER_GUIDE.md` for detailed instructions on adding new providers.

### File Upload Flow

1. ViewModel calls `repository.sendMessageStreaming()`
2. Repository calls `ensureFilesUploadedForProvider()` to upload files if needed
3. Provider-specific upload methods store file IDs/URIs in attachment objects
4. LLMApiService routes to the appropriate provider, which constructs API requests with file references

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

- The `StreamingCallback` interface (`data/model/StreamingCallback.kt`) is used throughout for streaming message updates from Provider → LLMApiService → Repository → ViewModel → UI.
- Message editing creates new versions that can be resent from that point (`resendFromMessage()` in ViewModel).
- Groups act as folders with optional "project" mode that adds shared context (system prompt + files) to all chats within.
- Provider selection determines which upload method and API format to use - this is all abstracted through providers.json configuration.
