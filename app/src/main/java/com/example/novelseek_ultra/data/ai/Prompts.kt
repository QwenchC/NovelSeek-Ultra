package com.example.novelseek_ultra.data.ai

/**
 * Prompt templates ported from the PC Rust backend (`src-tauri/src/generate_*.rs`). These mirror
 * the system + user message pairs the PC build sends to OpenAI-compatible endpoints, so output
 * quality matches the desktop experience.
 */
object Prompts {

    fun outlineSystem(language: String, isLong: Boolean = false): String {
        return if (isLong) {
            if (language == "en") {
                "You are a professional long-form novel story planner. Your task is to create a detailed, " +
                    "structured story plan with world building, characters, plot arcs, and a story timeline. " +
                    "Do NOT produce a fixed chapter-by-chapter outline — instead focus on narrative arcs that " +
                    "can span variable lengths. If existing material is provided, build upon it and keep it " +
                    "consistent. CRITICAL: Output ONLY the structured content starting directly with the first " +
                    "markdown heading. Do NOT include any preamble, greetings, acknowledgements, meta-commentary, " +
                    "or introductory sentences before the first heading."
            } else {
                "你是一位专业的长篇小说策划师。你的任务是为用户创作一份详尽的故事规划，包含世界观、人物设定、" +
                    "时间线和剧情弧线推进计划。不要生成固定章节数的大纲——聚焦于可弹性延伸的剧情弧线。" +
                    "如果提供了已有素材，请在其基础上保持一致并完善扩展。" +
                    "【重要】直接从第一个Markdown标题开始输出，不要在正文内容之前添加任何问候语、客套话、引导语或元评论。"
            }
        } else {
            if (language == "en") {
                "You are an experienced novel outline planner. Output a clean, well-structured Markdown outline. " +
                    "Use ## headings for major sections. CRITICAL: Output ONLY the structured content. " +
                    "Do NOT include any preamble, greetings, acknowledgements, or introductory sentences before the first heading."
            } else {
                "你是经验丰富的网络小说大纲策划。请用结构清晰的 Markdown 输出，## 作为一级章节标题。" +
                    "【重要】直接从第一个Markdown标题开始输出，不要在正文内容之前添加任何问候语、客套话或引导语。"
            }
        }
    }

