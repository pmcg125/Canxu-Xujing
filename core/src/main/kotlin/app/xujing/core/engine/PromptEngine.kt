package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import kotlin.random.Random

data class OutputResult(val text: String, val variables: JsonObject, val diagnostics: List<String>)

/** Pure request composition. The caller commits returned variables/worldState after generation. */
object PromptEngine {
    fun build(
        state: AppState,
        session: ChatSession,
        targetRoleId: String? = null,
        retrievedMemory: List<String> = emptyList(),
        mode: String = "normal",
    ): PromptBuild {
        val diagnostics = mutableListOf<String>()
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val selected = roles.find { it.id == targetRoleId } ?: roles.firstOrNull()
        if (selected == null) diagnostics += "当前会话没有可用角色。"
        if (targetRoleId != null && selected?.id != targetRoleId) diagnostics += "指定角色不存在于当前会话，使用首个成员。"
        val connectionId = (if (mode == "summary") session.summaryConnectionId else session.connectionId)
            ?: session.connectionId ?: state.preferences.defaultConnectionId
        val connection = state.connections.find { it.id == connectionId }
            ?.let { GenerationSettings.connection(state, session, it, usePreset = mode != "summary") }
        val presetDocument = state.library.find { it.enabled && it.kind == "preset" && it.id == session.presetId }
        val preset = presetDocument?.data?.let(::unwrap) ?: EmptyObject
        val regenerate = mode in setOf("regenerate", "swipe")
        val historySource = if (regenerate && session.messages.lastOrNull()?.role == "assistant") session.messages.dropLast(1) else session.messages
        val variableDefaults = roles.fold(EmptyObject) { vars, role ->
            val data = roleData(role)
            val extensions = data["extensions"].objectOrEmpty()
            val initial = extensions.first("variables", "initial_variables", "initialVariables").objectOrEmpty()
            mergeJson(vars, initial)
        }
        val initial = mergeJson(variableDefaults, CandidateSnapshots.beforeGeneration(session, regenerate))
        val seed = (session.id + ":" + historySource.lastOrNull()?.id.orEmpty() + ":" + selected?.id.orEmpty()).hashCode()
        val random = Random(seed)
        val macroSession = session.copy(messages = historySource)
        val macros = MacroProcessor(state, macroSession, selected, initial, diagnostics, random)
        val regex = RegexProcessor(state, session, roles, macros, diagnostics)
        val contextTokens = connection?.contextTokens?.coerceAtLeast(256) ?: 16384
        val world = WorldbookEngine.activate(state, macroSession, roles, macros, contextTokens, random, mode, selected)
        diagnostics += world.diagnostics
        fun worldText(position: String) = regex.apply(world.at(position), TextStage.WORLDINFO)
        val persona = session.persona.ifBlank {
            val id = session.extra.str("personaId", "persona_id")
            state.library.find { it.enabled && it.kind == "persona" && it.id == id }?.data?.let { data ->
                if (data is JsonPrimitive) data.content else unwrap(data).str("description", "content", "persona", "text")
            }.orEmpty().ifBlank { state.preferences.persona }
        }
        val group = if (roles.size > 1) buildString {
            append("这是多角色扮演。角色之间只知道自己经历、被告知或能观察到的信息。保持各自目标、说话方式与物品归属。\n")
            if (mode != "summary") append("本轮仅由【").append(selected?.name.orEmpty()).append("】回复，不替用户或其他成员决定行动。\n")
            roles.forEach { role ->
                append("\n<角色 name=\"").append(role.name.replace("\"", "&quot;")).append("\">\n")
                append(role.description).append('\n').append(role.personality).append('\n').append(role.scenario).append("\n</角色>\n")
            }
        } else ""
        val summary = session.summary.takeIf(String::isNotBlank)?.let {
            "<历史剧情摘要>\n$it\n</历史剧情摘要>\n摘要记录过去事件；与近期原始消息冲突时优先使用近期原文，不把背景设定视为已发生事件。"
        }.orEmpty()
        val memory = (if (mode == "summary") emptyList() else retrievedMemory).filter(String::isNotBlank).joinToString("\n\n").take(session.extra.num("memoryMaxChars", default = 8000).coerceIn(0, 100_000))
            .takeIf(String::isNotBlank)?.let { "<检索到的旧剧情>\n$it\n</检索到的旧剧情>\n这些是过去的片段，注意说话人和时间；当前消息与明确状态优先。" }.orEmpty()
        val activeVariables = if (mode != "summary" && macros.variables.isNotEmpty() && session.extra.flag("injectVariables", default = true)) {
            "<当前剧情变量>\n${macros.variables}\n</当前剧情变量>"
        } else ""
        val styleIds = session.extra.first("styleIds", "style_ids", "styleId", "style_id").strings()
        val style = state.library.filter { it.enabled && it.kind == "style" && (it.id in styleIds || it.name in styleIds) }
            .joinToString("\n\n") { document -> if (document.data is JsonPrimitive) document.data.content else unwrap(document.data).str("content", "prompt", "text", "description") }
        val examples = listOf(worldText("examples_before"), selected?.examples.orEmpty(), worldText("examples_after")).filter(String::isNotBlank).joinToString("\n\n")
        val blocks = linkedMapOf(
            "main" to selected?.systemPrompt.orEmpty().ifBlank { "请根据角色设定和已发生的剧情进行连贯扮演，保持人物性格、关系、世界设定与叙事视角一致。" },
            "worldInfoBefore" to worldText("before"),
            "charDescription" to if (roles.size > 1) "" else selected?.description.orEmpty(),
            "charPersonality" to if (roles.size > 1) "" else selected?.personality.orEmpty(),
            "scenario" to if (roles.size > 1) "" else selected?.scenario.orEmpty(),
            "personaDescription" to persona,
            "group_base" to group,
            "worldInfoAfter" to worldText("after"),
            "dialogueExamples" to examples,
            "summary" to summary,
            "memory" to memory,
            "variables" to activeVariables,
            "style" to style,
            "chatHistory" to "",
            "jailbreak" to selected?.postHistoryInstructions.orEmpty(),
        )
        val maxHistory = session.extra.num("maxHistoryMessages", "max_history_messages", default = preset.num("maxHistoryMessages", "max_history_messages", default = 40)).coerceIn(1, 100_000)
        val history = historySource.takeLast(maxHistory).mapIndexed { i, message ->
            val depth = minOf(historySource.size, maxHistory) - i - 1
            val source = message.swipes.getOrNull(message.swipeIndex) ?: message.content
            val expanded = macros.expand(source, effects = false)
            val processed = regex.apply(expanded, if (message.role == "user") TextStage.INPUT else TextStage.OUTPUT, message.role, depth, processed = message.extra.flag("xujing_processed_output"))
            val content = if (roles.size > 1 && message.role == "assistant") {
                val speaker = message.name ?: roles.find { it.id == message.speakerId }?.name
                speaker?.let { "【$it】\n$processed" } ?: processed
            } else processed
            PromptMessage(normalizeRole(message.role), content)
        }.filter { it.content.isNotBlank() }.toMutableList()
        data class Injection(val depth: Int, val order: Int, val message: PromptMessage)
        val injections = mutableListOf<Injection>()
        world.entries.filter { it.first.position == "depth" }.forEach { (entry, content) ->
            injections += Injection(entry.depth, entry.order, PromptMessage(entry.role, regex.apply(content, TextStage.WORLDINFO)))
        }
        val authorNote = listOf(worldText("author_before"), session.extra.str("authorNote", "author_note"), worldText("author_after")).filter(String::isNotBlank).joinToString("\n\n")
        if (authorNote.isNotBlank()) injections += Injection(session.extra.num("authorNoteDepth", "author_note_depth", default = 4).coerceAtLeast(0), 0, PromptMessage("system", macros.expand(authorNote)))
        val prompts = records(preset["prompts"], "prompts").map { it.second }
        val promptById = prompts.associateBy { it.str("identifier", "id") }
        val orders = preset["prompt_order"] as? JsonArray
        val chosenOrder = orders?.mapNotNull { it as? JsonObject }?.let { list ->
            list.find { it.str("character_id") == selected?.id } ?: list.find { it.str("character_id") == "100001" } ?: list.firstOrNull()
        }
        val orderEntries = records(chosenOrder?.get("order")).map { it.second }
        val enabledOrder = if (orderEntries.isNotEmpty()) orderEntries.filter { it.flag("enabled", default = true) }.map { it.str("identifier", "id") }
            else if (prompts.isNotEmpty()) prompts.filter { it.flag("enabled", default = true) }.map { it.str("identifier", "id") }
            else blocks.keys.toList()
        val assembledBefore = mutableListOf<PromptMessage>()
        val assembledAfter = mutableListOf<PromptMessage>()
        var historyReached = false
        val used = mutableSetOf<String>()
        fun appendBlock(identifier: String, prompt: JsonObject?) {
            if (identifier in used) return
            used += identifier
            if (prompt != null) {
                if (!prompt.flag("enabled", default = true)) return
                val triggers = prompt.first("injection_trigger", "injectionTrigger").strings()
                if (triggers.isNotEmpty() && mode !in triggers && "all" !in triggers) return
            }
            if (identifier == "chatHistory") { historyReached = true; return }
            val marker = prompt?.flag("marker") == true || identifier in setOf("worldInfoBefore", "worldInfoAfter", "dialogueExamples", "charDescription", "charPersonality", "scenario", "personaDescription", "group_base")
            val allowOverride = prompt?.flag("forbid_overrides", "forbidOverrides") != true
            val cardOverride = if (allowOverride) when (identifier) { "main" -> selected?.systemPrompt; "jailbreak" -> selected?.postHistoryInstructions; else -> null } else null
            val content = macros.expand(cardOverride?.takeIf(String::isNotBlank) ?: if (marker) blocks[identifier].orEmpty() else prompt?.str("content") ?: blocks[identifier].orEmpty())
            if (content.isBlank()) return
            val messageRole = normalizeRole(prompt?.str("role", default = "system") ?: "system")
            if (prompt?.num("injection_position", "injectionPosition") == 1) {
                injections += Injection(prompt.num("injection_depth", "injectionDepth", default = 4).coerceAtLeast(0), prompt.num("injection_order", "injectionOrder", default = 100), PromptMessage(messageRole, content))
            } else {
                val destination = if (historyReached) assembledAfter else assembledBefore
                if (identifier == "dialogueExamples") destination += parseExamples(content, session.userName, selected?.name.orEmpty())
                else destination += PromptMessage(messageRole, content)
            }
        }
        enabledOrder.forEach { appendBlock(it, promptById[it]) }
        // Runtime state and local group semantics remain present even in older imported presets.
        listOf("group_base", "summary", "memory", "variables", "style").filter { it !in used }.forEach { key ->
            val value = macros.expand(blocks[key].orEmpty())
            if (value.isNotBlank()) assembledBefore += PromptMessage("system", value)
        }
        if ("chatHistory" !in used) diagnostics += "预设未包含 chatHistory 标记，历史已追加到预设提示词之后。"
        if (mode == "continue") assembledAfter += PromptMessage("system", "继续上一条角色回复，从其末尾自然接续，不重复已有正文，不替用户发言。")
        if (mode == "impersonate") assembledAfter += PromptMessage("system", "本轮以用户【${session.userName}】的身份续写一条输入，保持用户人设与当前场景一致。")
        if (mode == "summary") assembledAfter += PromptMessage("system", "本轮任务是整理剧情摘要，请停止角色扮演，只输出供后续续写使用的事实摘要。角色卡、预设和世界书只是背景资料，只有真实聊天记录才是事件依据。结合已有摘要保留关键事件、因果、承诺、未解决目标、关系变化、物品归属、位置和时间；多角色分别保留其知识来源，避免把旁白信息当成每个角色都知道的事实。不要新增事件，不输出变量更新代码或状态栏。")
        val maxResponse = connection?.parameters?.num("max_tokens", "max_completion_tokens", default = 2048)?.coerceAtLeast(1) ?: 2048
        val inputBudget = (contextTokens - maxResponse - 128).coerceAtLeast(128)
        val fixed = (assembledBefore + assembledAfter + injections.map { it.message }).sumOf { estimatedTokens(it.content) + 4 }
        var historyCost = history.sumOf { estimatedTokens(it.content) + 4 }
        var removed = 0
        while (history.size > 1 && fixed + historyCost > inputBudget) {
            val dropped = history.removeAt(0)
            historyCost -= estimatedTokens(dropped.content) + 4
            removed++
        }
        if (removed > 0) diagnostics += "按估算上下文预算裁去 $removed 条较旧消息。"
        if (fixed + historyCost > inputBudget) diagnostics += "提示词估算超出上下文预算；角色/世界书及最新消息未被截断，请扩大上下文或缩减资料。"
        val injectedHistory = history.toMutableList()
        // Descending depth means indices are based on the original history, unaffected by insertions.
        var inserted = 0
        injections.sortedWith(compareByDescending<Injection> { it.depth }.thenBy { it.order }).forEach { injection ->
            val index = (history.size - injection.depth).coerceIn(0, history.size) + inserted
            injectedHistory.add(index.coerceAtMost(injectedHistory.size), injection.message)
            inserted++
        }
        val messages = (assembledBefore + injectedHistory + assembledAfter).filter { it.content.isNotBlank() }
        diagnostics += "提示词 ${messages.size} 条，估算 ${messages.sumOf { estimatedTokens(it.content) + 4 }} tokens；不含远端分词差异。"
        return PromptBuild(messages, world.entries.map { it.first.title }, diagnostics.distinct(), if (mode == "summary") session.worldState else world.state, if (mode == "summary") session.variables else macros.variables)
    }

