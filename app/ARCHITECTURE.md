# UAI App — Architecture Guide

## Purpose

This document is the entry point for any contributor (human or automated) editing this codebase.
It defines the structural rules, the reasoning behind them, and onboarding guidance for each layer.

**Primary goal:** changes to one feature should not require testing other features.
A contributor who modifies `feature/agora/` should only need to regression-test Agora.
A contributor who modifies `shared/chat-ui/` must test all features that consume it — and the
impact checklist in this document tells them exactly which those are.

---

## Layer Map

```
┌─────────────────────────────────────────────────────────┐
│                      feature/                           │
│  conversations │ agora │ agents │ settings │ bubble      │
│                  (each is an isolated silo)             │
└──────────────────────────┬──────────────────────────────┘
                           │ may depend on ↓
┌──────────────────────────▼──────────────────────────────┐
│                      shared/                            │
│         chat-ui │ attachment │ streaming                │
│         (cross-feature infrastructure)                  │
└──────────────────────────┬──────────────────────────────┘
                           │ may depend on ↓
┌──────────────────────────▼──────────────────────────────┐
│                       data/                             │
│           db │ repository │ model │ prefs               │
└──────────────────────────┬──────────────────────────────┘
                           │ may depend on ↓
┌──────────────────────────▼──────────────────────────────┐
│                      design/                            │
│                 components │ theme                      │
└─────────────────────────────────────────────────────────┘
```

**The one rule:** dependencies flow downward only.
A feature never imports from another feature.
`shared/` never imports from `feature/`.

---

## Package Structure

```
com.example.uai/
├── app/                        # Shell only — wiring, DI root, entry point
│   ├── UaiApplication.kt
│   ├── AppContainer.kt
│   └── MainActivity.kt
│
├── feature/
│   ├── conversations/          # 1-on-1 chat
│   ├── agora/                  # Multi-agent chat
│   ├── agents/                 # Assistant management
│   ├── settings/               # App settings
│   └── bubble/                 # Floating overlay (mini chat)
│
├── shared/
│   ├── chat-ui/                # Chat display primitives
│   ├── attachment/             # File/image/screenshot handling
│   └── streaming/              # AI provider pipeline
│
├── data/
│   ├── db/
│   ├── repository/
│   ├── model/
│   └── prefs/
│
└── design/
    ├── components/
    └── theme/
```

> **Current state:** migration is complete. All files are in their target packages.
> See the Decision Log at the bottom for the history of each structural decision.

---

## OOP & Composition Contract

Shared components define **structure**. Features inject **behavior**.

A shared component exposes its customization surface through:
- **Callback parameters** — `onReply`, `onDoubleTap`, `onSend`, `onStop`
- **Slot composables** — `textFieldContent: @Composable RowScope.() -> Unit`
- **Optional parameters with defaults** — `showAgentName: Boolean = true`

Features use these surfaces to specialize behavior without modifying the component.

```
shared/chat-ui/MessageBubble    defines: bubble layout, markdown, image display
                                exposes: onDoubleTap, onReply, onLongPress

feature/conversations/          injects: reply → scroll-to-message behavior
feature/agora/                  injects: reply → @mention pre-fill behavior
feature/bubble/                 injects: double-tap → minimize overlay behavior
```

**Rule:** if you need behavior that the shared component's interface does not expose,
add a new parameter to the shared component — do not fork the component or add
feature-specific `if` branches inside it.

---

## Feature Catalogue

### `feature/conversations/` — 1-on-1 Chat
Owns the list of past conversations and the single-conversation detail view with full AI streaming.

Key files: `ConversationsScreen`, `ConversationDetailScreen`, `ConversationDetailViewModel`

Uses from shared: `chat-ui` (message list, bubbles, input bar), `attachment` (images, files, screenshots), `streaming` (AI runtime)

Does **not** know about: Agora, bubble/mini-chat internals

---

### `feature/agora/` — Multi-agent Chat ⚠️ DISABLED
Owns the Agora conversation flow where multiple agents participate and can be @mentioned.

Key files: `AgoraListScreen`, `AgoraDetailScreen`, `AgoraDetailViewModel`, `AgoraCreateScreen`

> **Status: feature-flagged off.** Agora is not reachable in the current build and is excluded
> from all regression testing. Do not modify Agora files as part of work on other features.
> When Agora is re-enabled, a dedicated regression pass covering its full flow must be run before release.

Uses from shared: same as `conversations/`

Streaming logic is intentionally duplicated from `conversations/` — this is by design (see Decision Log).

Does **not** know about: Conversations feature internals, bubble/mini-chat internals

---

### `feature/agents/` — Assistant Management
Owns the creation, editing, reordering, and deletion of AI assistant configurations.

Key files: `AgentsScreen`, `AgentsViewModel`, `AgentEditScreen`, `AgentEditViewModel`