    fun outlineUser(
        title: String,
        genre: String,
        description: String,
        chapters: Int?,
        extraRequirements: String?,
        language: String,
        isLong: Boolean = false,
        realmContext: String? = null,
        existingWorld: String? = null,
        existingTimeline: String? = null,
        existingArcs: String? = null,
        charactersInfo: String? = null,
        // Continuation mode: ALL the above prompt blocks are still emitted verbatim, then a
        // tail instruction is appended telling the model to pick up where `currentOutline` ends
        // (instead of regenerating from scratch). Used when the user clicks "续写大纲".
        isContinuation: Boolean = false,
        currentOutline: String? = null,
    ): String {
        val req = extraRequirements?.takeIf { it.isNotBlank() }

        // Build the existing-material block (PC's "已有创作素材" section)
        val existingSection = buildString {
            existingWorld?.takeIf { it.isNotBlank() }?.let {
                if (language == "en") appendLine("[Existing World Setting]\n$it")
                else appendLine("【已有世界观设定】\n$it")
            }
            existingTimeline?.takeIf { it.isNotBlank() }?.let {
                if (language == "en") appendLine("[Existing Timeline]\n$it")
                else appendLine("【已有时间线】\n$it")
            }
            existingArcs?.takeIf { it.isNotBlank() }?.let {
                if (language == "en") appendLine("[Existing Plot Arcs]\n$it")
                else appendLine("【已有剧情弧线规划】\n$it")
            }
            realmContext?.takeIf { it.isNotBlank() }?.let {
                if (language == "en") appendLine("[Cultivation Realm System]\n$it")
                else appendLine("【修炼境界体系】\n$it")
            }
            charactersInfo?.takeIf { it.isNotBlank() }?.let {
                if (language == "en") appendLine("[Existing Characters]\n$it")
                else appendLine("【已有角色设定】\n$it")
            }
        }.trimEnd()

        val existingBlock = if (existingSection.isNotBlank()) {
            if (language == "en")
                "\n\n## Existing Material to Build Upon\nThe author has already prepared the following. " +
                    "Please keep these consistent and expand/improve upon them:\n\n$existingSection\n"
            else
                "\n\n## 已有创作素材（请在此基础上完善）\n作者已有以下内容，请保持一致性并在此基础上扩展完善：\n\n$existingSection\n"
        } else ""

        val basePrompt = if (isLong) {
            if (language == "en") {
                buildString {
                    appendLine("Please create a detailed story plan for the following novel:")
                    appendLine()
                    appendLine("Title: $title")
                    appendLine("Genre: $genre")
                    appendLine("Description: $description")
                    if (req != null) appendLine("Additional requirements: $req")
                    append(existingBlock)
                    appendLine()
                    appendLine("Generate the following sections:")
                    appendLine()
                    appendLine("## World Overview")
                    appendLine("Describe the world, era, social structure, and any special rules or systems.")
                    appendLine()
                    appendLine("## Core Characters (3-5 main characters)")
                    appendLine("For each character: name, role, personality, motivation, and arc.")
                    appendLine()
                    appendLine("## Main Story Throughline")
                    appendLine("Summarize the overall journey and central conflict of the novel.")
                    appendLine()
                    appendLine("## Story Timeline")
                    appendLine("List the major events and milestones in chronological order (not tied to specific chapters).")
                    appendLine()
                    appendLine("## Plot Arc Plan (4-7 arcs)")
                    appendLine("For each arc, use EXACTLY this heading format:")
                    appendLine("### Arc N: [Arc Name]")
                    appendLine("- **Core Objective**: What does this arc accomplish?")
                    appendLine("- **Primary Conflict**: The central tension driving this arc")
                    appendLine("- **Key Turning Point**: The pivotal moment that changes things")
                    appendLine("- **Emotional Journey**: The emotional progression for the protagonist")
                    appendLine("- **Ending Beat**: How this arc concludes and what changes")
                    appendLine()
                    appendLine("## Themes & Depth")
                    appendLine("What deeper themes does this story explore?")
                    appendLine()
                    appendLine("IMPORTANT: Begin your response immediately with `## World Overview`. " +
                        "Do not write any greeting, acknowledgement, or introduction before the first heading.")
                }
            } else {
                buildString {
                    appendLine("请为以下长篇小说创建详细的故事策划方案：")
                    appendLine()
                    appendLine("书名：$title")
                    appendLine("题材：$genre")
                    appendLine("简介：$description")
                    if (req != null) appendLine("额外要求：$req")
                    append(existingBlock)
                    appendLine()
                    appendLine("请生成以下内容：")
                    appendLine()
                    appendLine("## 世界观概述")
                    appendLine("描述故事的世界、时代背景、社会结构和特殊规则体系。")
                    appendLine()
                    appendLine("## 核心人物设定（3-5个主要人物）")
                    appendLine("每个人物包含：姓名、身份定位、性格特点、核心动机、人物弧线。")
                    appendLine()
                    appendLine("## 故事主线")
                    appendLine("概述整部小说的核心旅程与中心冲突。")
                    appendLine()
                    appendLine("## 时间线")
                    appendLine("按时间顺序列出故事中的重大事件与转折节点（不与具体章节绑定）。")
                    appendLine()
                    appendLine("## 剧情弧线规划")
                    appendLine("每个弧线请严格使用以下标题格式（将N替换为实际序号，如1、2、3）：")
                    appendLine("### 弧线N：[弧线名称]")
                    appendLine("- **核心目标**：这段剧情要完成什么任务？")
                    appendLine("- **主要冲突**：驱动这段剧情的核心张力")
                    appendLine("- **关键转折**：改变一切的关键时刻")
                    appendLine("- **情感走向**：主角的情感历程演变")
                    appendLine("- **结尾收束**：这段弧线如何结束，带来什么变化")
                    appendLine()
                    appendLine("## 主题与深度")
                    appendLine("这部作品探讨哪些更深层的主题？")
                    appendLine()
                    appendLine("【重要】请直接从 `## 世界观概述` 开始输出，第一个字符即为标题，" +
                        "不要在此之前写任何问候、确认或引导性语句。严格使用上述标题格式，" +
                        "例如 `## 时间线`、`## 剧情弧线规划`。")
                }
            }
        } else {
            // Short novel: chapter-by-chapter outline
            if (language == "en") {
                buildString {
                    appendLine("Plan a novel outline.")
                    appendLine("Title: $title")
                    appendLine("Genre: $genre")
                    appendLine("Synopsis: $description")
                    if (chapters != null) appendLine("Target chapter count: $chapters")
                    if (req != null) appendLine("Extra requirements: $req")
                    append(existingBlock.ifBlank {
                        buildString {
                            realmContext?.takeIf { it.isNotBlank() }?.let { appendLine("\n[Cultivation Realm System]\n$it") }
                            existingWorld?.takeIf { it.isNotBlank() }?.let { appendLine("\n[Existing World Setting]\n$it") }
                            existingTimeline?.takeIf { it.isNotBlank() }?.let { appendLine("\n[Existing Timeline]\n$it") }
                        }
                    })
                    appendLine("\nReturn a complete Markdown outline with the following sections in order:")
                    appendLine()
                    appendLine("## World Overview")
                    appendLine("(Describe the world, era, setting, and any special power systems or rules.)")
                    appendLine()
                    appendLine("## Timeline")
                    appendLine("(Key events in chronological order, independent of chapter numbering.)")
                    appendLine()
                    appendLine("## Core Characters")
                    appendLine("(3–5 main characters; for each: name, role, personality, motivation.)")
                    appendLine()
                    appendLine("## Chapter Outline")
                    appendLine("For EACH chapter use EXACTLY this format (replace N with the actual number):")
                    appendLine()
                    appendLine("### [Chapter Title]")
                    appendLine("- **Plot**: (2–3 sentences summarizing the core events of this chapter)")
                    appendLine("- **Key Scene**: (The most vivid or pivotal scene in this chapter)")
                    appendLine()
                    appendLine("IMPORTANT: Use ONLY the chapter title in the heading — do NOT write \"Chapter N:\" or any numbering prefix in the heading.")
                    appendLine()
                    appendLine("IMPORTANT: Begin your response immediately with `## World Overview`. Do not write any greeting, acknowledgement, or introduction before the first heading.")
                }
            } else {
                buildString {
                    appendLine("请为以下小说生成完整大纲：")
                    appendLine("书名：$title")
                    appendLine("题材：$genre")
                    appendLine("简介：$description")
                    if (chapters != null) appendLine("目标章节数：$chapters")
                    if (req != null) appendLine("额外要求：$req")
                    append(existingBlock.ifBlank {
                        buildString {
                            realmContext?.takeIf { it.isNotBlank() }?.let { appendLine("\n【修炼境界体系】\n$it") }
                            existingWorld?.takeIf { it.isNotBlank() }?.let { appendLine("\n【已有世界观设定】\n$it") }
                            existingTimeline?.takeIf { it.isNotBlank() }?.let { appendLine("\n【已有时间线】\n$it") }
                        }
                    })
                    appendLine("\n请按以下结构输出完整的 Markdown 大纲：")
                    appendLine()
                    appendLine("## 世界观概述")
                    appendLine("（描述世界观、时代背景及特殊体系规则。）")
                    appendLine()
                    appendLine("## 时间线")
                    appendLine("（按时间顺序列出重大事件，不与章节编号绑定。）")
                    appendLine()
                    appendLine("## 核心人物设定")
                    appendLine("（3-5个主要人物，每人包含：姓名、身份定位、性格特点、核心动机。）")
                    appendLine()
                    appendLine("## 章节大纲")
                    appendLine("每章严格使用以下格式（将N替换为实际章节编号）：")
                    appendLine()
                    appendLine("### [章节名称]")
                    appendLine("- **情节**：（2-3句话概述本章核心事件）")
                    appendLine("- **关键场景**：（本章最重要的一幕场景）")
                    appendLine()
                    appendLine("【重要】每章标题只写章节名称本身，不要加「第N章：」或任何编号前缀。直接从 `## 世界观概述` 开始输出，第一个字符即为标题，不要写任何问候、确认或引导语。")
                }
            }
        }

        // Continuation tail — all original prompt blocks above are still present (per user
        // requirement "提示词输入模块要和原先一样不能缺少任何部分"). This appends an explicit
        // "pick up where the existing outline ends" instruction plus the current outline tail.
        if (!isContinuation || currentOutline.isNullOrBlank()) return basePrompt

        val tail = currentOutline.takeLast(3500)
        val continuationBlock = if (language == "en") buildString {
            appendLine()
            appendLine("---")
            appendLine("## CONTINUATION MODE")
            appendLine("The author has ALREADY started this outline (text below). DO NOT rewrite or restate anything that's already there. Pick up exactly where it left off and produce only the NEW content that comes next — keep the same Markdown heading style, the same sections, and the same conventions. If the previous text cuts off mid-section or mid-bullet, finish it cleanly before moving on.")
            appendLine()
            appendLine("### Existing outline tail (last ~3500 chars — context only, do not repeat):")
            appendLine("```")
            appendLine(tail)
            appendLine("```")
            appendLine()
            appendLine("Now output ONLY the continuation. Start with whatever comes naturally next — a new heading, a new bullet, or completing the last unfinished line. No greetings, no recap, no preamble.")
        } else buildString {
            appendLine()
            appendLine("---")
            appendLine("## 续写模式")
            appendLine("作者已经写好了大纲的前半段（下方文本）。请勿重写、复述或重复已有内容，直接从断点处接着写下去，只输出新的内容。保持同样的 Markdown 标题风格、同样的章节结构、同样的写作约定。如果上文在某节或某条目中间断开，请先把那个未完成的部分自然补完，然后再继续。")
            appendLine()
            appendLine("### 已有大纲结尾（最近约 3500 字，仅作衔接参考，不要重复）：")
            appendLine("```")
            appendLine(tail)
            appendLine("```")
            appendLine()
            appendLine("现在请只输出续写部分。直接从最自然的位置开始——一个新标题、一个新条目，或者把上文最后那行没写完的补完。不要任何寒暄、回顾或开场白。")
        }
        return basePrompt + continuationBlock
    }