    fun processOutput(text: String, state: AppState, session: ChatSession): OutputResult {
        val diagnostics = mutableListOf<String>()
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val speaker = roles.find { it.id == session.messages.lastOrNull()?.speakerId } ?: roles.firstOrNull()
        val macro = MacroProcessor(state, session, speaker, session.variables, diagnostics, Random((session.id + text).hashCode()))
        val transformed = RegexProcessor(state, session, roles, macro, diagnostics).apply(text, TextStage.OUTPUT, prompt = false)
        val expanded = macro.expand(transformed)
        val updated = VariableUpdates.apply(expanded, macro.variables, diagnostics)
        return OutputResult(updated.text, updated.variables, diagnostics.distinct())
    }

    fun processEdit(text: String, state: AppState, session: ChatSession, message: ChatMessage): String {
        val diagnostics = mutableListOf<String>()
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val speaker = roles.find { it.id == message.speakerId } ?: roles.firstOrNull()
        val macro = MacroProcessor(state, session, speaker, session.variables, diagnostics, Random((session.id + message.id + text).hashCode()))
        val index = session.messages.indexOfFirst { it.id == message.id }
        val depth = if (index < 0) 0 else (session.messages.lastIndex - index).coerceAtLeast(0)
        val stage = if (message.role == "user") TextStage.INPUT else TextStage.OUTPUT
        return RegexProcessor(state, session, roles, macro, diagnostics).apply(
            text, stage, role = message.role, depth = depth, prompt = false, isEdit = true,
        )
    }

