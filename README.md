# NovelSeek Ultra

> 一款基于 AI 的 Android 小说创作助手，由桌面端项目 [NovelSeek-Pro-PC](https://github.com/QwenchC/NovelSeek-Pro-PC) 迁移而来。

<p align="center">
  <img src="app/src/main/res/drawable/splash_icon.xml" width="120" alt="NovelSeek Ultra Logo"/>
</p>

---

## 功能概览

### 📖 长篇小说
- 创建与管理多个长篇小说项目
- AI 生成包含剧情弧线、章节结构的完整大纲
- 大纲保存支持选择性覆盖世界观、时间线、角色、章节等字段
- 逐章 AI 写作，自动注入上下文（前章摘要、角色信息、世界观）
- 章节封面头图批量生成（跳过已有图片）
- 宣传图 & 封面图 AI 生成

### 📄 短篇小说
- 创建与管理多个短篇小说项目
- AI 生成严格格式大纲（世界观 / 时间线 / 章节列表，无弧线）
- 从大纲一键导入空白章节
- 逐章 AI 写作，章节间自动衔接（前章摘要机制）

### 🧙 角色管理
- 每个项目独立的角色库
- 从 AI 生成大纲中批量导入角色
- 角色信息（背景、外貌、性格等）自动注入章节生成

### 🌍 世界观 & 修炼体系
- 独立世界观信息管理
- 修炼体系（境界）编辑对话框
- 世界观信息注入大纲与章节生成

### 📤 导出
- 章节文本导出为 **PDF** 或纯文本

### ⚙️ 设置
- 配置 AI 服务的 API Key 与 Base URL（默认接入 Pollinations）
- 密钥本地加密存储（Android Keystore + `security-crypto`）

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | ViewModel + Repository（单模块） |
| 网络 | OkHttp 4 + SSE 流式输出 |
| 图片 | Coil Compose |
| 序列化 | Kotlinx Serialization JSON |
| 持久化 | DataStore Preferences + JSON 文件 |
| 安全 | AndroidX Security Crypto（EncryptedSharedPreferences） |
| PDF 导出 | PdfBox Android |
| 启动屏 | Android SplashScreen API (core-splashscreen 1.0.1) |
| 最低 SDK | API 24（Android 7.0） |
| 目标 SDK | API 36 |

---

## 快速开始

### 环境要求

- Android Studio Meerkat 或更新版本
- JDK 11（Android Studio 内置 JBR 即可）
- Android SDK 36

### 构建运行

```bash
git clone https://github.com/QwenchC/NovelSeek-Ultra.git
cd NovelSeek-Ultra
./gradlew assembleDebug
```

或在 Android Studio 中直接 **Run ▶**。

### 配置 API Key

打开应用 → 右上角 **设置（⚙）** → 填写：

| 字段 | 说明 |
|------|------|
| API Base URL | AI 服务地址，默认 `https://text.pollinations.ai` |
| API Key | 对应服务的密钥（Pollinations 免费模式可留空） |
| 图片 API URL | 图片生成地址，默认 `https://image.pollinations.ai` |

---

## 项目结构

```
app/src/main/java/com/example/novelseek_ultra/
├── MainActivity.kt              # 入口，安装 SplashScreen
├── data/
│   ├── model/                   # 数据模型（Domain, Backup, Kb）
│   ├── ai/                      # AI 服务（AiService, Prompts, KbService）
│   ├── export/                  # PDF / 文本导出
│   ├── AppRepository.kt         # 数据持久化
│   └── SecureStore.kt           # 加密存储
└── ui/
    ├── AppNavigation.kt          # 导航图
    ├── AppViewModel.kt           # 全局 ViewModel
    └── screens/                  # 各功能页面
        ├── HomeScreen.kt
        ├── LongNovelsHomeScreen.kt
        ├── LongNovelScreen.kt
        ├── ProjectScreen.kt       # 短篇小说项目页
        ├── OutlineScreen.kt       # 大纲生成（长/短篇通用）
        ├── CharactersScreen.kt
        ├── EditorScreen.kt
        ├── ExportScreen.kt
        ├── SettingsScreen.kt
        ├── CultivationScreen.kt
        └── RealmInfoDialog.kt
```

---

## 版本历史

### v1.0.0
- 从 NovelSeek-Pro-PC 完整迁移至 Android
- 长/短篇小说 AI 大纲生成与章节写作
- 角色管理、世界观编辑、修炼体系
- 封面 & 宣传图 AI 生成
- 章节封面头图批量生成
- PDF 导出
- Android SplashScreen 启动画面

---

## License

MIT © 2025 QwenchC