    fun charsFromOutlineSystem(language: String) = if (language == "en") {
        """You are a professional novel character analyst. Extract ALL major and secondary characters from the provided outline.

For each character, create a COMPREHENSIVE and DETAILED profile — do not summarize, be thorough.

Output ONLY a valid JSON array with no markdown, no code fences, no explanation before or after:
[
  {
    "name": "Full character name",
    "gender": "male / female / unknown",
    "isProtagonist": true or false,
    "role": "Role/title in the story (e.g. protagonist, main antagonist, mentor, rival...)",
    "personality": "Detailed personality traits, behavioral tendencies, strengths and flaws",
    "motivation": "Core desires, goals, driving forces, and what they fear or want to avoid",
    "background": "Detailed backstory: origin, family, past events that shaped them",
    "appearance": "Physical description: build, features, clothing style, distinguishing marks"
  }
]"""
    } else {
        """你是专业的小说角色分析师。请从提供的大纲中提取所有主要角色与重要配角。

对每个角色，请创建详尽完整的角色档案——不要简略概括，要详细展开。

请只输出合法 JSON 数组，不要加任何 markdown、代码块标记、前置说明或结尾说明：
[
  {
    "name": "角色全名",
    "gender": "男 / 女 / 未知",
    "isProtagonist": true 或 false,
    "role": "角色在故事中的身份定位（如主角、主要反派、导师、对手……）",
    "personality": "详细的性格特点、行为倾向、优点与缺陷",
    "motivation": "核心欲望、目标、驱动力，以及他们恐惧或想要避免的事",
    "background": "详细背景故事：出身、家庭、塑造其性格的过去经历",
    "appearance": "外貌描述：体型、五官特征、着装风格、显著标志"
  }
]"""
    }

    fun charsFromOutlineUser(outline: String, language: String) = if (language == "en") {
        "Novel outline:\n\n$outline\n\nNow output the complete JSON character array. Be thorough — each field must be detailed, not just a brief phrase."
    } else {
        "小说大纲：\n\n$outline\n\n请现在输出完整的 JSON 角色数组。每个字段都要详尽，不能只写简短的词语或短语。"
    }

    /**
     * Quick single-character generation from a free-form user brief, grounded in the novel's
     * outline + cultivation-realm system so the result fits the story instead of being generic.
     */
    fun characterFromBriefSystem(language: String) = if (language == "en") {
        """You are a professional novel character designer. Given a short user brief, design ONE character that fits THIS novel — its outline, world building, and cultivation-realm system are provided as reference.

Hard rules:
- The character's role, background, motivation, and realm MUST be consistent with the provided setting. Do NOT invent elements that contradict or are unrelated to the novel.
- "currentRealm" MUST be chosen from the provided realm system (use an exact realm or sub-realm name). If no realm system is provided, leave it empty.
- Honor the user's brief, but adapt it to fit the world.

Output ONLY a single valid JSON object — no markdown, no code fences, no explanation:
{
  "name": "Full name",
  "gender": "male / female / unknown",
  "isProtagonist": true or false,
  "role": "Role/title in the story",
  "personality": "Detailed personality traits, strengths and flaws",
  "motivation": "Core desires, goals, fears",
  "background": "Detailed backstory consistent with the outline",
  "appearance": "Physical description: build, features, clothing, marks",
  "currentRealm": "An exact realm/sub-realm name from the provided system, or empty"
}"""
    } else {
        """你是专业的小说角色设计师。请根据用户的简短描述，设计一个与本小说契合的角色——下方会提供小说大纲、世界观与修炼境界体系作为参考。

【硬性规则】
- 角色的身份、背景、动机、境界必须与提供的设定保持一致，不要创造与小说矛盾或无关的元素。
- "currentRealm" 必须从提供的境界体系中选取（填写一个准确的境界或子境界名称）；若未提供境界体系，则留空。
- 尊重用户的描述，但要将其融入本小说的世界观。

请只输出一个合法 JSON 对象，不要加任何 markdown、代码块标记或说明：
{
  "name": "角色全名",
  "gender": "男 / 女 / 未知",
  "isProtagonist": true 或 false,
  "role": "角色在故事中的身份定位",
  "personality": "详细的性格特点、优点与缺陷",
  "motivation": "核心欲望、目标、恐惧",
  "background": "与大纲设定契合的详细背景故事",
  "appearance": "外貌描述：体型、五官、着装、显著标志",
  "currentRealm": "从提供的境界体系中选取的准确境界/子境界名称，没有则留空"
}"""
    }

    fun characterFromBriefUser(brief: String, context: String, language: String) = if (language == "en") {
        buildString {
            if (context.isNotBlank()) {
                appendLine("[Novel setting reference]")
                appendLine(context)
                appendLine()
            }
            appendLine("[Character the user wants]")
            appendLine(brief)
            appendLine()
            append("Now output the single JSON character object that fits this novel. Every field must be detailed and consistent with the setting above.")
        }
    } else {
        buildString {
            if (context.isNotBlank()) {
                appendLine("【小说设定参考】")
                appendLine(context)
                appendLine()
            }
            appendLine("【用户想要的角色】")
            appendLine(brief)
            appendLine()
            append("请现在输出契合本小说的单个 JSON 角色对象。每个字段都要详尽，并确保与上面的设定一致。")
        }
    }

