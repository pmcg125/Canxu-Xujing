package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.random.Random

internal data class WorldEntry(
    val id: String, val title: String, val data: JsonObject, val source: String,
    val sourceRank: Int, val order: Int, val keys: List<String>, val secondary: List<String>,
    val position: String, val depth: Int, val role: String, val outlet: String,
)
internal data class WorldActivation(
    val entries: List<Pair<WorldEntry, String>>, val state: JsonObject,
    val diagnostics: List<String>,
) {
    fun at(position: String): String = entries.filter { it.first.position == position }.joinToString("\n\n") { it.second }
}

internal object WorldbookEngine {
    fun activate(
        app: AppState, session: ChatSession, roles: List<RoleCard>, macros: MacroProcessor,
        contextTokens: Int, random: Random,
    ): WorldActivation {
        val diagnostics = mutableListOf<String>()
        val config = listOf("worldInfo", "world_info", "worldbookSettings", "worldbook_settings")
            .map { session.extra[it].objectOrEmpty() }.fold(EmptyObject, ::mergeJson)
        val sourceOrder = config.first("sourceOrder", "source_order").strings().ifEmpty { listOf("chat", "persona", "character", "global") }
        val entries = mutableListOf<WorldEntry>()
        fun collect(id: String, data: JsonElement, source: String) {
            records(data, "entries", "world_book_entries", "worldBookEntries").forEach { (key, entry) ->
                val ext = entry["extensions"].objectOrEmpty()
                val normalized = mergeJson(ext, entry)
                if (normalized.flag("disable", "disabled") || !normalized.flag("enabled", default = true)) return@forEach
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
                    normalized.str("comment", "name", "title", default = "$id/$key"), normalized,
                    source, sourceOrder.indexOf(source).let { if (it < 0) sourceOrder.size else it },
                    normalized.num("order", "insertion_order", "insertionOrder", default = 100), keys, secondary,
                    position, positionObject.num("depth", default = normalized.num("depth", "insertion_depth", default = 4)).coerceAtLeast(0),
                    messageRole, normalized.str("outletName", "outlet_name", "outlet", default = positionObject.str("outlet")),
                )
            }
        }
        val characterBooks=roles.flatMap { role ->
            val extensions=roleData(role)["extensions"].objectOrEmpty()
            listOf(extensions.str("world")) + extensions["tavern_helper"].objectOrEmpty()["additional_worldbooks"].strings()
        }.filter(String::isNotBlank).toSet()
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
        val sessionBooks=session.extra["globalWorldBookNames"].strings()+listOf(session.extra.str("chatWorldBookName"))
        val explicitIds=resolveBooks(session.worldbookIds+sessionBooks)
        val characterIds=resolveBooks(characterBooks)
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
            if (explicitlyBound || global || source == "persona" || source == "character") collect(document.id, document.data, source)
        }
        roles.forEach { role ->
            val raw = roleData(role)
            listOf(raw["character_book"], raw["characterBook"], raw["worldbook"], raw["world_book"], raw["extensions"].objectOrEmpty()["character_book"])
                .filterNotNull().distinct().forEachIndexed { index, book -> collect("role:${role.id}:$index", book, "character") }
        }
        val sorted = entries.distinctBy(WorldEntry::id).sortedWith(compareBy<WorldEntry> { it.sourceRank }.thenByDescending { it.order }.thenBy { it.id })
        val scanDepth = config.num("depth", "scanDepth", "world_info_depth", default = 4).coerceIn(1, 1000)
        val maxDepth = config.num("maxScanDepth", "max_scan_depth", default = scanDepth).coerceIn(scanDepth, 1000)
        val minActivations = config.num("minActivations", "min_activations", default = 0).coerceAtLeast(0)
        val recursion = config.flag("recursive", "recursion", "world_info_recursive", default = true)
        val recursionLimit = config.num("maxRecursionSteps", "max_recursion_steps", "max_recursion", default = 8).coerceIn(1, 32)
        val percent = config.decimal("budgetPercent", "budget_percent", "world_info_budget", default = 25.0).coerceIn(0.0, 100.0)
        val configuredCap = config.num("budgetCap", "budget_cap", "world_info_budget_cap", default = 2048).coerceAtLeast(0)
        val budget = if (percent > 0.0) (contextTokens * percent / 100).toInt().let { if (configuredCap > 0) minOf(it, configuredCap) else it } else configuredCap.takeIf { it > 0 }
        val budgetText = budget?.toString() ?: "无上限"
        val turn = session.messages.count { it.role == "assistant" && it.content.isNotBlank() }
        val priorEntries = session.worldState["entries"].objectOrEmpty()
        val nextEntries = priorEntries.toMutableMap()
        val active = linkedMapOf<String, Pair<WorldEntry, String>>()
        var spent = 0
        var scan = scanDepth
        var recursiveText = ""
        val groups = mutableSetOf<String>()
        val chance = mutableMapOf<String, Boolean>()
        val groupChoice = mutableMapOf<String, String>()

        fun entryGroup(entry: WorldEntry): List<String> = entry.data.first("group", "groups", "inclusion_group").strings()
        fun textFor(entry: WorldEntry): String {
            val d = entry.data
            val localDepth = d.num("scanDepth", "scan_depth", default = scan).coerceIn(0, 1000)
            val history = session.messages.takeLast(localDepth).joinToString("\n") { message ->
                val name = if (config.flag("includeNames", "include_names")) "${message.name ?: if (message.role == "user") session.userName else roles.find { it.id == message.speakerId }?.name.orEmpty()}: " else ""
                name + message.content
            }
            return buildString {
                append(history)
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
            if (!chance.getOrPut(entry.id) {
                !d.flag("useProbability", "use_probability", default = true) || random.nextDouble(100.0) < d.decimal("probability", default = 100.0).coerceIn(0.0, 100.0)
            }) return false
            return true
        }
        val lastDelayedStep = sorted.maxOfOrNull(::delayUntilStep)?.coerceAtMost(recursionLimit - 1) ?: 0
        var step = 0
        while (step < recursionLimit) {
            val candidates = sorted.filter { it.id !in active && eligible(it, step) }
            val grouped = candidates.flatMap { e -> entryGroup(e).map { it to e } }.groupBy({ it.first }, { it.second })
            grouped.forEach { (name, candidatesInGroup) ->
                if (name !in groupChoice) {
                    val sticky = candidatesInGroup.find { priorEntries[it.id].objectOrEmpty().num("stickyUntil", default = -1) >= turn }
                    val override = candidatesInGroup.filter { it.data.flag("groupOverride", "group_override") }.maxByOrNull { it.order }
                    val weighted = candidatesInGroup.map { it to it.data.decimal("groupWeight", "group_weight", default = 100.0).coerceAtLeast(0.0) }
                    val total = weighted.sumOf { it.second }
                    var draw = if (total > 0) random.nextDouble(total) else 0.0
                    val selected = sticky ?: override ?: weighted.firstOrNull { (_, weight) -> draw -= weight; draw < 0 }?.first ?: candidatesInGroup.first()
                    groupChoice[name] = selected.id
                }
            }
            var added = 0
            candidates.forEach { entry ->
                val membership = entryGroup(entry)
                if (membership.any { it in groups || groupChoice[it] != entry.id }) return@forEach
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
                groups += membership
                added++
                val previous = priorEntries[entry.id].objectOrEmpty()
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
            if (active.size < minActivations && scan < maxDepth) { scan++; continue }
            if (!recursion || (added == 0 && step >= lastDelayedStep)) break
            step++
        }
        val output = active.values.sortedWith(compareBy<Pair<WorldEntry, String>> { it.first.sourceRank }.thenBy { it.first.order })
        output.filter { it.first.position == "outlet" }.groupBy { it.first.outlet }.forEach { (name, chunks) ->
            macros.outlets[name] = chunks.joinToString("\n\n") { it.second }
        }
        diagnostics += "世界书激活 " + active.size + "/" + sorted.size + "，估算 " + spent + "/" + budgetText + " tokens；扫描 " + scan + " 条消息。"
        return WorldActivation(output, JsonObject(session.worldState + mapOf("entries" to JsonObject(nextEntries), "turn" to JsonPrimitive(turn))), diagnostics)
    }
}
