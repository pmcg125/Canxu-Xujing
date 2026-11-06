package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.random.Random

internal data class WorldEntry(
    val id: String, val title: String, val data: JsonObject, val source: String,
    val sourceRank: Int, val order: Int, val keys: List<String>, val secondary: List<String>,
    val position: String, val depth: Int, val role: String, val outlet: String,
    // 扫描深度必须在收集阶段随条目保留：条目显式值（含 0）、所属书级值分别记录。
    val explicitScanDepth: Int? = null,
    val bookScanDepth: Int? = null,
    // 组评分三态：null/缺失沿用全局，false 表示该条目不参与评分筛选。
    val groupScoring: Boolean? = null,
)
internal data class WorldActivation(
    val entries: List<Pair<WorldEntry, String>>, val state: JsonObject,
    val diagnostics: List<String>,
) {
    fun at(position: String): String = entries.filter { it.first.position == position }.joinToString("\n\n") { it.second }
}

private const val MaxHistoryDepth = 1000

/**
 * 扫描深度三态解析：字段缺失、null、"same_as_global" 都表示继承；
 * 非数字、负数等非法值同样回退到继承；显式 0 会被原样返回。
 */
private fun JsonObject.scanDepthOverride(vararg keys: String): Int? {
    for (key in keys) {
        val element = this[key] ?: continue
        if (element is JsonNull) continue
        val primitive = element as? JsonPrimitive ?: continue
        if (primitive.isString && primitive.content.equals("same_as_global", ignoreCase = true)) return null
        val value = primitive.content.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 0) return null
        return value.toInt()
    }
    return null
}

/** 布尔覆盖三态：缺失/null/"same_as_global" 返回 null（跟随全局）。 */
private fun JsonObject.booleanOverride(vararg keys: String): Boolean? {
    for (key in keys) {
        val element = this[key] ?: continue
        if (element is JsonNull) continue
        val primitive = element as? JsonPrimitive ?: continue
        if (primitive.isString && primitive.content.equals("same_as_global", ignoreCase = true)) return null
        primitive.booleanOrNull?.let { return it }
        primitive.intOrNull?.let { return it != 0 }
        when (primitive.content.lowercase()) { "true" -> return true; "false" -> return false }
    }
    return null
}

/**
 * 书级扫描深度：LibraryDocument.data 根 / 其 data、config、extensions，
 * 以及 document.extra / extra.extensions。非法或缺失返回 null，继续落到会话全局值。
 */
private fun resolveBookScanDepth(data: JsonElement, extra: JsonObject): Int? {
    val candidates = mutableListOf<JsonObject>()
    val root = data.objectOrEmpty()
    if (root.isNotEmpty()) {
        candidates += root
        (root["data"] as? JsonObject)?.let { candidates += it }
        (root["config"] as? JsonObject)?.let { candidates += it }
        (root["extensions"] as? JsonObject)?.let { candidates += it }
    }
    candidates += extra
    (extra["extensions"] as? JsonObject)?.let { candidates += it }
    for (candidate in candidates) candidate.scanDepthOverride("scan_depth", "scanDepth")?.let { return it }
    return null
}

/** 参考 rm() 中的角色身份：角色文件名、角色名、角色标签。 */
private data class CharacterRef(val name: String, val filename: String, val tags: Set<String>)

private fun characterRef(role: RoleCard): CharacterRef {
    val raw = roleData(role)
    val rawRoot = role.raw.objectOrEmpty()
    val extensions = raw["extensions"].objectOrEmpty()
    val name = raw.str("name").ifBlank { role.name }.trim()
    val filename = extensions.str("piupiu_character_filename").ifBlank { name }.trim()
    val tags = ((raw["tags"] as? JsonArray)
        ?: (rawRoot["data"].objectOrEmpty()["tags"] as? JsonArray)
        ?: JsonArray(emptyList())).strings().map(String::trim).filter(String::isNotEmpty).toSet()
    return CharacterRef(name, filename, tags)
}

private fun characterFilterMatches(filter: JsonObject, source: CharacterRef?, focus: CharacterRef?): Boolean {
    val names = filter.first("names", "name").strings()
    val tags = filter.first("tags", "tag").strings()
    val sourceNames = listOfNotNull(source?.filename, source?.name, focus?.name).filter(String::isNotEmpty)
    val sourceTags = source?.tags ?: focus?.tags.orEmpty()
    return names.any { it in sourceNames } || tags.any { it in sourceTags }
}