    fun chapterSystem(language: String) = if (language == "en") {
        """You are a skilled fiction writer.

Hard constraints:
1. Follow world building exactly.
2. Follow timeline consistency exactly.
3. Follow character bible exactly.
4. Keep continuity with previous content when provided.
5. Output plain English prose only (no Markdown).

Style:
- concrete details over vague abstraction
- show, don't tell
- natural dialogue
- controlled pacing and rhythm"""
    } else {
        """你是一位优秀的小说作者。你的任务是根据大纲和章节目标，撰写引人入胜的章节内容。

【最重要的规则 - 必须严格遵守】
1. 世界观一致性：如果用户提供了世界观设定（时代背景、地理环境、社会结构、特殊规则、势力分布），你必须严格遵守，不得创造与设定矛盾的内容。
2. 时间线一致性：如果用户提供了时间线事件，你必须保持时间顺序一致，不得与已发生的历史事件冲突。
3. 角色一致性：如果用户提供了角色设定，你必须严格遵守每个角色的身份、性格、背景和动机，不得擅自更改。

核心原则：
1. 严格按照提供的世界观、时间线、角色设定创作，保持全书一致
2. 叙事连贯性 - 如果提供了前一章内容，必须自然衔接
3. 注重场景描写和画面感
4. 对话要符合人物口吻和性格设定
5. 保持叙述节奏，张弛有度

写作风格：
- 避免AI痕迹（减少"然而"、"不禁"、"心中暗想"等词汇）
- 使用具体细节而非笼统描述
- 展示而非告知（Show, don't tell）
- 保持语言简洁有力
- 不要使用任何markdown格式，输出纯小说正文"""
    }

    fun chapterUser(
        chapterTitle: String,
        outlineGoal: String,
        conflict: String?,
        prevSummary: String?,
        currentContent: String?,
        chapterList: String?,
        charactersInfo: String?,
        worldSetting: String?,
        timeline: String?,
        targetWords: Int,
        isContinuation: Boolean,
        language: String,
        // KB-augmentation context (book/arc summaries, open foreshadowing, RAG-retrieved
        // long-range memory). Emitted as its own labeled block when non-null.
        kbAugmentation: String? = null,
    ): String = buildString {
        if (language == "en") {
            chapterList?.takeIf { it.isNotBlank() }?.let { appendLine("[Novel Chapter Structure]\n$it\n") }
            kbAugmentation?.takeIf { it.isNotBlank() }?.let {
                appendLine("[Knowledge Base Augmentation - book/arc summaries, open foreshadowing, long-range relevant memory]\nUse this to keep continuity over the whole novel. Do NOT quote these blocks verbatim in prose.\n\n$it\n")
            }
            worldSetting?.takeIf { it.isNotBlank() }?.let {
                appendLine("[Important: World Building - follow strictly]\nKeep all generated content consistent with this world setting:\n\n$it\n")
            }
            timeline?.takeIf { it.isNotBlank() }?.let {
                appendLine("[Important: Timeline - follow strictly]\nKeep chronology consistent with these events:\n\n$it\n")
            }
            charactersInfo?.takeIf { it.isNotBlank() }?.let {
                appendLine("[Important: Character Bible - follow strictly]\nKeep identity/personality/background/motivation consistent:\n\n$it\n")
            }
            if (isContinuation) {
                prevSummary?.takeIf { it.isNotBlank() }?.let {
                    appendLine("[Previous chapters — context only. NEVER mention \"Chapter N\", \"the last chapter\", or any structural labels in story prose.]\n$it\n")
                }
                appendLine("Continue writing this chapter.\n\nChapter title: $chapterTitle\nChapter goal: $outlineGoal\n\n[Current tail]\n${currentContent ?: "(none)"}\n\nRequirements:\n1. Continue naturally without repeating existing text.\n2. Keep advancing the plot.\n3. Write around $targetWords words for this continuation.\n4. Keep style and pacing consistent.\n5. Strictly follow world setting, timeline, and character bible.\n6. Output plain English prose only (no Markdown).\n\nContinue directly:")
            } else {
                appendLine("Write this chapter:\n\nChapter title: $chapterTitle\nChapter goal: $outlineGoal\nCore conflict: ${conflict.orEmpty()}")
                prevSummary?.takeIf { it.isNotBlank() }?.let {
                    appendLine("\n[Previous chapter tail — context only. NEVER reference \"Chapter N\", \"the last chapter\", or structural labels in your prose.]\n$it\n")
                }
                appendLine("\nRequirements:\n1. Write around $targetWords words.\n2. Strictly follow world setting, timeline, and character bible.\n3. Strong scene immersion and visual details.\n4. Natural dialogues consistent with character voices.\n5. If previous chapter context exists, connect naturally.\n6. Output plain English prose only (no Markdown).\n7. NEVER include \"Chapter N\", \"in the last chapter\", or any structural meta-labels in your prose.\n\nStart writing the chapter content now:")
            }
            charactersInfo?.takeIf { it.isNotBlank() }?.let {
                appendLine("\n\n[PRE-WRITE CHARACTER LOCK — immutable, takes priority if anything above contradicts]\n$it\n\nBegin writing the chapter now:")
            }
        } else {
            chapterList?.takeIf { it.isNotBlank() }?.let { appendLine("【小说章节结构】\n$it\n") }
            kbAugmentation?.takeIf { it.isNotBlank() }?.let {
                appendLine("【知识库增强 — 全书梗概 / 当前弧线进度 / 未回收伏笔 / 长程相关记忆】\n以下信息用于保持全书连贯，请用作背景参考，不要在正文中原文引用这些 block 内容。\n\n$it\n")
            }
            worldSetting?.takeIf { it.isNotBlank() }?.let {
                appendLine("【重要：世界观设定 - 必须严格遵守】\n以下是本小说的世界观设定，生成内容时必须保持一致，不得与设定冲突：\n\n$it\n")
            }
            timeline?.takeIf { it.isNotBlank() }?.let {
                appendLine("【重要：时间线事件 - 必须严格遵守】\n以下是本小说的时间线，生成内容时必须保持时间顺序一致，不得与已发生的事件冲突：\n\n$it\n")
            }
            charactersInfo?.takeIf { it.isNotBlank() }?.let {
                appendLine("【重要：角色设定 - 必须严格遵守】\n以下是本小说的角色设定，生成内容时必须保持角色身份、性格、背景完全一致，不得擅自更改：\n\n$it\n")
            }
            if (isContinuation) {
                prevSummary?.takeIf { it.isNotBlank() }?.let {
                    appendLine("【前几章结尾内容（仅供衔接参考，正文中绝对不能出现\"第X章\"或任何章节标记）】\n$it\n")
                }
                appendLine("请续写以下小说章节内容。\n\n章节标题：$chapterTitle\n本章目标：$outlineGoal\n\n【已有内容结尾】\n${currentContent ?: "（无）"}\n\n请注意：\n1. 自然衔接上文，不要重复已有内容\n2. 继续推进剧情发展\n3. 本次续写约${targetWords}字\n4. 保持文风和节奏一致\n5. 【重要】必须严格遵守上述世界观设定、时间线和角色设定，不得与之冲突\n6. 不要使用markdown格式，直接输出小说正文\n\n请直接续写内容，不要添加任何说明或标记：")
            } else {
                appendLine("请撰写以下章节：\n\n章节标题：$chapterTitle\n本章目标：$outlineGoal\n核心冲突：${conflict.orEmpty()}")
                prevSummary?.takeIf { it.isNotBlank() }?.let {
                    appendLine("\n【上章结尾内容（仅供行文衔接，绝对不要在正文中提及「第X章」或任何章节标记）】（请自然衔接，不要重复）\n$it\n")
                }
                appendLine("\n写作要求：\n1. 本次生成约${targetWords}字\n2. 【重要】必须严格遵守世界观设定、时间线和角色设定，不得与之冲突\n3. 场景描写要有画面感\n4. 对话要自然生动，符合角色性格\n5. 如果有前一章内容，请自然衔接，不要突兀\n6. 不要使用markdown格式，直接输出小说正文\n7. 【严禁】正文中绝对不能出现「第X章」、「上一章」、「章节」等元叙事信息，那些只是内部参考标记，不属于故事内容")
            }
            charactersInfo?.takeIf { it.isNotBlank() }?.let {
                appendLine("\n\n【动笔前强制核对 — 以下角色设定绝对不可更改，如与上文任何内容矛盾，以此处为准】\n$it\n\n立即开始写作，直接输出正文，不要添加任何说明：")
            }
        }
    }

