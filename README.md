# NovelSeek Ultra

> 一款基于 AI 的 Android 小说创作助手，由桌面端项目 [NovelSeek-Pro-PC](https://github.com/QwenchC/NovelSeek-Pro-PC) 迁移而来。

---

## 功能概览

### 📖 长篇小说
- 创建与管理多个长篇小说项目
- AI 生成包含剧情弧线、章节结构的完整大纲
- 大纲保存支持选择性覆盖世界观、时间线、角色、章节等字段
- 逐章 AI 写作，自动注入上下文（前章摘要、角色信息、世界观）
- 章节生成页可在当前章节**前 / 后插入新章节**，后续章节序号自动顺延
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
- **新建角色支持一句话 AI 快速生成**：输入一句描述即可自动填写姓名/性别/身份/性格/背景/动机/形象等全部字段；生成时注入大纲、世界观与境界体系，确保角色与小说契合，并自动匹配境界
- 角色信息（背景、外貌、性格等）自动注入章节生成

### 🌍 世界观 & 修炼体系
- 独立世界观信息管理
- 修炼体系（境界）编辑对话框
- 世界观信息注入大纲与章节生成

### 📤 导出
- 章节文本导出为 **PDF** 或纯文本

### 🕘 版本管理（全项目快照）
- 项目页「版本」入口，把**整个项目**（章节正文、大纲、世界观、角色、插图、宣传图等）打包成版本快照，类似 `git commit`
- 支持手动保存版本、AI 覆盖章节前自动备份；可查看历史、一键回退到任意版本（回退前自动备份，可再次撤销）
- 回退后自动清理失效的知识库向量，并标记需重建的章节，支持**仅重建变化章节**的增量重建

### 🤖 小说问答（AI 智能体）
- 项目页 / 章节生成页「问答」入口，用自然语言询问关于**当前版本**小说的任何问题（如"角色现在什么境界""A 和 B 是什么关系""目前发生了哪些事"）
- 混合检索：结构化数据（角色 / 境界 / 关系 / 事件 / 知识条目 / 摘要 / 章节列表）始终可用，配置本地知识库后额外检索正文片段
- AI 只依据检索到的资料作答、可标注章节号，对话按项目持久化保存，支持追问与流式输出

### 🎧 听书
- 底栏独立「听书」入口，可收听任意短篇 / 长篇小说项目
- 语音引擎用 **微软 Edge 免费神经语音**（无需密钥），多种中文音色 + 语速可选
- **分段流式朗读**：章节按句切段，逐段合成并播放，并预取下一段使衔接连贯（切换音色/语速会即时重备下一段）
- 切换章节、章末自动续下一章、N 段进度滑块可拖动跳段；离开页面自动保存进度，重进恢复

### 📦 容器（AI 自演化知识库）
- 项目页 / 章节生成页「容器」入口，新建可按角色 / 按章节分块、或不分块的资料容器
- 勾选**按章更新**：每次保存最新章时，AI 在各分块当前值基础上演进出新条目（无变化则跳过），并记录触发的章节，形成属性进化链
- 勾选**影响章节生成**：容器最新值作为**软引导**注入新章节，避免角色属性等前后矛盾，同时允许自然演进
- 分块随角色 / 章节数量自动增减；值链新旧可见、最新值可手动修改；受版本管理影响，每个分支独立

### 🎨 图片生成引擎
- **Pollinations**（默认）：云端免费图片生成，无需本地环境
- **ComfyUI**：连接局域网内本地运行的 ComfyUI，使用 `z-image-turbo` 工作流，支持高质量本地推理
  - 设置中填写局域网 IP 地址（如 `http://192.168.1.100:8188`）
  - 内置连接测试按钮

### ⚙️ 设置
- 配置 AI 服务的 API Key 与 Base URL（默认接入 Pollinations）
- 选择图片生成引擎：Pollinations（云端）或 ComfyUI（本地）
- 密钥本地加密存储（Android Keystore + `security-crypto`）

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | ViewModel + Repository（单模块） |
| 网络 | OkHttp 4 + SSE 流式输出 |
| 图片生成 | Pollinations API / ComfyUI（本地局域网） |
| 图片加载 | Coil Compose |
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

### 配置 API Key 与图片引擎

打开应用 → 右上角 **设置（⚙）** → 填写：

| 字段 | 说明 |
|------|------|
| API Base URL | AI 文本服务地址，默认 `https://text.pollinations.ai` |
| API Key | 对应服务的密钥（Pollinations 免费模式可留空） |
| 图片 API URL | Pollinations 图片地址，默认 `https://image.pollinations.ai` |
| 图片引擎 | `Pollinations`（云端）或 `ComfyUI`（本地） |
| ComfyUI 地址 | 仅 ComfyUI 模式需填，填局域网 IP，如 `http://192.168.1.100:8188` |

> **ComfyUI 须知**：手机与运行 ComfyUI 的电脑需在同一局域网。应用使用内置 `t2i-lumicreate.json` 工作流（z-image-turbo，不带 LoRA）。

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

### v1.3.0
- 新增 **听书**：底栏听书入口，微软 Edge 免费神经语音，分段流式朗读 + 预取、进度滑块、断点续听
- 新增 **容器（AI 自演化知识库）**：按角色 / 按章节 / 不分块；可按章自动更新、软引导影响生成，受版本管理隔离
- 章节生成页顶栏精简：问答 / 容器 / 境界合并为溢出菜单

### v1.2.0
- 新增 **版本管理**：全项目快照、历史回退、知识库失效增量重建
- 新增 **小说问答 AI 智能体**：基于当前版本的混合检索问答，按项目保存会话
- 新增 **角色 AI 快速生成**：一句话描述自动生成契合小说设定的完整角色
- 新增 **插入新章节**：章节生成页可在当前章前 / 后插入新章节

### v1.1.0
- 新增 **ComfyUI 图片生成引擎**：可在设置中切换 Pollinations / ComfyUI
- ComfyUI 连接测试、局域网地址配置
- 图片自动压缩（ComfyUI 返回大尺寸 PNG，写入状态前自动 JPEG 压缩）

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

MIT © 2026 QwenchC
