package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * Focused M1-01/02/03 checks for character/mode filters, scan-depth inheritance and group scoring.
 *
 * These are deterministic source-level checks with a fixed Random. They do not replace the
 * emulator/API acceptance run in docs/development-goals-2026-11-06.md.
 */
object WorldbookM1Checks {
    fun run() {
        characterAndModeFilters()
        scanDepthInheritance()
        groupScoring()
        println("WorldbookM1Checks passed: characterFilter, triggers, scan-depth inheritance and group scoring")
    }

    private fun entry(
        uid: Int,
        content: String,
        key: String? = null,
        secondary: String? = null,
        constant: Boolean = false,
        scanDepth: Int? = null,
        group: String? = null,
        useGroupScoring: Boolean? = null,
        filter: JsonObject? = null,
        triggers: List<String> = emptyList(),
    ) = buildJsonObject {
        put("uid", uid)
        put("content", content)
        put("comment", content)
        if (key != null) put("key", buildJsonArray { add(JsonPrimitive(key)) })
        if (secondary != null) put("keysecondary", buildJsonArray { add(JsonPrimitive(secondary)) })
        put("constant", constant)
        if (scanDepth != null) put("scanDepth", scanDepth)
        if (group != null) put("group", group)
        if (useGroupScoring != null) put("useGroupScoring", useGroupScoring)
        if (filter != null) put("characterFilter", filter)
        if (triggers.isNotEmpty()) put("triggers", buildJsonArray { triggers.forEach { add(JsonPrimitive(it)) } })
    }

    private fun book(id: String, entries: List<JsonObject>, scanDepth: Int? = null) = LibraryDocument(
        id = id, name = id, kind = "worldbook", data = buildJsonObject {
            put("name", id)
            put("enabled", true)
            if (scanDepth != null) put("scan_depth", scanDepth)
            put("entries", buildJsonArray { entries.forEach { add(it) } })
        },
    )