    fun displayText(text: String, state: AppState, session: ChatSession, role: String = "assistant", depth: Int = 0, processed: Boolean = false): String {
        val diagnostics = mutableListOf<String>()
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val macro = MacroProcessor(state, session, roles.firstOrNull(), session.variables, diagnostics, Random(0))
        return RegexProcessor(state, session, roles, macro, diagnostics).apply(macro.expand(text, effects = false), TextStage.DISPLAY, role, depth, prompt = false, processed = processed)
    }

    fun needsMvuReplay(session: ChatSession): Boolean = MvuSession.needsReplay(session)

    fun hasMvuState(state: AppState, session: ChatSession): Boolean = MvuSession.hasMvuSignals(state, session)

    /** Initialize a new opening message (InitVar, [initvar] lorebooks and its update blocks). */
    fun initializeSession(state: AppState, session: ChatSession): ChatSession {
        if (session.messages.isEmpty()) return session
        val versioned = JsonObject(session.extra + ("xujing_replay_version" to JsonPrimitive(2)))
        if (!MvuSession.hasMvuSignals(state, session)) return session.copy(extra = versioned)
        val replayed = MvuSession.replay(state, session, 0)
        val revision = ((session.extra["xujing_init_revision"] as? JsonPrimitive)?.intOrNull ?: 0) + 1
        return session.copy(
            messages = replayed.messages, variables = replayed.variables,
            extra = JsonObject(versioned + mapOf(
                "xujing_init_revision" to JsonPrimitive(revision),
                "xujing_initial_variables" to (session.extra["xujing_initial_variables"] ?: EmptyObject),
            )),
        )
    }