    fun chapterOutlineSystem(language: String) = if (language == "en") {
        "You are a creative writing assistant helping an author plan the next chapter of their novel. Output ONLY the three requested fields in the exact format, nothing else."
    } else {
        "你是创作助手，协助小说作者规划下一章节。只输出要求格式的三个字段，不要任何额外文字或解释。"
    }

    fun chapterOutlineUser(
        previousSummary: String,
        arcContext: String,
        chapterIndex: Int,
        worldSetting: String?,
        charactersInfo: String?,
        userRequirements: String,
        language: String,
    ): String = if (language == "en") buildString {
        appendLine("Plan Chapter $chapterIndex for this novel.")
        worldSetting?.takeIf { it.isNotBlank() }?.let { appendLine("\n[World Setting & Realm System]\n$it") }
        charactersInfo?.takeIf { it.isNotBlank() }?.let { appendLine("\n[Key Characters]\n$it") }
        if (arcContext.isNotBlank()) appendLine("\n$arcContext")
        appendLine("\nRecent chapter endings:\n${if (previousSummary.isBlank()) "(Opening chapter — no prior content)" else previousSummary}")
        appendLine("\nAuthor's requirements for this chapter:\n${if (userRequirements.isBlank()) "Write a transitional chapter that naturally advances the story and maintains tension." else userRequirements}")
        append("\nOutput EXACTLY three lines, no extra text:\nTitle: [concise chapter title]\nGoal: [what happens this chapter, 1-2 sentences]\nConflict: [the key tension or conflict, 1 sentence]")
    } else buildString {
        appendLine("为这部小说规划第${chapterIndex}章。")
        worldSetting?.takeIf { it.isNotBlank() }?.let { appendLine("\n【世界观与修炼体系】\n$it") }
        charactersInfo?.takeIf { it.isNotBlank() }?.let { appendLine("\n【主要角色设定】\n$it") }
        if (arcContext.isNotBlank()) appendLine("\n$arcContext")
        appendLine("\n前几章的结尾内容：\n${if (previousSummary.isBlank()) "（开篇章节，暂无前文内容）" else previousSummary}")
        appendLine("\n作者对本章的需求/期望：\n${if (userRequirements.isBlank()) "安排一个过渡性章节，自然推进剧情，保持张力，为后续伏笔做铺垫。" else userRequirements}")
        append("\n请严格输出以下三行，不要任何额外文字：\n标题：[简洁的章节名称]\n目标：[本章发生什么，1-2句话]\n冲突：[关键张力或冲突，1句话]")
    }

    fun arcMiniOutlineSystem(language: String) = if (language == "en") {
        "You are an expert novel editor. Break a story arc into a clear, chapter-by-chapter plan. Output ONLY the chapter list in exact format — no extra text."
    } else {
        "你是一位经验丰富的小说策划编辑，擅长将宏大的剧情弧线拆解为可操作的章节计划。请严格按要求格式输出，不要添加任何额外说明。"
    }

    fun arcMiniOutlineUser(
        projectTitle: String,
        projectOutline: String,
        arcTitle: String,
        arcSummary: String,
        chapterCount: Int,
        startChapterNumber: Int,
        prevChaptersContext: String,
        charactersInfo: String?,
        language: String,
    ): String {
        val endNum = startChapterNumber + chapterCount - 1
        val prevEnd = if (startChapterNumber > 1) startChapterNumber - 1 else 0
        val charsBlock = if (!charactersInfo.isNullOrBlank()) {
            if (language == "en") "\n\n[Key Characters]\n$charactersInfo" else "\n\n【主要角色】\n$charactersInfo"
        } else ""
        return if (language == "en") {
            "Novel: $projectTitle\n\nOverall outline:\n${if (projectOutline.isBlank()) "(No overall outline provided)" else projectOutline}$charsBlock\n\nCurrent story arc: \"$arcTitle\"\nArc description:\n${if (arcSummary.isBlank()) "(No arc description)" else arcSummary}\n\nPrevious chapters context (the story so far):\n${if (prevChaptersContext.isBlank()) "(Opening arc — no prior chapters)" else prevChaptersContext}\n\nTask: Plan exactly $chapterCount NEW chapters for the \"$arcTitle\" arc.\nThe existing story already has chapters up to Chapter $prevEnd. Your plan must number chapters starting from Chapter $startChapterNumber through Chapter $endNum.\n\nOutput format (one line per chapter, exactly $chapterCount lines, starting at Chapter $startChapterNumber):\nChapter $startChapterNumber: [title] — [core event and goal, 1-2 sentences]\nChapter ${startChapterNumber + 1}: [title] — [core event and goal, 1-2 sentences]\n...\n\nRules:\n- Number chapters consecutively from $startChapterNumber to $endNum\n- Each chapter must causally follow the previous\n- The opening chapter should connect naturally from prior content\n- The final chapter should conclude this arc's main conflict and plant seeds for the next arc\n- Be specific and story-driven\n- Output ONLY the chapter list, no intro or explanation"
        } else {
            "小说信息：\n标题：$projectTitle\n总纲概述：\n${if (projectOutline.isBlank()) "（暂无总纲）" else projectOutline}$charsBlock\n\n当前剧情弧线：《$arcTitle》\n弧线描述：\n${if (arcSummary.isBlank()) "（暂无弧线描述）" else arcSummary}\n\n前置章节简况（已有内容）：\n${if (prevChaptersContext.isBlank()) "（首个弧线，暂无前置章节）" else prevChaptersContext}\n\n任务：请为【$arcTitle】这一剧情弧线规划${chapterCount}章的详细章节安排。\n小说目前已有第1章至第${prevEnd}章，本次规划应从第${startChapterNumber}章开始，到第${endNum}章结束，共${chapterCount}章。\n\n输出格式（严格按此格式，每章一行，共${chapterCount}行，从第${startChapterNumber}章开始编号）：\n第${startChapterNumber}章：[章节标题] — [本章核心事件与目标，1-2句话]\n第${startChapterNumber + 1}章：[章节标题] — [本章核心事件与目标，1-2句话]\n...\n\n要求：\n- 章节编号从第${startChapterNumber}章连续递增至第${endNum}章，不得从第1章重新开始\n- 章节之间要有连贯的因果关系和剧情推进\n- 开篇要承接前置章节，结尾要为下一弧线或全书收束埋下伏笔\n- 每章目标明确，核心事件清晰\n- 只输出章节计划，不要任何其他文字"
        }
    }

