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
    private val rules: List<JsonObject> = buildList {
        state.library.filter { it.enabled && it.kind == "regex" && (it.id in session.regexIds || it.extra.flag("global")) }
            .forEach { addAll(records(it.data, "scripts", "regex_scripts", "regexScripts", "regexes").map(Pair<String, JsonObject>::second)) }
        roles.forEach { role ->
            val data = roleData(role)
            val extensions = data["extensions"].objectOrEmpty()
            listOf(extensions["regex_scripts"], extensions["regexScripts"], data["regex_scripts"], role.raw["regex_scripts"])
                .filterNotNull().forEach { addAll(records(it).map(Pair<String, JsonObject>::second)) }
        }
    }.distinctBy { it.str("id", "scriptName", "name") + it.str("findRegex", "find_regex") + it.str("replaceString", "replace_string") }

    fun apply(text: String, stage: TextStage, role: String = "assistant", depth: Int = 0, prompt: Boolean = true, processed: Boolean = false): String {
        var result = text
        rules.forEach { rule ->
            if (rule.flag("disabled") || !rule.flag("enabled", default = true)) return@forEach
            val markdownOnly = rule.flag("markdownOnly", "markdown_only")
            val promptOnly = rule.flag("promptOnly", "prompt_only")
            if (processed && !markdownOnly && !promptOnly) return@forEach
            if (stage == TextStage.DISPLAY && promptOnly || stage != TextStage.DISPLAY && markdownOnly) return@forEach
            if (!prompt && promptOnly) return@forEach
            val min = rule.num("minDepth", "min_depth", default = -1)
            val max = rule.num("maxDepth", "max_depth", default = -1)
            if (min >= 0 && depth < min || max >= 0 && depth > max) return@forEach
            val placements = rule.first("placement", "placements")?.strings().orEmpty()
            val expected = when (stage) { TextStage.INPUT -> "1"; TextStage.OUTPUT -> "2"; TextStage.WORLDINFO -> "5"; TextStage.DISPLAY -> if (role == "user") "1" else "2" }
            val symbolic = if (expected == "1") setOf("user", "user_input", "input") else if (expected == "5") setOf("worldinfo", "world_info") else setOf("assistant", "ai_output", "output")
            if (placements.isNotEmpty() && placements.none { it == expected || it.lowercase() in symbolic }) return@forEach
            var pattern = rule.str("findRegex", "find_regex", "pattern")
            if (pattern.isBlank()) return@forEach
            val substitution=rule.num("substituteRegex", "substitute_regex")
            if (substitution > 0) pattern = macros.expand(pattern, effects = false, escapeValues = substitution==2)
            try {
                var flags = ""
                if (pattern.startsWith('/')) {
                    val last = pattern.lastIndexOf('/')
                    if (last > 0) { flags = pattern.substring(last + 1); pattern = pattern.substring(1, last) }
                }
                if (flags.any { it !in "gimsuyd" }) diagnostics += "正则 ${rule.str("scriptName", "name")} 包含未知标志。"
                val options = buildSet {
                    if ('i' in flags) add(RegexOption.IGNORE_CASE)
                    if ('m' in flags) add(RegexOption.MULTILINE)
                    if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
                }
                val regex = Regex(pattern, options)
                val replacement = macros.expand(rule.str("replaceString", "replace_string", "replacement"), effects = false)
                fun replacementFor(match: MatchResult): String {
                    var body = replacement
                    rule.first("trimStrings", "trim_strings").strings().forEach { trim ->
                        body = body.replace("{{match}}", match.value.replace(trim, ""))
                    }
                    body = body.replace("{{match}}", match.value)
                    return Regex("\\$(\\$|&|`|'|\\d{1,2}|<[^>]+>)").replace(body) { token ->
                        when (val id = token.groupValues[1]) {
                            "$" -> "$"
                            "&" -> match.value
                            "`" -> result.substring(0, match.range.first)
                            "'" -> result.substring(match.range.last + 1)
                            else -> if (id.startsWith('<')) runCatching { match.groups[id.removeSurrounding("<", ">")]?.value.orEmpty() }.getOrDefault("")
                                else match.groups[id.toIntOrNull() ?: 0]?.value.orEmpty()
                        }
                    }
                }
                result = if ('g' in flags || !rule.str("findRegex", "find_regex", "pattern").startsWith('/')) {
                    regex.replace(result, ::replacementFor)
                } else regex.find(result)?.let { result.replaceRange(it.range, replacementFor(it)) } ?: result
            } catch (error: Exception) {
                diagnostics += "正则 ${rule.str("scriptName", "name", default = "未命名")} 无法处理：${error.message.orEmpty().take(120)}"
            }
        }
        return result
    }
}