Uses from shared: `streaming` (only for connection testing and model catalog fetching)

Does **not** know about: any chat feature

---

### `feature/settings/` — App Settings
Owns overlay permission, bubble toggle, and color theme selection.

Key files: `SettingsScreen`, `SettingsViewModel`

Uses from shared: nothing — reads directly from `data/repository`

---

### `feature/bubble/` — Floating Overlay (Mini Chat)
Owns the floating bubble, the slide-up chat panel, and all overlay window lifecycle.
This is the only feature that runs inside a foreground `Service` rather than an `Activity`.

Key files: `FloatingBubbleService`, `ChatPanel`, `ServiceLifecycleOwner`, `OverlayBubblePositioning`

`ChatPanel` is the mini-chat's own screen component — it is **not** shared with other features.
It consumes `shared/chat-ui` primitives (MessageBubble, ChatInputBar, etc.) but composes
them into a layout specific to the overlay surface.

Uses from shared: `chat-ui`, `attachment`, `streaming`

Does **not** know about: Conversations or Agora feature internals

---

## Shared Layer Catalogue

### `shared/chat-ui/` — Chat Display Primitives
Rendering components with no business logic. No ViewModels. No navigation.
Any feature that renders chat messages uses these.

| Component | Responsibility |
|---|---|
| `MessageBubble` | Renders a single message with markdown, images, file attachments, swipe-to-reply |
| `ChatMessageList` | Lazy scrolling list of MessageBubble items with auto-scroll behavior |
| `ChatInputBar` | Unified input bar: reply preview + attachment strip + text field slot + send/stop |
| `MarkdownMessageText` | Markdown-to-Compose renderer for message content |
| `LoadingStatusText` | Rotating loading phrases shown while the AI is streaming |
| `AttachedFileDisplay` | Preview card for attached files inside a bubble |
| `OverlayTextToolbar` | Custom text selection toolbar for `TYPE_APPLICATION_OVERLAY` windows |

**Impact:** changes here affect all active chat features. Run regression on: conversations, bubble. Agora is excluded until re-enabled.

---

### `shared/attachment/` — Attachment Handling
Stateless utilities for importing, encoding, persisting, and capturing attachments.
No UI. No ViewModels.

| Component | Responsibility |
|---|---|
| `FileAttachmentImport` | Extracts text from PDF, DOCX, XLSX, PPTX, plain text files |
| `AttachmentPersistence` | Saves and deletes image files to local storage |
| `ScreenCaptureHelper` | Performs screen capture via `MediaProjection` and encodes to base64 |
| `CameraPermission` | Composable helper for requesting camera permission |

**Impact:** changes here affect all active chat features. Run regression on: conversations, bubble. Agora is excluded until re-enabled.

---

### `shared/streaming/` — AI Provider Pipeline
The full AI streaming stack. Handles provider selection, SSE parsing, search grounding,
history compression, and rate-limited DB writes.

| Component | Responsibility |
|---|---|
| `AiProvider` / `StreamChunk` | Interface contract + result type for all providers |
| `AiProviderFactory` | Selects the correct provider implementation from an `AgentConfig` |
| Provider implementations | `OpenAiProvider`, `AnthropicProvider`, `OpenRouterProvider`, `OllamaProvider` |
| `ToolAwareAssistantRuntime` | Orchestrates a full streaming turn: history → provider → DB write |
| `AssistantStreamingSession` | Holds cancellable session state for an active stream |
| `HistoryCompressor` | Trims conversation history to fit context window limits |
| `ThrottledStreamingMessageWriter` | Rate-limits Room DB writes during streaming |
| `WebGateway` / `WebGroundingService` | Web search and response grounding |
| `SearchPlanningService` | Decides when and what to search |

**Impact:** changes here affect all active chat features. Run regression on: conversations, bubble.
Changes to a single provider implementation (e.g. `AnthropicProvider`) only affect conversations
using that provider — but run full streaming regression for safety.
Agora is excluded until re-enabled.

---

## Onboarding: How to Add a New Feature

1. Create `feature/<your-feature>/` package.
2. Add your Screen composable(s) and ViewModel(s) inside it.
3. Consume `shared/` components through their public parameters — do not reach into their internals.
4. Add your route to `Routes.kt` and wire the screen in `AppNavGraph.kt`.
5. If your feature needs DI, add its dependencies to `AppContainer` and resolve them in `AppNavGraph`.
6. Write in this document: one section under Feature Catalogue, and a Decision Log entry.

---

## Onboarding: How to Extend an Existing Feature

This is the most common type of work. The rules below are mandatory — not optional guidelines.

### The extension checklist

**Before writing any code, answer these three questions:**

1. **Does the change belong exclusively to one feature?**
   If yes → all new code goes inside `feature/<name>/` only. No other package is touched.
   If no → the change belongs in `shared/` (see "How to Modify a Shared Component" below).

