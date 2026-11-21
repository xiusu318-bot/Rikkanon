# Repository Guidelines

## Project Overview

Rikkanon (package `me.rerere.rikkahub.rikkanon`) is a personal Android AI Agent client forked from RikkaHub, supporting multi-provider LLM chat, advanced SillyTavern-style prompt assembly & lorebooks, persistent workspace PTY terminals, sub-agents, a QuickJS plugin runtime (`:plugin`), and an independent native Custom UI engine (`.ui.js`).
Built with Jetpack Compose and Kotlin.

## Design Language

The default host UI uses `MaterialExpressiveTheme` (`app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt`) while preserving compact card/dialog workflows preferred in this fork.

- Settings-style and form pages use `CardGroup` (`app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt`).
- Shared controls such as `UIModalBottomSheet`, `UISwitch`, `uiToggleButtonShapes`, `uiConnectedButtonShapes`, and `uiSegmentedListItemShapes` (`app/src/main/java/me/rerere/rikkahub/ui/custom/UIHostComponents.kt`) behave identically to baseline components when Custom UI is off and automatically adapt to `ui.shapes` / `ui.style` when Custom UI is enabled.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with Compose UI, Custom UI engine (`ui/custom/`), ViewModels, database, and core orchestration
- **ai**: AI SDK abstraction layer for different providers (OpenAI Chat & Responses, Google GenerateContent & Interactions, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions (HCT / Oklab) used by the app UI
- **mediagen**: Unified image and video generation provider abstraction
- **oauth**: OAuth authentication helpers
- **plugin**: Standalone QuickJS (`quickjs-kt`) ES2020 plugin runtime, registry, Java reflection bridge (`PluginJavaBridge`), and Pine ART method hook engine (`PluginHookEngine`)
- **search**: Web search SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, etc.)
- **speech**: TTS and ASR provider implementations
- **terminal**: Terminal emulator core (`TerminalSession` / `TerminalTransport` / `LocalPtyTransport`) for local PRoot and remote SSH sessions
- **ui**: Reusable Compose UI components and Markdown renderer independent of app business logic
- **web**: Embedded Ktor server hosting static frontend build files from `web-ui/`
- **workspace**: Sandboxed per-workspace PRoot / SSH file system, shell execution, and transparent loopback proxy bridge

## Core Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, temperature, context size, custom headers, local/workspace tools, sub-agent bindings, memory options, regex rules, and prompt presets / lorebooks (`app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`).

- **Conversation**: A persistent conversation thread holding a tree of `MessageNode` branches, title, timestamps, pin status, prompt injection bindings, and optional sub-agent linkage (`parentConversationId`, `subAgentCode`). Unlike upstream snapshot locking, **conversations dynamically follow their bound `Assistant`'s latest configuration** (model, reasoning level, web search, MCP servers, skills, workspace) via `Settings.getAssistantById(conversation.assistantId)` (`app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`).

- **UIMessage & MessageNode**: `UIMessage` (`ai/src/main/java/me/rerere/ai/ui/Message.kt`) encapsulates multi-part messages (text, media, reasoning, tool calls/results) with streaming chunk merging and `finalizeInterruptedParts` recovery. `MessageNode` wraps alternative `UIMessage` branches with `selectIndex` and supports incremental single-node persistence (`checkpointConversation`) plus chunked `substr` fallback loading for nodes exceeding Android's 2MB `CursorWindow`.

- **Message Transformer**: Input/output transformation pipeline (`app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt`), including `PromptInjectionTransformer` (advanced prompt preset track, `@D` depth injection, raw JSON blocks, and `LorebookScanEngine`), `TemplateTransformer`, `ThinkTagTransformer`, `RegexOutputTransformer`, `DocumentAsPromptTransformer`, `Base64ImageToLocalFileTransformer`, `OcrTransformer`, and `WorkspaceReminderTransformer`.

## Custom UI Hooks & R8 / ProGuard Rules

- **Preserve Custom UI Hooks**: Host components contain lightweight hooks such as `UISlot("chat.topbar", ...) { original }`, `uiComponentStyle("card_group")`, `uiPageEntryDecorator`, and `CustomColors.topBarColors`. When Custom UI is off (default), they run the original host code unchanged. Do not delete these hook wrappers or `style?.x ?: originalValue` fallbacks when editing UI screens; see `CUSTOM_UI_DOCS.md`.
- **Kotlin / Compose & Android ICU Pitfalls**:
  - Never write `return@label` inside inline `@Composable` lambdas (`Column`, `Row`, `Box`, `key`), as R8 dexing fails with `$$$$$NON_LOCAL_RETURN$$$$$`.
  - Always escape `{`, `}`, `[`, `]` in Android ICU regular expressions (e.g. `\\}\\}`).
- **R8 & Reflection Compatibility**:
  - All modules compile with `-java-parameters` so `java.copy(obj, patch)` can match Kotlin `data class` primary `copy(...)` parameter names reliably on Android ART (where `Class.getDeclaredFields()` reorders fields).
  - `app/proguard-rules.pro` uses `-keep,allowshrinking` on `me.rerere.**` (excluding `me.rerere.hugeicons.**`) and `kotlin.uuid.**`, and applies hard `-keep` (without `allowshrinking`) on public data models, `SettingsExtKt` / `DefaultSettingsKt`, repositories, ViewModels, and composable controls documented in `CUSTOM_UI_DOCS.md` and `PLUGIN_API_DOC.md`. Keep `proguard-rules.pro` in sync if adding new public APIs promised in those docs.

## Internationalization

- String resources live in `app/src/main/res/values*/strings.xml` (and module-level `values*/strings.xml` such as `search`).
- Use `stringResource(R.string.key_name)` in Compose; page-specific strings use page prefixes (e.g. `setting_page_`).
- Unless localization is explicitly requested, prioritize implementing functionality directly. For `locale-tui`, use its CLI/TUI workflow documented in `locale-tui/CLAUDE.md`.