/** 空 names+tags 始终放行；否则按 isExclude 取反。参考 rm() 20739-20759。 */
private fun characterFilterAllows(filter: JsonObject, source: CharacterRef?, focus: CharacterRef?): Boolean {
    val names = filter.first("names", "name").strings()
    val tags = filter.first("tags", "tag").strings()
    if (names.isEmpty() && tags.isEmpty()) return true
    val matched = characterFilterMatches(filter, source, focus)
    return matched != filter.flag("isExclude", "is_exclude")
}

/**
 * 参考 im()：triggers 缺失或为空时总是允许；否则要求当前模式在列表内。
 * 原版只有 normal / continue / regenerate 三个触发值；swipe 没有独立触发值，
 * 这里按 regenerate 处理。summary、impersonate 等未知模式保留字面值，
 * 只匹配空 triggers 或显式同名 triggers，不回退成 normal。
 */
private fun worldTriggerMode(mode: String): String = when (mode.lowercase()) {
    "", "normal" -> "normal"
    "continue" -> "continue"
    "regenerate", "swipe" -> "regenerate"
    else -> mode.lowercase()
}

internal object WorldbookEngine {
    fun activate(
        app: AppState, session: ChatSession, roles: List<RoleCard>, macros: MacroProcessor,
        contextTokens: Int, random: Random, mode: String = "normal", focusRole: RoleCard? = null,
    ): WorldActivation {
        val diagnostics = mutableListOf<String>()
        val config = listOf("worldInfo", "world_info", "worldbookSettings", "worldbook_settings")
            .map { session.extra[it].objectOrEmpty() }.fold(EmptyObject, ::mergeJson)
        val sourceOrder = config.first("sourceOrder", "source_order").strings().ifEmpty { listOf("chat", "persona", "character", "global") }
        val entries = mutableListOf<WorldEntry>()
        val triggerMode = worldTriggerMode(mode)
        fun collect(id: String, data: JsonElement, source: String, sourceCharacter: CharacterRef?, focusCharacter: CharacterRef?, extra: JsonObject = EmptyObject) {
            val entryBookScanDepth = resolveBookScanDepth(data, extra)
            records(data, "entries", "world_book_entries", "worldBookEntries").forEach { (key, entry) ->
                val ext = entry["extensions"].objectOrEmpty()
                val normalized = mergeJson(ext, entry)
                if (normalized.flag("disable", "disabled") || !normalized.flag("enabled", default = true)) return@forEach
                val title = normalized.str("comment", "name", "title", default = "$id/$key")
                // 角色过滤与生成模式过滤在收集阶段执行，和参考 rm()/im() 一样先于常驻、sticky、递归和预算。
                val filter = normalized.first("characterFilter", "character_filter") as? JsonObject
                if (filter != null && filter.isNotEmpty() && !characterFilterAllows(filter, sourceCharacter, focusCharacter)) {
                    val reason = if (filter.flag("isExclude", "is_exclude")) "命中排除名单" else "角色名/标签不匹配"
                    diagnostics += "世界书 $title 被 characterFilter 排除（$reason）。"
                    return@forEach
                }
                val triggers = normalized.first("triggers", "trigger").strings()
                if (triggers.isNotEmpty() && triggerMode !in triggers) {
                    diagnostics += "世界书 $title 的 triggers 不包含当前模式 $triggerMode。"
                    return@forEach
                }
                val strategy = normalized["strategy"].objectOrEmpty()
                val keyObject = normalized["keys"].objectOrEmpty()
                val secondaryObject = strategy["keys_secondary"].objectOrEmpty()
                val keys = normalized.first("key", "keys").strings().ifEmpty {
                    keyObject["primary"].strings().ifEmpty { strategy["keys"].strings() }
                }
                val secondary = normalized.first("keysecondary", "secondary_keys", "secondaryKeys").strings().ifEmpty {
                    keyObject["secondary"].strings().ifEmpty { secondaryObject["keys"].strings() }
                }
                val positionObject = normalized["position"].objectOrEmpty()
                val positionValue = normalized["position"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: positionObject.str("type", default = "before_char")
                val position = when (positionValue.lowercase()) {
                    "0", "before_char", "before", "before_character", "before_character_definition" -> "before"
                    "1", "after_char", "after", "after_character", "after_character_definition" -> "after"
                    "2", "before_an", "before_author_note" -> "author_before"
                    "3", "after_an", "after_author_note" -> "author_after"
                    "4", "at_depth", "depth", "in_chat" -> "depth"
                    "5", "before_em", "before_example_messages" -> "examples_before"
                    "6", "after_em", "after_example_messages" -> "examples_after"
                    "7", "outlet" -> "outlet"
                    else -> { diagnostics += "世界书 $id/$key 的插入位置 $positionValue 未识别，置于角色后。"; "after" }
                }
                val roleValue = positionObject.str("role", default = normalized.str("role", "insertion_role", default = "system"))
                val messageRole = when (roleValue) { "0", "system" -> "system"; "1", "user" -> "user"; "2", "assistant" -> "assistant"; else -> "system" }
                entries += WorldEntry(
                    "$id:${normalized.str("uid", "id", default = key)}",
                    title, normalized,
                    source, sourceOrder.indexOf(source).let { if (it < 0) sourceOrder.size else it },
                    normalized.num("order", "insertion_order", "insertionOrder", default = 100), keys, secondary,
                    position, positionObject.num("depth", default = normalized.num("depth", "insertion_depth", default = 4)).coerceAtLeast(0),
                    messageRole, normalized.str("outletName", "outlet_name", "outlet", default = positionObject.str("outlet")),
                    explicitScanDepth = normalized.scanDepthOverride("scanDepth", "scan_depth")
                        ?: strategy.scanDepthOverride("scan_depth", "scanDepth"),
                    bookScanDepth = entryBookScanDepth,
                    groupScoring = normalized.booleanOverride("useGroupScoring", "use_group_scoring"),
                )
            }
        }
        val roleRefs = roles.associate { it.id to characterRef(it) }
        // 参考多角色行为：角色绑定书使用该书所属角色的名字/文件名/标签；
        // 聊天、人设和全局书使用本轮回复角色（focusRole，缺省为首个会话角色）。
        val focusRef = (focusRole ?: roles.firstOrNull())?.let { roleRefs[it.id] ?: characterRef(it) }
        val visibleBooks=app.library.filter { document ->
            document.enabled && document.kind in setOf("worldbook", "world_book", "lorebook") &&
                (document.extra.text("xujing_session_id").isEmpty() || document.extra.text("xujing_session_id")==session.id)
        }
        fun resolveBooks(references:Collection<String>):Set<String> = references.filter(String::isNotBlank).mapNotNull { ref ->
            visibleBooks.firstOrNull { it.id==ref }?.id ?: run {
                val matches=visibleBooks.filter { it.name==ref }
                val local=matches.filter { it.extra.text("xujing_session_id")==session.id }
                val selected=local.ifEmpty { matches }
                if(selected.size>1) diagnostics += "存在多个同名世界书 $ref，请按资料 ID 绑定。"
                selected.singleOrNull()?.id
            }
        }.toSet()
        // 参考按来源顺序更新附加书映射；同一本书重复绑定时，后一个角色覆盖前一个。
        val characterOwners = linkedMapOf<String, RoleCard>()
        roles.forEach { role ->
            val extensions=roleData(role)["extensions"].objectOrEmpty()
            val references=listOf(extensions.str("world")) + extensions["tavern_helper"].objectOrEmpty()["additional_worldbooks"].strings()
            resolveBooks(references.filter(String::isNotBlank)).forEach { id -> characterOwners[id] = role }
        }
        val characterIds=characterOwners.keys
        val sessionBooks=session.extra["globalWorldBookNames"].strings()+listOf(session.extra.str("chatWorldBookName"))
        val explicitIds=resolveBooks(session.worldbookIds+sessionBooks)
        visibleBooks.forEach { document ->
            val explicitlyBound = document.id in explicitIds
            val source = when {
                explicitlyBound -> "chat"
                document.id in characterIds -> "character"
                document.extra.str("source") == "persona" -> "persona"
                document.extra.str("source") == "character" -> "character"
                else -> "global"
            }
            val global = document.extra.flag("global", "isGlobal", "is_global")
            // 角色附加书同时被聊天绑定时，来源角色身份仍用于过滤，即使注入来源优先记为 chat。
            val ownerRef = characterOwners[document.id]?.let { roleRefs[it.id] }
            if (explicitlyBound || global || source == "persona" || source == "character") {
                collect(document.id, document.data, source, ownerRef ?: focusRef, focusRef, document.extra)
            }
        }
        roles.forEach { role ->
            val raw = roleData(role)
            listOf(raw["character_book"], raw["characterBook"], raw["worldbook"], raw["world_book"], raw["extensions"].objectOrEmpty()["character_book"])
                .filterNotNull().distinct().forEachIndexed { index, book ->
                    collect("role:${role.id}:$index", book, "character", roleRefs[role.id], focusRef)
                }
        }
        val sorted = entries.distinctBy(WorldEntry::id).sortedWith(compareBy<WorldEntry> { it.sourceRank }.thenByDescending { it.order }.thenBy { it.id })
        // 会话全局值：默认 4（本项目既有默认，不改为参考实现的 2）；显式 0 合法。
        val globalScanDepth = (config.scanDepthOverride("depth", "scanDepth", "world_info_depth", "scan_depth") ?: 4).coerceIn(0, MaxHistoryDepth)
        // maxDepth 缺失或为 0 时允许扩大至现有历史长度。
        val maxDepthValue = config.scanDepthOverride("maxScanDepth", "max_scan_depth", "maxDepth", "max_depth")
        // 参考 23049-23059：正数 maxDepth 按包含末端的索引处理，因此要加一。
        val maxExtraDepth = when {
            maxDepthValue == null || maxDepthValue == 0 -> session.messages.size
            else -> (minOf(session.messages.size, maxDepthValue.coerceAtMost(MaxHistoryDepth) + 1) - globalScanDepth).coerceAtLeast(0)
        }.coerceIn(0, minOf(MaxHistoryDepth, session.messages.size))
        val minActivations = config.num("minActivations", "min_activations", default = 0).coerceAtLeast(0)
        val groupScoring = config.flag("useGroupScoring", "use_group_scoring")
        val recursion = config.flag("recursive", "recursion", "world_info_recursive", default = true)
        // 原版 0 表示不限制递归轮数；正值是轮数上限。
        val recursionLimit = config.num("maxRecursionSteps", "max_recursion_steps", "max_recursion", default = 8).coerceIn(0, 32)
        val percent = config.decimal("budgetPercent", "budget_percent", "world_info_budget", default = 25.0).coerceIn(0.0, 100.0)
        val configuredCap = config.num("budgetCap", "budget_cap", "world_info_budget_cap", default = 2048).coerceAtLeast(0)
        val budget = if (percent > 0.0) (contextTokens * percent / 100).toInt().let { if (configuredCap > 0) minOf(it, configuredCap) else it } else configuredCap.takeIf { it > 0 }
        val budgetText = budget?.toString() ?: "无上限"
        val turn = session.messages.count { it.role == "assistant" && it.content.isNotBlank() }
        val priorEntries = session.worldState["entries"].objectOrEmpty()
        val nextEntries = priorEntries.toMutableMap()
        val active = linkedMapOf<String, Pair<WorldEntry, String>>()
        var spent = 0
        var scanExpansion = 0
        var maxScanWindow = globalScanDepth
        var recursiveText = ""
        val chance = mutableMapOf<String, Boolean>()
        val processedCandidates = mutableSetOf<String>()

        fun entryGroup(entry: WorldEntry): List<String> = entry.data.first("group", "groups", "inclusion_group").strings()
        fun scanDepthFor(entry: WorldEntry): Int {
            // 参考 22453-22482：条目显式 scanDepth 优先（包括 0），否则书级 scan_depth，最后全局默认。
            entry.explicitScanDepth?.let { return it.coerceIn(0, MaxHistoryDepth) }
            val base = entry.bookScanDepth ?: globalScanDepth
            if (scanExpansion <= 0) return base.coerceIn(0, MaxHistoryDepth)
            // minActivations 扩大扫描只作用于继承值；显式 scanDepth 不随扩大变化。
            return (base + scanExpansion).coerceIn(0, MaxHistoryDepth)
        }
        fun textFor(entry: WorldEntry): String {
            val d = entry.data
            val localDepth = scanDepthFor(entry)
            if (localDepth > maxScanWindow) maxScanWindow = localDepth
            val history = if (localDepth <= 0) "" else session.messages.takeLast(localDepth).joinToString("\n") { message ->
                val name = if (config.flag("includeNames", "include_names")) "${message.name ?: if (message.role == "user") session.userName else roles.find { it.id == message.speakerId }?.name.orEmpty()}: " else ""
                name + message.content
            }
            return buildString {
                append(history)
                // 角色资料等额外扫描源不受 scanDepth=0 影响，参考 22683-22702 单独并入扫描文本。
                if (d.flag("match_character_description", "matchCharacterDescription")) roles.forEach { append('\n').append(it.description) }
                if (d.flag("match_character_personality", "matchCharacterPersonality")) roles.forEach { append('\n').append(it.personality) }
                if (d.flag("match_scenario", "matchScenario")) roles.forEach { append('\n').append(it.scenario) }
                if (d.flag("match_persona_description", "matchPersonaDescription")) append('\n').append(session.persona)
                if (!d.flag("excludeRecursion", "exclude_recursion") && !d["recursion"].objectOrEmpty().flag("prevent_incoming")) append('\n').append(recursiveText)
            }
        }
        fun keywordMatches(key: String, text: String, d: JsonObject): Boolean {
            if (key.isBlank()) return false
            val caseSensitive = d.flag("caseSensitive", "case_sensitive", default = config.flag("caseSensitive", "case_sensitive"))
            val wholeWords = d.flag("matchWholeWords", "match_whole_words", default = config.flag("matchWholeWords", "match_whole_words"))
            return if (key.startsWith('/') && key.lastIndexOf('/') > 0) {
                val index = key.lastIndexOf('/')
                runCatching {
                    val options = if ('i' in key.substring(index + 1)) setOf(RegexOption.IGNORE_CASE) else emptySet()
                    Regex(key.substring(1, index), options).containsMatchIn(text)
                }.getOrElse { diagnostics += "世界书关键词正则无效：${key.take(80)}"; false }
            } else if (wholeWords) Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])", if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(text)
            else text.contains(key, ignoreCase = !caseSensitive)
        }
        // 参考 om() 20827-20853：得分是主关键词与副关键词的命中数量，规则与普通匹配一致。
        fun groupScore(entry: WorldEntry, text: String): Int =
            (entry.keys + entry.secondary).count { keywordMatches(macros.expand(it, false), text, entry.data) }
        fun delayUntilStep(entry: WorldEntry): Int {
            val topDelay = entry.data["delayUntilRecursion"] ?: entry.data["delay_until_recursion"]
            return topDelay?.let { value ->
                val primitive = value as? JsonPrimitive
                primitive?.booleanOrNull?.let { if (it) 1 else 0 }
                    ?: primitive?.contentOrNull?.toIntOrNull()?.coerceAtLeast(0)
                    ?: 0
            } ?: entry.data["recursion"].objectOrEmpty().num("delay_until").coerceAtLeast(0)
        }
        fun eligible(entry: WorldEntry, step: Int): Boolean {
            val d = entry.data
            val old = priorEntries[entry.id].objectOrEmpty()
            val previousTurn = old.num("lastActivatedTurn", default = -1)
            val sticky = previousTurn >= 0 && old.num("stickyUntil", default = -1) >= turn
            if (sticky) return true
            if (old.num("cooldownUntil", default = -1) >= turn && previousTurn != turn) return false
            val effect = d["effect"].objectOrEmpty()
            if (turn < d.num("delay", default = effect.num("delay"))) return false
            val rec = d["recursion"].objectOrEmpty()
            if (delayUntilStep(entry) > step) return false
            val strategy = d["strategy"].objectOrEmpty()
            if (!d.flag("constant") && strategy.str("type") != "constant") {
                val text = textFor(entry)
                if (!entry.keys.any { keywordMatches(macros.expand(it, false), text, d) }) return false
                if (d.flag("selective", default = entry.secondary.isNotEmpty()) && entry.secondary.isNotEmpty()) {
                    val hits = entry.secondary.map { keywordMatches(macros.expand(it, false), text, d) }
                    val logic = strategy["keys_secondary"].objectOrEmpty().str("logic", default = d.str("selectiveLogic", "selective_logic", default = "0"))
                    val match = when (logic.lowercase()) { "0", "and_any" -> hits.any { it }; "1", "not_all" -> !hits.all { it }; "2", "not_any" -> hits.none { it }; "3", "and_all" -> hits.all { it }; else -> hits.any { it } }
                    if (!match) return false
                }
            }
            // 概率不在 eligible 阶段判定；参考 22740-22774 在组选择之后、预算之前抽取。
            return true
        }
        val lastDelayedStep = (sorted.maxOfOrNull(::delayUntilStep) ?: 0)
            .coerceAtMost(if (recursionLimit > 0) recursionLimit - 1 else MaxHistoryDepth)
        // 显式 scanDepth 不参与最小激活扩大；没有可扩大的条目时不再空转。
        val canExpandScan = sorted.any { it.explicitScanDepth == null }
        // 无上限模式仍设防止异常配置导致长时间循环的安全边界；正常轮次最多依次激活每个条目、等待延迟并扩大历史。
        val unlimitedSafetyLimit = minOf(Int.MAX_VALUE.toLong(), 1L + sorted.size + lastDelayedStep + maxExtraDepth).toInt()
        val roundLimit = if (!recursion) 1 else if (recursionLimit == 0) unlimitedSafetyLimit else recursionLimit
        var step = 0
        while (step < roundLimit) {
            val candidates = sorted.filter { it.id !in active && it.id !in processedCandidates && eligible(it, step) }
            // 参考 22683-22721：组评分文本使用最近 1000 条历史与递归文本，而不是条目自身的 scanDepth 窗口。
            val scoreText = if (groupScoring || candidates.any { it.groupScoring == true }) {
                val history = session.messages.takeLast(MaxHistoryDepth).joinToString("\n") { it.content }
                if (recursiveText.isBlank()) history else history + "\n" + recursiveText
            } else ""
            val grouped = candidates.flatMap { e -> entryGroup(e).map { it to e } }.groupBy({ it.first }, { it.second })
            val selectedIds = candidates.filter { entryGroup(it).isEmpty() }.mapTo(mutableSetOf()) { it.id }
            grouped.forEach { (name, candidatesInGroup) ->
                val sticky = candidatesInGroup.filter {
                    val previous = priorEntries[it.id].objectOrEmpty()
                    previous.num("lastActivatedTurn", default = -1) >= 0 && previous.num("stickyUntil", default = -1) >= turn
                }
                val override = candidatesInGroup.filter { it.data.flag("groupOverride", "group_override") }.maxByOrNull { it.order }
                var pool = candidatesInGroup
                // 顺序保持 参考 om()：sticky → groupOverride → 评分筛选 → 权重随机。
                if (sticky.isEmpty() && override == null &&
                    (groupScoring || candidatesInGroup.any { it.groupScoring == true })) {
                    val scores = candidatesInGroup.associate { it.id to groupScore(it, scoreText) }
                    val best = scores.values.maxOrNull() ?: 0
                    val kept = candidatesInGroup.filter { entry ->
                        // 未启用评分的条目（条目 false 或全局关闭）无条件保留；启用评分的条目只保留最高分。
                        !(entry.groupScoring ?: groupScoring) || scores[entry.id] == best
                    }
                    if (kept.size < candidatesInGroup.size) {
                        diagnostics += "世界书互斥组 $name 关键词评分排除 ${candidatesInGroup.size - kept.size} 条，保留 ${kept.size} 条。"
                    }
                    pool = kept
                }
                if (sticky.isNotEmpty()) selectedIds.addAll(sticky.map { it.id })
                else if (override != null) selectedIds.add(override.id)
                else {
                    val weighted = pool.map { it to it.data.decimal("groupWeight", "group_weight", default = 100.0).coerceAtLeast(0.0) }
                    val total = weighted.sumOf { it.second }
                    var draw = random.nextDouble(maxOf(1.0, total))
                    val selected = weighted.firstOrNull { (_, weight) -> draw -= weight; draw <= 0 }?.first ?: pool.last()
                    selectedIds.add(selected.id)
                }
            }
            // 多组成员在任一组获选即可保留；已经参与选择的条目不会在后续轮次重试。
            processedCandidates.addAll(candidates.map { it.id })
            var outgoingAdded = false
            candidates.forEach { entry ->
                if (entry.id !in selectedIds) return@forEach
                // 参考 22740-22774：组选择之后再判定概率；sticky 条目直接通过。
                val previousState = priorEntries[entry.id].objectOrEmpty()
                val stickyNow = previousState.num("lastActivatedTurn", default = -1) >= 0 &&
                    previousState.num("stickyUntil", default = -1) >= turn
                val probabilityAllowed = chance.getOrPut(entry.id) {
                    stickyNow || !entry.data.flag("useProbability", "use_probability", default = true) ||
                        random.nextDouble(100.0) < entry.data.decimal("probability", default = 100.0).coerceIn(0.0, 100.0)
                }
                if (!probabilityAllowed) return@forEach
                val raw = entry.data.str("content", "text")
                // Do not apply side effects to a rejected budget candidate.
                val estimated = estimatedTokens(macros.expand(raw, effects = false))
                // budget == null means "no limit" (percent 0 and cap 0); only enforce when a cap exists.
                if (!entry.data.flag("ignoreBudget", "ignore_budget") && budget != null && spent + estimated > budget) {
                    diagnostics += "世界书预算不足，跳过 ${entry.title}（估算 $estimated tokens）。"
                    return@forEach
                }
                val content = macros.expand(raw)
                if (content.isBlank()) return@forEach
                active[entry.id] = entry to content
                spent += estimated
                if (!entry.data.flag("preventRecursion", "prevent_recursion") && !entry.data["recursion"].objectOrEmpty().flag("prevent_outgoing")) outgoingAdded = true
                val previous = previousState
                val duration = entry.data.num("sticky", default = entry.data["effect"].objectOrEmpty().num("sticky")).coerceAtLeast(0)
                val cooldown = entry.data.num("cooldown", default = entry.data["effect"].objectOrEmpty().num("cooldown")).coerceAtLeast(0)
                val wasSticky = previous.num("stickyUntil", default = -1) >= turn
                val stickyUntil = if (wasSticky) previous.num("stickyUntil") else turn + max(duration - 1, 0)
                nextEntries[entry.id] = buildJsonObject {
                    put("lastActivatedTurn", turn); put("stickyUntil", stickyUntil)
                    put("cooldownUntil", if (wasSticky) previous.num("cooldownUntil", default = stickyUntil + cooldown) else stickyUntil + cooldown)
                }
            }
            recursiveText = active.values.filterNot { (e, _) -> e.data.flag("preventRecursion", "prevent_recursion") || e.data["recursion"].objectOrEmpty().flag("prevent_outgoing") }
                .joinToString("\n") { it.second }
            // 先处理新递归内容与延迟条目；仅无限递归模式才在这些轮次结束后扩大扫描。
            if (recursion && (outgoingAdded || step < lastDelayedStep)) { step++; continue }
            if (recursion && recursionLimit == 0 && active.size < minActivations && canExpandScan && scanExpansion < maxExtraDepth) {
                scanExpansion++
                step++
                continue
            }
            break
        }
        if (step >= roundLimit && recursion && recursionLimit == 0) diagnostics += "世界书递归达到安全轮数上限 $roundLimit。"
        val output = active.values.sortedWith(compareBy<Pair<WorldEntry, String>> { it.first.sourceRank }.thenBy { it.first.order })
        output.filter { it.first.position == "outlet" }.groupBy { it.first.outlet }.forEach { (name, chunks) ->
            macros.outlets[name] = chunks.joinToString("\n\n") { it.second }
        }
        val scanNote = if (scanExpansion > 0) "（最小激活扩大 +$scanExpansion，显式 scanDepth 不扩大）" else ""
        diagnostics += "世界书激活 " + active.size + "/" + sorted.size + "，估算 " + spent + "/" + budgetText + " tokens；扫描 " + maxScanWindow + " 条消息" + scanNote + "。"
        return WorldActivation(output, JsonObject(session.worldState + mapOf("entries" to JsonObject(nextEntries), "turn" to JsonPrimitive(turn))), diagnostics)
    }
}
