---
name: feedback-tech-choices
description: Confirmed architectural choices for the Android port — Jetpack Compose native, EncryptedSharedPreferences for keys
metadata:
  type: feedback
---

For [[project-novelseek-ultra]]: when the user asks to port more PC features to Android, write native Kotlin + Jetpack Compose (Material 3, Navigation Compose, AndroidViewModel + StateFlow). Do NOT propose WebView wrapping / Capacitor / KMP unless the user explicitly revisits this.

API keys (textModelProfiles[].apiKey, textModelConfig.apiKey, embeddingConfig.apiKey, pollinationsKey) must always be routed into `SecureStore` (EncryptedSharedPreferences, AES256-GCM via MasterKey) — never into the plain JSON state file or DataStore.

**Why:** User explicitly chose these in the kick-off (2026-05-28) after seeing the trade-offs. Native rewrite was picked over the much cheaper WebView wrapper despite the larger effort. Secure key storage was picked over PC parity (PC stores keys in plain localStorage).

**How to apply:**
- New ports of PC settings panels: read sensitive fields from `SecureStore`, write the rest to `AppRepository`.
- New PC backup export → re-inline secrets from SecureStore on the way out (already handled in `AppRepository.mergeSensitivesIntoState`).
- New PC backup import → strip secrets and route them via `AppRepository.splitSensitives` (already implemented).