    private fun role() = RoleCard(
        id = "role-m1", name = "M1 Role", raw = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", buildJsonObject {
                put("name", "M1 Role")
                put("tags", buildJsonArray { add(JsonPrimitive("alpha")) })
            })
        },
    )

    private fun activate(
        books: List<LibraryDocument>,
        entries: List<String> = books.map { it.id },
        messages: List<ChatMessage> = listOf(ChatMessage(role = "user", content = "hello")),
        extra: JsonObject = EmptyObject,
        mode: String = "normal",
    ): WorldActivation {
        val role = role()
        val session = ChatSession(
            id = "session-m1", roleIds = listOf(role.id), worldbookIds = entries,
            messages = messages, extra = extra,
        )
        val state = AppState(roles = listOf(role), library = books, sessions = listOf(session))
        val diagnostics = mutableListOf<String>()
        val macro = MacroProcessor(state, session, role, EmptyObject, diagnostics, Random(0))
        return WorldbookEngine.activate(state, session, listOf(role), macro, 16384, Random(0), mode, role)
    }

    private fun characterAndModeFilters() {
        val include = buildJsonObject { put("names", buildJsonArray { add(JsonPrimitive("M1 Role")) }) }
        val excludeByName = buildJsonObject { put("names", buildJsonArray { add(JsonPrimitive("Other Role")) }) }
        val byTag = buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("alpha")) }) }
        val excludeByTag = buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("beta")) }) }
        val filterBook = book("filters", listOf(
            entry(1, "CF-INCLUDE", constant = true, filter = include),
            entry(2, "CF-EXCLUDE-NAME", constant = true, filter = excludeByName),
            entry(3, "CF-TAG", constant = true, filter = byTag),
            entry(4, "CF-EXCLUDE-TAG", constant = true, filter = excludeByTag),
            entry(5, "TRIG-NORMAL", constant = true, triggers = listOf("normal")),
            entry(6, "TRIG-REGEN", constant = true, triggers = listOf("regenerate")),
        ))

        val normalTitles = activate(listOf(filterBook), mode = "normal").entries.map { it.first.title }
        check("CF-INCLUDE" in normalTitles) { "characterFilter 名称匹配应激活：$normalTitles" }
        check("CF-TAG" in normalTitles) { "characterFilter 标签匹配应激活：$normalTitles" }
        check("CF-EXCLUDE-NAME" !in normalTitles) { "未匹配的 characterFilter 应排除：$normalTitles" }
        check("CF-EXCLUDE-TAG" !in normalTitles) { "未匹配的标签过滤应排除：$normalTitles" }
        check("TRIG-NORMAL" in normalTitles) { "normal trigger 应激活：$normalTitles" }
        check("TRIG-REGEN" !in normalTitles) { "regenerate trigger 不应在 normal 模式激活：$normalTitles" }
        val normalContents = activate(listOf(filterBook), mode = "normal").entries.map { it.second }
        check(normalContents.none { it.contains("CF-EXCLUDE") }) { "被过滤内容不得泄漏：$normalContents" }

        val regenTitles = activate(listOf(filterBook), mode = "regenerate").entries.map { it.first.title }
        check("TRIG-REGEN" in regenTitles) { "regenerate trigger 应激活：$regenTitles" }
        check("TRIG-NORMAL" !in regenTitles) { "normal trigger 不应在 regenerate 模式激活：$regenTitles" }

        val summaryTitles = activate(listOf(filterBook), mode = "summary").entries.map { it.first.title }
        check("TRIG-NORMAL" !in summaryTitles && "TRIG-REGEN" !in summaryTitles) {
            "summary 模式不应命中 normal/regenerate trigger：$summaryTitles"
        }
    }

    private fun scanDepthInheritance() {
        val messages = listOf(
            ChatMessage(role = "user", content = "NEEDLE old"),
            ChatMessage(role = "assistant", content = "plain reply"),
        )
        val depthBook = book("depth", listOf(
            entry(10, "SCAN-0", key = "NEEDLE", scanDepth = 0),
            entry(11, "SCAN-1", key = "NEEDLE", scanDepth = 1),
            entry(12, "SCAN-2", key = "NEEDLE", scanDepth = 2),
        ))
        val depthTitles = activate(listOf(depthBook), messages = messages).entries.map { it.first.title }
        check("SCAN-2" in depthTitles) { "scanDepth=2 应看到两条历史：$depthTitles" }
        check("SCAN-1" !in depthTitles) { "scanDepth=1 不应看到更早的 NEEDLE：$depthTitles" }
        check("SCAN-0" !in depthTitles) { "显式 scanDepth=0 不应扫描历史：$depthTitles" }

        val shallow = book("book-shallow", listOf(entry(20, "BOOK-SHALLOW", key = "NEEDLE")), scanDepth = 1)
        val deep = book("book-deep", listOf(entry(21, "BOOK-DEEP", key = "NEEDLE")), scanDepth = 2)
        val bookTitles = activate(listOf(shallow, deep), messages = messages).entries.map { it.first.title }
        check("BOOK-DEEP" in bookTitles) { "书级 scan_depth=2 应继承：$bookTitles" }
        check("BOOK-SHALLOW" !in bookTitles) { "书级 scan_depth=1 应覆盖全局默认：$bookTitles" }

        val globalZero = buildJsonObject { put("worldInfo", buildJsonObject { put("scanDepth", 0) }) }
        val globalBook = book("global", listOf(
            entry(30, "GLOBAL-INHERIT", key = "NEEDLE"),
            entry(31, "GLOBAL-EXPLICIT", key = "NEEDLE", scanDepth = 2),
        ))
        val globalTitles = activate(listOf(globalBook), messages = messages, extra = globalZero).entries.map { it.first.title }
        check("GLOBAL-INHERIT" !in globalTitles) { "全局显式 0 不能被当成未设置：$globalTitles" }
        check("GLOBAL-EXPLICIT" in globalTitles) { "条目显式 scanDepth 应覆盖全局 0：$globalTitles" }
    }

    private fun groupScoring() {
        val groupBook = book("groups", listOf(
            entry(40, "GROUP-APPLE", key = "APPLE", secondary = "RED", group = "g", useGroupScoring = true),
            entry(41, "GROUP-BANANA", key = "BANANA", group = "g", useGroupScoring = true),
        ))
        val messages = listOf(ChatMessage(role = "user", content = "APPLE RED BANANA"))
        val scoringOn = buildJsonObject { put("worldInfo", buildJsonObject { put("useGroupScoring", true) }) }
        val titles = activate(listOf(groupBook), messages = messages, extra = scoringOn).entries.map { it.first.title }
        check(titles == listOf("GROUP-APPLE")) { "组评分应只保留主关键词得分最高条目：$titles" }

        val scoringOff = activate(listOf(groupBook), messages = messages).entries.map { it.first.title }
        check(scoringOff.size == 1) { "评分关闭时同组仍只应选中一条：$scoringOff" }
    }
}
