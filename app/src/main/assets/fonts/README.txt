放置中文宋体字体 / Place the Chinese Song (serif) font here
============================================================

为什么需要：
  Android 原生 PdfDocument 渲染中文靠系统字体，但它无法生成"可点击的目录超链接 /
  PDF 书签 / 封面标题框"。本项目改用 PdfBox-Android 来支持这些功能，而 PdfBox 必须
  *自带* 一个能覆盖中文字形的字体文件——它不会读系统字体。

怎么做：
  1. 准备一个【中文宋体 / 衬线】TrueType 字体（必须是 .ttf，不要 .otf / .ttc）。
     可选来源（任选其一，注意各自授权）：
       - 思源宋体 Source Han Serif / Noto Serif CJK 的 TTF 构建版
       - 方正书宋 / 思源宋体的 TTF 子集
       - 其它任何带 glyf 表的中文 .ttf 衬线字体
     ⚠ 不要用 .otf（CFF 字体 PdfBox 难以嵌入）或 .ttc（字体集合，需特殊处理）。

  2. 把字体文件重命名为：
       cjk-serif.ttf

  3. 放到本目录：
       app/src/main/assets/fonts/cjk-serif.ttf

  4. 重新编译运行。导出 PDF 时若导出页显示"✓ 已检测到内置中文字体"，即生效：
       - 满页封面 + 顶部白底黑框带阴影的书名
       - 第二页起为目录，条目可点击跳转 + PDF 书签
       - 每章另起页：加粗标题 → 推文图 → 浅色摘要 → 宋体正文（避头尾换行）
       - 从目录开始的页码

  若未放置字体：导出仍可用，但回退为"基础排版"（系统字体、无封面标题框 /
  目录超链接 / 书签）。

Why this is needed:
  PdfBox-Android needs an embedded TTF that covers Chinese glyphs (it does not use
  system fonts). Drop a Chinese Song/serif TrueType font here, renamed to
  `cjk-serif.ttf`, then rebuild. Without it, PDF export falls back to a basic
  (system-font, no-links) layout.
