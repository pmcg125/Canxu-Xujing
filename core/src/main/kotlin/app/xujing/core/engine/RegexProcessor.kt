package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

internal enum class TextStage { INPUT, OUTPUT, DISPLAY, WORLDINFO }

internal class RegexProcessor(
    state: AppState,
    session: ChatSession,
    roles: List<RoleCard>,
    private val macros: MacroProcessor,
    private val diagnostics: MutableList<String>,
) {
    private val rules: List<Pair<String, JsonObject>> = buildList {
        state.library.filter { it.enabled && it.kind == "regex" && (it.id in session.regexIds || it.extra.flag("global")) }
            .forEach { document -> addAll(records(document.data, "scripts", "regex_scripts", "regexScripts", "regexes").map { "library" to it.second }) }
        state.library.filter { it.enabled && it.kind == "preset" && it.id == session.presetId }.forEach { document ->
            val extensions = unwrap(document.data as? JsonObject ?: EmptyObject)["extensions"].objectOrEmpty()
            records(extensions.first("regex_scripts", "scripts", "regexScripts", "regexes"), "regex_scripts", "scripts", "regexScripts", "regexes")
                .forEach { (_, rule) -> add("preset:${document.id}" to rule) }
        }
        roles.forEach { role ->
            val data = roleData(role)
            val extensions = data["extensions"].objectOrEmpty()
            listOf(extensions["regex_scripts"], extensions["regexScripts"], data["regex_scripts"], role.raw["regex_scripts"])
                .filterNotNull().forEach { container -> addAll(records(container).map { "role:${role.id}" to it.second }) }
        }
    }.distinctBy { (origin, rule) -> origin + rule.str("id", "scriptName", "name") + rule.str("findRegex", "find_regex") + rule.str("replaceString", "replace_string") }

    fun apply(text: String, stage: TextStage, role: String = "assistant", depth: Int = 0, prompt: Boolean = true, processed: Boolean = false, isEdit: Boolean = false): String {
        var result = text
        fun ruleName(rule: JsonObject, origin: String, index: Int): String = rule.str("scriptName", "name").ifBlank {
            if (origin.startsWith("preset:")) rule.str("id").ifBlank { "#${index + 1}" } else ""
        }
        rules.forEachIndexed { index, (origin, rule) ->
            if (rule.flag("disabled") || !rule.flag("enabled", default = true)) return@forEachIndexed
            val markdownOnly = rule.flag("markdownOnly", "markdown_only")
            val promptOnly = rule.flag("promptOnly", "prompt_only")
            if (isEdit && (!rule.flag("runOnEdit", "run_on_edit") || markdownOnly || promptOnly)) return@forEachIndexed
            if (processed && !markdownOnly && !promptOnly) return@forEachIndexed
            if (stage == TextStage.DISPLAY && promptOnly || stage != TextStage.DISPLAY && markdownOnly) return@forEachIndexed
            if (!prompt && promptOnly) return@forEachIndexed
            val min = rule.num("minDepth", "min_depth", default = -1)
            val max = rule.num("maxDepth", "max_depth", default = -1)
            if (min >= 0 && depth < min || max >= 0 && depth > max) return@forEachIndexed
            val placements = rule.first("placement", "placements")?.strings().orEmpty()
            val expected = when (stage) { TextStage.INPUT -> "1"; TextStage.OUTPUT -> "2"; TextStage.WORLDINFO -> "5"; TextStage.DISPLAY -> if (role == "user") "1" else "2" }
            val symbolic = if (expected == "1") setOf("user", "user_input", "input") else if (expected == "5") setOf("worldinfo", "world_info") else setOf("assistant", "ai_output", "output")
            if (placements.isNotEmpty() && placements.none { it == expected || it.lowercase() in symbolic }) return@forEachIndexed
            var pattern = rule.str("findRegex", "find_regex", "pattern")
            if (pattern.isBlank()) return@forEachIndexed
            val substitution=rule.num("substituteRegex", "substitute_regex")
            if (substitution > 0) pattern = macros.expand(pattern, effects = false, escapeValues = substitution==2)
            try {
                var flags = ""
                if (pattern.startsWith('/')) {
                    val last = pattern.lastIndexOf('/')
                    if (last > 0) { flags = pattern.substring(last + 1); pattern = pattern.substring(1, last) }
                }
                if (flags.any { it !in "gimsuyd" }) diagnostics += "正则 ${ruleName(rule, origin, index)} 包含未知标志。"
                val options = buildSet {
                    if ('i' in flags) add(RegexOption.IGNORE_CASE)
                    if ('m' in flags) add(RegexOption.MULTILINE)
                    if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
                }
                val regex = Regex(pattern, options)
                val replacement = macros.expand(rule.str("replaceString", "replace_string", "replacement"), effects = false)
                val trimStrings = rule.first("trimStrings", "trim_strings").strings().map { macros.expand(it, effects = false) }
                fun clean(value: String): String = trimStrings.fold(value) { current, trim -> if (trim.isEmpty()) current else current.replace(trim, "") }
                fun replacementFor(match: MatchResult): String {
                    val body = replacement.replace("{{match}}", clean(match.value))
                    return Regex("\\$(\\$|&|\\x60|'|\\d{1,2}|<[^>]+>)").replace(body) { token ->
                        when (val id = token.groupValues[1]) {
                            "$" -> "$"
                            "&" -> clean(match.value)
                            "`" -> result.substring(0, match.range.first)
                            "'" -> result.substring(match.range.last + 1)
                            else -> clean(if (id.startsWith('<')) runCatching { match.groups[id.removeSurrounding("<", ">")]?.value.orEmpty() }.getOrDefault("")
                                else match.groups[id.toIntOrNull() ?: 0]?.value.orEmpty())
                        }
                    }
                }
                result = if ('g' in flags || !rule.str("findRegex", "find_regex", "pattern").startsWith('/')) {
                    regex.replace(result, ::replacementFor)
                } else regex.find(result)?.let { result.replaceRange(it.range, replacementFor(it)) } ?: result
            } catch (error: Exception) {
                diagnostics += "正则 ${ruleName(rule, origin, index).ifBlank { "未命名" }} 无法处理：${error.message.orEmpty().take(120)}"
            }
        }
        return result
    }
}
