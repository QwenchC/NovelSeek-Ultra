---
name: project-novelseek-ultra
description: NovelSeek-Ultra is a native Android Compose port of the PC desktop app at E:\Lumi\NovelSeek-Pro-PC; backup JSON is the interop format
metadata:
  type: project
---

NovelSeek-Ultra (E:\Lumi\NovelSeek-Ultra) is being ported from NovelSeek-Pro-PC (E:\Lumi\NovelSeek-Pro-PC, a Tauri + React + TypeScript desktop app, ~19k lines across 13 pages).

**Why:** User wants the full feature set on Android while keeping data interchangeable with the PC build.

**How to apply:**
- The PC `SettingsPage.handleExportBackup` produces `BackupBundle { version: 1, exportedAt, appVersion, data }` JSON. `data` contains 11 `xxxByProject` metadata maps, `folders`, and 12 app-settings fields (listed as PROJECT_MAP_FIELDS and APP_SETTINGS_FIELDS in both projects). Chapter bodies are NOT in the backup (they live in PC-side SQLite). The Android `AppRepository.importBackup` mirrors `handleConfirmImport` semantics: per-key merge, import wins; app settings only applied if user opts in.
- Android state is persisted as the raw JsonObject mirroring the PC zustand shape (file: `<filesDir>/app_state.json`), so new PC fields survive round-trip without an Android schema change.
- Currently implemented Android-side (full 13-page parity in skeleton form):
  - HomeScreen, LongNovelsHomeScreen, ProjectScreen, LongNovelScreen
  - OutlineScreen (shared by short/long, AI streaming)
  - CharactersScreen (AI appearance + Pollinations portrait)
  - EditorScreen (chapter editor with AI generate/revise, streaming via OkHttp SSE)
  - ExportScreen (TXT/Markdown/PDF via PdfDocument + SAF)
  - CultivationScreen (realm system)
  - SettingsScreen (language, AI model profiles, Pollinations key, backup import/export)
- Data layer:
  - `AppRepository` — JsonObject-backed state mirroring PC zustand; first-launch seeds the 4 built-in profiles
  - `SecureStore` — EncryptedSharedPreferences for all API keys
  - `AiService` — OkHttp-based replacement for Tauri AI invokes (OpenAI-compat streaming chat + Pollinations image)
  - Chapter bodies stored per-chapter as JSON files in `<filesDir>/chapters/` (kept out of main state for size)
- Things deliberately simplified vs. PC: no Monaco-like editor (BasicTextField), no jsPDF cover/illustration rendering (plain text PDF), no local RAG knowledge base, no chapter-promo image generation, no character-relationship graph UI, no comfyUI image backend.