2. **Do I need to reuse something from another feature?**
   Never import from a sibling `feature/` package. If two features need the same thing,
   that thing must live in `shared/`, `data/`, or `design/` — extract it first, then use it from both.

3. **Do I need to change a shared component's behavior?**
   Do not add feature-specific `if` branches inside shared components.
   Instead, add a new optional parameter with a safe default so existing callers are unaffected.

### Extending a Screen (new UI section, new button, new state)

1. Open the Screen and ViewModel inside `feature/<name>/`.
2. Add state to the ViewModel as a new `StateFlow`. Do not reuse existing state for a different purpose.
3. Add the UI inside the Screen composable. If the new UI uses a shared component, pass behavior
   through the component's existing callback/slot parameters.
4. If the shared component does not expose the hook you need, add a new optional parameter to it
   (see OOP & Composition Contract). Update all existing call sites to pass the default value explicitly
   so the change is visible in code review.
5. Regression scope: only `feature/<name>/`. If you touched a shared component, also test all its consumers.

### Extending a ViewModel (new action, new data, new side effect)

1. Add new methods and `StateFlow`s inside the existing ViewModel class in `feature/<name>/`.
2. Do not create a base class shared between features — duplication between feature ViewModels
   is intentional (see Decision Log #3).
3. If the new action requires a new data operation, add it to the relevant repository in `data/repository/`
   and call it from the ViewModel. Do not call the DAO directly from a ViewModel.
4. Regression scope: the feature whose ViewModel changed.

### Extending an existing Shared Component (adding a new capability)

1. Add the new capability as an **optional parameter with a safe default**:
   ```kotlin
   // Before
   fun MessageBubble(message: MessageEntity, onReply: () -> Unit)
   // After — existing callers pass nothing and get the old behavior
   fun MessageBubble(message: MessageEntity, onReply: () -> Unit, onShare: (() -> Unit)? = null)
   ```
2. All existing call sites continue to compile without changes.
3. Only the feature that needs the new behavior passes a non-default value.
4. Regression scope: all features that consume this shared component.
5. Add a Decision Log entry.

### Adding a new data type or repository operation

1. Add the new entity or field in `data/db/`. Run the Room migration if the schema changes.
2. Add the new query or operation in the relevant DAO and Repository.
3. Wire the repository into the feature ViewModel via `AppContainer`.
4. Regression scope: only the feature(s) that use the new data.

### What is explicitly forbidden when extending

| Action | Why forbidden |
|---|---|
| Import `feature/A` from `feature/B` | Couples two features; changes to A require testing B |
| Add a feature-specific `if (isAgora)` inside a shared component | Hides feature logic in shared code; breaks when the condition grows |
| Create a new file in `shared/` that is only used by one feature | Shared means used by multiple features; single-feature code belongs in `feature/` |
| Duplicate a shared component with minor tweaks | Creates two diverging versions; use a parameter instead |
| Call a DAO directly from a Screen or ViewModel without going through a Repository | Bypasses the data layer contract |

---

## Onboarding: How to Modify a Feature

1. Locate the feature's package (`feature/<name>/`).
2. Make your change entirely within that package.
3. If you find yourself importing from another `feature/` package — stop. Extract the dependency to `shared/` instead.
4. Regression scope: only that feature.

---

## Onboarding: How to Modify a Shared Component

1. Identify which features consume the component (see Shared Layer Catalogue above).
2. If adding new behavior: add it as a new optional parameter with a safe default so existing callers are unaffected.
3. If changing existing behavior: audit every call site across all consuming features before changing.
4. Regression scope: all consuming features.
5. Add a Decision Log entry explaining the change and why it belongs in shared rather than in a feature.

---

## Decision Log

| # | Decision | Reason |
|---|---|---|
| 1 | Feature-based package isolation introduced | Changes to one feature were causing unintended regression in others due to shared flat structure. Goal: each feature change requires testing only that feature. |
| 2 | `ChatPanel` lives in `feature/bubble/`, not in `shared/chat-ui/` | ChatPanel is the bubble feature's own screen. It has one consumer (`FloatingBubbleService`). Keeping it in the shared layer was misleading — it looked like shared infrastructure but was feature-specific. |
| 3 | Streaming logic duplicated between `feature/conversations/` and `feature/agora/` | Sharing a single streaming ViewModel base class would couple the two features. A bug fix or behavior change in one would require retesting the other. Duplication is the deliberate trade-off for isolation. |
| 4 | Single Gradle module retained | Multi-module Gradle adds build complexity (configuration, inter-module API surfaces, longer initial setup). Package-level discipline achieves the same isolation benefit for this codebase's current scale. Revisit if the module grows significantly. |
| 5 | Shared components use callback/slot APIs for behavior customization | Features must be able to specialize shared component behavior without forking the component. Callbacks and Compose slot parameters are the OOP composition mechanism in this stack. |