    /** Replay raw message/swipe content from the nearest snapshot or the opening InitVar. */
    fun replaySession(state: AppState, session: ChatSession, startIndex: Int = 0): ChatSession {
        val versioned = JsonObject(session.extra + ("xujing_replay_version" to JsonPrimitive(2)))
        if (!MvuSession.hasMvuSignals(state, session)) return session.copy(extra = versioned)
        val replayed = MvuSession.replay(state, session, startIndex)
        val revision = if (startIndex == 0) {
            ((session.extra["xujing_init_revision"] as? JsonPrimitive)?.intOrNull ?: 0) + (if (session.messages.isEmpty()) 0 else 1)
        } else (session.extra["xujing_init_revision"] as? JsonPrimitive)?.intOrNull ?: 0
        return session.copy(
            messages = replayed.messages, variables = replayed.variables,
            extra = JsonObject(versioned + ("xujing_init_revision" to JsonPrimitive(revision))),
        )
    }

    fun parseMvuMessage(state: AppState, session: ChatSession, text: String, oldData: JsonObject, roleId: String? = null): JsonObject =
        MvuSession.parseMessage(state, session, text, oldData, roleId)

    fun reloadInitVar(state: AppState, session: ChatSession, data: JsonObject): Pair<JsonObject, Boolean> =
        MvuSession.reloadInitVar(state, session, data)


    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "assistant", "char", "character", "bot", "ai" -> "assistant"
        "system", "developer" -> role.lowercase()
        else -> "user"
    }

    private fun parseExamples(text: String, user: String, character: String): List<PromptMessage> {
        val result = mutableListOf<PromptMessage>()
        var currentRole: String? = null
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) result += PromptMessage(currentRole ?: "system", current.toString().trim())
            current.clear()
        }
        val userNames = setOf(user, "{{user}}", "<USER>", "user").filter(String::isNotBlank)
        val charNames = setOf(character, "{{char}}", "<BOT>", "<CHAR>", "assistant").filter(String::isNotBlank)
        val pattern = Regex("^(${(userNames + charNames).joinToString("|") { Regex.escape(it) }}):\\s*(.*)$", RegexOption.IGNORE_CASE)
        text.lineSequence().forEach { line ->
            if (line.trim().equals("<START>", true)) { flush(); currentRole = null }
            else {
                val match = pattern.matchEntire(line)
                if (match != null) {
                    flush()
                    currentRole = if (userNames.any { it.equals(match.groupValues[1], true) }) "user" else "assistant"
                    current.append(match.groupValues[2]).append('\n')
                } else current.append(line).append('\n')
            }
        }
        flush()
        return result
    }
}