    fun revisionSystem(language: String) = if (language == "en") {
        "You are a precise prose editor. Rewrite the given passage while preserving its meaning. " +
            "Keep the same point-of-view and tone. Output ONLY the rewritten text."
    } else {
        "你是一名严谨的小说润色编辑。请在保留原意的前提下重写给定段落，保持视角与语气一致。" +
            "只输出重写后的正文，不要任何解释。"
    }

    fun revisionUser(text: String, goals: String?, language: String) = buildString {
        if (language == "en") {
            if (!goals.isNullOrBlank()) appendLine("Revision goals: $goals")
            appendLine("Passage:")
        } else {
            if (!goals.isNullOrBlank()) appendLine("润色要求：$goals")
            appendLine("待润色段落：")
        }
        append(text)
    }

    fun characterAppearanceSystem(language: String) = if (language == "en") {
        "You design vivid character appearances for novels. Reply with strict JSON of the form " +
            "{\"appearance\":\"<3-5 sentence visual description in prose>\",\"image_prompt\":" +
            "\"<concise English prompt for a text-to-image model: subject, attire, mood, lighting>\"}. " +
            "No markdown, no explanation."
    } else {
        "你是小说人物形象设计师。请回复严格的 JSON：" +
            "{\"appearance\":\"<3-5 句中文外形描述>\",\"image_prompt\":\"<英文文生图提示词，包含主体、服饰、神情、光线>\"}。" +
            "不要包含 Markdown 或额外解释。"
    }

    fun characterAppearanceUser(
        name: String,
        role: String?,
        personality: String?,
        background: String?,
        motivation: String?,
        style: String?,
        language: String,
    ): String = buildString {
        if (language == "en") {
            appendLine("Design appearance for character.")
            appendLine("Name: $name")
            role?.takeIf { it.isNotBlank() }?.let { appendLine("Role: $it") }
            personality?.takeIf { it.isNotBlank() }?.let { appendLine("Personality: $it") }
            background?.takeIf { it.isNotBlank() }?.let { appendLine("Background: $it") }
            motivation?.takeIf { it.isNotBlank() }?.let { appendLine("Motivation: $it") }
            style?.takeIf { it.isNotBlank() }?.let { appendLine("Visual style: $it") }
        } else {
            appendLine("请为以下角色设计外形：")
            appendLine("姓名：$name")
            role?.takeIf { it.isNotBlank() }?.let { appendLine("身份：$it") }
            personality?.takeIf { it.isNotBlank() }?.let { appendLine("性格：$it") }
            background?.takeIf { it.isNotBlank() }?.let { appendLine("背景：$it") }
            motivation?.takeIf { it.isNotBlank() }?.let { appendLine("动机：$it") }
            style?.takeIf { it.isNotBlank() }?.let { appendLine("视觉风格：$it") }
        }
    }

    fun portraitPromptSystem(language: String) = if (language == "en") {
        "Output ONE concise English text-to-image prompt for a portrait of the given character. " +
            "Reply with strict JSON {\"image_prompt\":\"...\"}. No markdown."
    } else {
        "请输出该角色的肖像的一条简洁英文文生图提示词。严格 JSON：{\"image_prompt\":\"...\"}，不要 Markdown。"
    }

    fun plotArcSystem(language: String) = if (language == "en") {
        "You plan story arcs for serialized novels. Reply with strict JSON {\"title\",\"summary\"," +
            "\"chapter_count\":<int>,\"mini_outline\":\"<Markdown outline of beats>\"}. No prose " +
            "outside JSON."
    } else {
        "你为连载长篇小说规划剧情弧线。严格 JSON：{\"title\",\"summary\",\"chapter_count\":<整数>," +
            "\"mini_outline\":\"<Markdown 弧线节奏小纲>\"}，不要 JSON 之外的任何文字。"
    }

    fun plotArcUser(
        userIdea: String,
        bookTitle: String,
        bookDescription: String?,
        bookOutline: String?,
        existingArcsSummary: String?,
        realmSystemContext: String?,
        charactersSummary: String?,
        targetChapterCount: Int?,
        language: String,
    ): String = buildString {
        if (language == "en") {
            appendLine("Idea: $userIdea")
            appendLine("Book: $bookTitle")
            bookDescription?.takeIf { it.isNotBlank() }?.let { appendLine("Synopsis: $it") }
            bookOutline?.takeIf { it.isNotBlank() }?.let { appendLine("Outline excerpt:\n$it") }
            existingArcsSummary?.takeIf { it.isNotBlank() }?.let { appendLine("Existing arcs:\n$it") }
            realmSystemContext?.takeIf { it.isNotBlank() }?.let { appendLine("Cultivation system:\n$it") }
            charactersSummary?.takeIf { it.isNotBlank() }?.let { appendLine("Characters:\n$it") }
            targetChapterCount?.let { appendLine("Target chapter count: $it") }
        } else {
            appendLine("用户想法：$userIdea")
            appendLine("作品：$bookTitle")
            bookDescription?.takeIf { it.isNotBlank() }?.let { appendLine("简介：$it") }
            bookOutline?.takeIf { it.isNotBlank() }?.let { appendLine("大纲节选：\n$it") }
            existingArcsSummary?.takeIf { it.isNotBlank() }?.let { appendLine("已有弧线：\n$it") }
            realmSystemContext?.takeIf { it.isNotBlank() }?.let { appendLine("修炼体系：\n$it") }
            charactersSummary?.takeIf { it.isNotBlank() }?.let { appendLine("角色：\n$it") }
            targetChapterCount?.let { appendLine("目标章节数：$it") }
        }
    }

