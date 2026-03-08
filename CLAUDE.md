# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.uai.ExampleUnitTest"

# Run instrumented tests (requires running emulator/device)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Architecture Overview

Single-module Android app (`app/`) — Jetpack Compose + Material3, min SDK 24, target SDK 36, Kotlin 2.0.21, AGP 8.13.2.

**Manual DI root:** `UaiApplication` creates `AppContainer` which is a singleton holder for `OkHttpClient`, `AppDatabase` (Room), `AppPreferences` (DataStore), `ConversationRepository`, and `AgentRepository`. Both `MainActivity` and `FloatingBubbleService` resolve deps via `(application as UaiApplication).container`.

### Package Structure

```
com.example.uai/
├── UaiApplication.kt          # App class; creates AppContainer, notification channel
├── AppContainer.kt            # Manual DI: OkHttpClient, Room DB, DataStore, repos
├── MainActivity.kt            # Entry point; bottom nav (Chats/Agents/Settings)
│
├── data/
│   ├── db/                    # Room: ConversationEntity, MessageEntity, DAOs, AppDatabase
│   ├── prefs/AppPreferences.kt  # DataStore: agent list (JSON), active agent, bubble state
│   ├── model/AgentConfig.kt   # AgentConfig data class + AiProviderType enum
│   └── repository/            # ConversationRepository, AgentRepository
│
├── ai/
│   ├── AiProvider.kt          # Interface: streamResponse() → Flow<StreamChunk>
│   ├── StreamChunk.kt         # Sealed class: Token | Done | Error
│   ├── OpenAiProvider.kt      # OpenAI SSE streaming (also used by OpenRouter base)
│   ├── AnthropicProvider.kt   # Anthropic SSE (event: / data: format)
│   ├── OpenRouterProvider.kt  # Delegates to OpenAiProvider with different base URL
│   ├── OllamaProvider.kt      # Newline-delimited JSON streaming
│   └── AiProviderFactory.kt   # Creates correct provider from AgentConfig
│
├── service/
│   ├── ServiceLifecycleOwner.kt   # Implements LifecycleOwner+ViewModelStoreOwner+
│   │                               # SavedStateRegistryOwner for ComposeView in Service
│   └── FloatingBubbleService.kt   # Foreground service; WindowManager overlay bubble
│                                   # + bottom chat panel (both ComposeViews)
│
└── ui/
    ├── navigation/            # Routes.kt, AppNavGraph.kt
    ├── chat/                  # ChatPanel.kt (overlay UI), MessageBubble.kt, BubbleContent
    ├── conversations/         # ConversationsScreen + VM, ConversationDetailScreen + VM
    ├── agents/                # AgentsScreen + VM, AgentEditScreen + VM
    ├── settings/              # SettingsScreen + VM (overlay permission, bubble toggle)
    └── theme/                 # Color.kt, Theme.kt, Type.kt (unchanged from scaffold)
```

### Key Patterns

- **AI streaming:** All providers use `flow { ... }.flowOn(Dispatchers.IO)`. OkHttp `execute()` blocks on IO thread; `currentCoroutineContext().ensureActive()` handles cancellation; `CancellationException` triggers `call.cancel()`.
- **Overlay in Service:** `FloatingBubbleService` holds a `ServiceLifecycleOwner` and attaches it via `setViewTreeLifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner` before calling `windowManager.addView(composeView, params)`.
- **Bubble drag vs tap:** `setOnTouchListener` tracks raw pointer delta; drag threshold is 8px; `ACTION_UP` without drag calls `toggleChatPanel()`.
- **Foreground service type:** `specialUse` (API 34+ requires `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`). Guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`.
- **Agent configs** stored as Gson-serialized JSON list in DataStore (not Room), since agents are configuration, not user data.
- **Streaming token updates** write to Room on every token via `messageDao.updateContent()` so the main app's `Flow<List<MessageEntity>>` shows live progress.
- Dependency versions centralized in `gradle/libs.versions.toml`; Room uses KSP (`ksp = "2.0.21-1.0.28"`).
