---
name: feedback-no-powershell-on-utf8
description: Never use PowerShell Get-Content/Set-Content to truncate or rewrite Kotlin source files — it corrupts Chinese text
metadata:
  type: feedback
---

NEVER use Windows PowerShell `Get-Content` + `Set-Content`/`Out-File` to truncate, filter, or rewrite source files in this project. The repo's Kotlin files are UTF-8 (no BOM) and full of Chinese string literals (every `tx(lang, "中文", "english")`).

**Why:** PS 5.1 `Get-Content` defaults to the system ANSI codepage (GBK/cp936 on this Chinese Windows), so it misreads UTF-8 multibyte bytes; `Set-Content -Encoding utf8` then re-encodes the mojibake. Result: every Chinese char turns into garbage like `大纲`→`澶х翰`. A reverse transform (`gbk.GetBytes(utf8.GetString(bytes))`) recovers most CJK but is **lossy on non-CJK punctuation** like `·` (U+00B7) and `—` (U+2014), which desyncs byte alignment and corrupts adjacent CJK chars AND can break string-literal quotes → uncompilable.

**How to apply:**
- To remove/replace code: use the Edit tool (preserves UTF-8) or the Write tool with full content.
- To truncate a file: read it with the Read tool, then Write the kept portion.
- If a file does get mojibake-corrupted: do NOT trust the GBK reverse transform for files containing `·`/`—`/box-drawing chars. Fully rewrite the file from a known-good copy (e.g. an earlier Read in context).
- Raw-bytes PowerShell (`[System.IO.File]::ReadAllBytes/WriteAllBytes`) is safe ONLY if you never decode/re-encode through a string.