    // ── KB v2.0: chapter / arc / book summaries ─────────────────────────────

    fun chapterSummarySystem(language: String) = if (language == "en") {
        "You are a literary editor. Produce a concise (~150 chars) plot summary of the given chapter — capture WHO did WHAT, KEY consequences, and any unresolved hooks. No commentary, no markdown."
    } else {
        "你是文学编辑。请为给定章节生成约 150 字的精炼剧情摘要，要点：主要角色做了什么、关键后果、是否埋下未解决的伏笔。不要任何评论或 Markdown 标记。"
    }

    fun chapterSummaryUser(chapterTitle: String, chapterText: String, language: String) =
        if (language == "en") "Chapter title: $chapterTitle\n\nChapter text:\n$chapterText\n\nOutput the summary only:"
        else "章节标题：$chapterTitle\n\n章节正文：\n$chapterText\n\n只输出摘要："

    fun arcSummarySystem(language: String) = if (language == "en") {
        "You are a literary editor. Synthesize a coherent arc-level synopsis (~300 chars) from the listed chapter summaries. Highlight the arc's central conflict, key turning points, and unresolved threads."
    } else {
        "你是文学编辑。请从给定的章节摘要列表中提炼一段约 300 字的弧线级别梗概，突出弧线的核心冲突、关键转折点和未解的伏笔线。"
    }

    fun arcSummaryUser(arcTitle: String, arcDescription: String?, chapterSummaries: List<String>, language: String) =
        if (language == "en") buildString {
            appendLine("Arc: $arcTitle")
            arcDescription?.takeIf { it.isNotBlank() }?.let { appendLine("Arc description: $it") }
            appendLine()
            appendLine("Chapter summaries (in order):")
            chapterSummaries.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            append("\nNow output the arc summary only:")
        } else buildString {
            appendLine("弧线名：$arcTitle")
            arcDescription?.takeIf { it.isNotBlank() }?.let { appendLine("弧线描述：$it") }
            appendLine()
            appendLine("各章摘要（按时序）：")
            chapterSummaries.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            append("\n现在请只输出弧线梗概：")
        }

    fun bookSummarySystem(language: String) = if (language == "en") {
        "You are a literary editor. Produce a tight whole-book synopsis (~500 chars) from the listed arc/chapter summaries. Highlight the central throughline, the protagonist's journey, the world's stakes, and open threads."
    } else {
        "你是文学编辑。请从给定的弧线/章节摘要中提炼一段约 500 字的全书梗概，要点：主线、主角成长弧、世界利害、尚未回收的伏笔线。"
    }

    fun bookSummaryUser(bookTitle: String, bookDescription: String?, layers: List<String>, language: String) =
        if (language == "en") buildString {
            appendLine("Book title: $bookTitle")
            bookDescription?.takeIf { it.isNotBlank() }?.let { appendLine("Author synopsis: $it") }
            appendLine()
            appendLine("Existing arc / chapter summaries (oldest first):")
            layers.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            append("\nNow output the book synopsis only:")
        } else buildString {
            appendLine("书名：$bookTitle")
            bookDescription?.takeIf { it.isNotBlank() }?.let { appendLine("作者简介：$it") }
            appendLine()
            appendLine("已有弧线/章节摘要（按时序，旧→新）：")
            layers.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
            append("\n现在请只输出全书梗概：")
        }

    // ── KB v2.1: entity extraction ──────────────────────────────────────────

    fun entityExtractionSystem(language: String) = if (language == "en") {
        """You analyze novel chapters and extract structured entities. Output ONLY a valid JSON object (no markdown, no preamble) of the form:
{
  "characters":     [{"name": "...", "aliases": ["..."], "summary": "1-2 sentence role/action this chapter"}],
  "foreshadowing":  [{"name": "concise label of the foreshadowed thread", "summary": "what was planted and why it matters", "status": "open" | "paid_off"}],
  "locations":      [{"name": "...", "summary": "what happened here"}],
  "events":         [{"name": "concise event label", "summary": "what happened"}],
  "items":          [{"name": "...", "summary": "significance"}]
}
Any category may be omitted or be an empty array. Be selective — only entities that meaningfully advance plot or character."""
    } else {
        """你是小说章节分析师。请从给定章节中抽取结构化实体。只输出合法 JSON（无 markdown 标记、无前置说明），格式：
{
  "characters":     [{"name": "角色名", "aliases": ["别名"], "summary": "本章中此角色做了什么 / 担任什么角色，1-2 句话"}],
  "foreshadowing":  [{"name": "伏笔线的简洁标签", "summary": "埋下了什么、为何重要", "status": "open" 或 "paid_off"}],
  "locations":      [{"name": "地名", "summary": "此处发生了什么"}],
  "events":         [{"name": "事件标签", "summary": "事件经过"}],
  "items":          [{"name": "物品名", "summary": "重要性"}]
}
任何分类可省略或为空数组。要克制——只抽取真正推进剧情或人物的实体。"""
    }

    fun entityExtractionUser(
        chapterTitle: String,
        chapterText: String,
        knownCharacterNames: List<String>,
        language: String,
    ): String = buildString {
        if (language == "en") {
            if (knownCharacterNames.isNotEmpty()) {
                appendLine("Known character names (prefer these as canonical, treat near-matches as aliases): ${knownCharacterNames.joinToString(", ")}")
                appendLine()
            }
            appendLine("Chapter title: $chapterTitle")
            appendLine()
            appendLine("Chapter text:")
            appendLine(chapterText)
            appendLine()
            append("Output the JSON only:")
        } else {
            if (knownCharacterNames.isNotEmpty()) {
                appendLine("已知角色名（请优先使用这些作为正名，相近写法视为别名）：${knownCharacterNames.joinToString("、")}")
                appendLine()
            }
            appendLine("章节标题：$chapterTitle")
            appendLine()
            appendLine("章节正文：")
            appendLine(chapterText)
            appendLine()
            append("只输出 JSON：")
        }
    }

    // ── Illustration prompt builder ─────────────────────────────────────────
    // PC parity: the Rust backend's `generate_illustration_prompt` command takes the selected
    // paragraph text + an optional style hint and returns a single concise English image prompt
    // tailored for a text-to-image model (Pollinations / flux / zimage). We reproduce the same
    // contract with the OpenAI-compatible chat endpoint we already use.

    fun illustrationPromptSystem(language: String) = if (language == "en") {
        "You craft concise English text-to-image prompts for a novelist's chapter illustration. " +
            "Output ONE single line — no JSON, no markdown, no quotes — that captures the visual " +
            "essence of the scene: subject, action, mood, lighting, composition, style. Keep it " +
            "under 70 words. If a style hint is provided, weave it in naturally. If a character " +
            "description is provided, translate its exact visual features (face, hair, age, build, " +
            "attire) into the prompt so the figure stays consistent across illustrations."
    } else {
        "你为小说章节插图编写英文文生图提示词。只输出**一行**英文 prompt——不要 JSON、不要 Markdown、" +
            "不要引号——抓住场景的视觉精髓：主体、动作、氛围、光线、构图、画风。控制在 70 词以内。" +
            "如果给了 style 提示，请自然融入。如果给了指定角色的外貌描述，请把其确切外貌特征（脸型、" +
            "发型、年龄、体型、服饰）翻译进英文 prompt，使该角色在多张插图中保持一致。"
    }

    fun illustrationPromptUser(
        paragraphText: String,
        style: String?,
        language: String,
        charactersInfo: String? = null,
    ) = buildString {
        if (language == "en") {
            appendLine("Scene text:")
            appendLine(paragraphText)
            if (!style.isNullOrBlank()) {
                appendLine()
                appendLine("Style hint: $style")
            }
            append(characterConsistencyBlock(charactersInfo, language))
            append("\nOutput the image prompt now (one line, English only):")
        } else {
            appendLine("场景文本：")
            appendLine(paragraphText)
            if (!style.isNullOrBlank()) {
                appendLine()
                appendLine("画风提示：$style")
            }
            append(characterConsistencyBlock(charactersInfo, language))
            append("\n现在输出英文文生图 prompt（仅一行）：")
        }
    }

    /**
     * Shared "depict these exact characters" block for image-prompt builders (consistency).
     * [charactersInfo] is a pre-formatted list (one "- 名字：外貌" line per selected character);
     * 0 characters → empty block (no constraint).
     */
    private fun characterConsistencyBlock(charactersInfo: String?, language: String): String {
        if (charactersInfo.isNullOrBlank()) return ""
        return if (language == "en") buildString {
            appendLine(); appendLine()
            appendLine("[Character consistency — the figures below MUST appear as these exact characters; keep each one's visual features identical]")
            appendLine(charactersInfo.take(1200))
        } else buildString {
            appendLine(); appendLine()
            appendLine("【人物一致性 —— 画面中的人物必须是以下指定角色，每个角色的外貌特征严格保持不变】")
            appendLine(charactersInfo.take(1200))
        }
    }

    // ── Chapter promo prompt builder ─────────────────────────────────────────
    // Given chapter content, ask the text model for both an image prompt (English,
    // for the 3:1 chapter-header banner) AND a short chapter summary for readers.
    // Returns JSON: { "image_prompt": "...", "summary": "..." }

    fun chapterPromoSystem(language: String) = if (language == "en") {
        "You are a professional novelist's assistant. When given a chapter, return ONLY a JSON object " +
        "with exactly two fields: \"image_prompt\" (a concise English text-to-image prompt for a " +
        "cinematic 3:1 wide banner image — describe the scene's main subject, mood, lighting, " +
        "composition, style; max 60 words) and \"summary\" (a short 1–3 sentence reader-facing " +
        "chapter summary). No markdown, no extra text, just the JSON object."
    } else {
        "你是专业小说助手。给定章节内容，仅返回一个 JSON 对象，包含两个字段：" +
        "\"image_prompt\"（英文文生图提示词，用于 3:1 横幅封面图，描述主体、氛围、光线、构图、风格，不超过 60 词）和 " +
        "\"summary\"（面向读者的 1–3 句中文章节摘要）。不要 Markdown，不要多余文字，只输出 JSON 对象。"
    }

    fun chapterPromoUser(chapterTitle: String, chapterContent: String, style: String?, language: String) = buildString {
        if (language == "en") {
            appendLine("Chapter title: $chapterTitle")
            if (!style.isNullOrBlank()) appendLine("Image style hint: $style")
            appendLine()
            appendLine("Chapter content (excerpt):")
            append(chapterContent.take(2000))
        } else {
            appendLine("章节标题：$chapterTitle")
            if (!style.isNullOrBlank()) appendLine("图片风格提示：$style")
            appendLine()
            appendLine("章节内容（节选）：")
            append(chapterContent.take(2000))
        }
    }

    // ── Project cover prompt builder ─────────────────────────────────────────

    fun projectCoverSystem(language: String) = if (language == "en") {
        "You are a professional book cover designer's assistant. When given a novel description, " +
        "return ONLY a single English text-to-image prompt for a portrait book cover image. " +
        "Describe: main subject, mood, colors, lighting, composition, art style. Max 70 words. " +
        "If a character description is provided, the cover's main figure must be that exact " +
        "character with their precise visual features (face, hair, age, build, attire). " +
        "No markdown, no explanation, just the prompt on one line."
    } else {
        "你是专业书籍封面设计助手。给定小说描述，仅返回一行英文文生图 prompt，用于竖向书籍封面图。" +
        "描述：主体、氛围、色调、光线、构图、艺术风格。不超过 70 词。若给了指定角色的外貌描述，" +
        "封面主体人物必须是该角色，并保持其确切外貌特征（脸型、发型、年龄、体型、服饰）。" +
        "不要 Markdown，不要解释，只输出一行 prompt。"
    }

    fun projectCoverUser(
        title: String,
        description: String?,
        outline: String?,
        style: String?,
        language: String,
        charactersInfo: String? = null,
    ) = buildString {
        if (language == "en") {
            appendLine("Novel title: $title")
            if (!description.isNullOrBlank()) { appendLine(); appendLine("Description: ${description.take(500)}") }
            if (!outline.isNullOrBlank()) { appendLine(); appendLine("Outline excerpt: ${outline.take(1000)}") }
            if (!style.isNullOrBlank()) { appendLine(); appendLine("Style hint: $style") }
            append(characterConsistencyBlock(charactersInfo, language))
            append("\nGenerate the cover image prompt now (one line, English only):")
        } else {
            appendLine("小说标题：$title")
            if (!description.isNullOrBlank()) { appendLine(); appendLine("简介：${description.take(500)}") }
            if (!outline.isNullOrBlank()) { appendLine(); appendLine("大纲节选：${outline.take(1000)}") }
            if (!style.isNullOrBlank()) { appendLine(); appendLine("风格提示：$style") }
            append(characterConsistencyBlock(charactersInfo, language))
            append("\n现在输出封面文生图 prompt（仅一行英文）：")
        }
    }
}
