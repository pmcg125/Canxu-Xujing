package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

/**
 * Opening-lorebook discovery and the recovered jl replay loop. A message owns
 * one full MVU snapshot per swipe; switching a swipe only restores that snapshot.
 */
internal object MvuSession {
    private val bookKinds = setOf("worldbook", "world_book", "lorebook")
    private val macroUser = Regex("(?i)\\{\\{user\\}\\}")
    private val macroChar = Regex("(?i)\\{\\{char\\}\\}")
    private val macroUserTag = Regex("(?i)<USER>")
    private val macroCharTag = Regex("(?i)<BOT>|<CHAR>")

    fun replaceMacros(text: String, user: String, character: String): String = text
        .replace(macroUser, user)
        .replace(macroChar, character)
        .replace(macroUserTag, user)
        .replace(macroCharTag, character)

    fun needsReplay(session: ChatSession): Boolean {
        if (session.messages.isEmpty()) return false
        val version = (session.extra["xujing_replay_version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version < 2) return true
        return session.messages.any { message ->
            message.swipes.size > 1 &&
                ((message.extra["xujing_swipe_variables"] as? JsonArray)?.size ?: 0) < message.swipes.size
        }
    }

    fun hasMvuSignals(state: AppState, session: ChatSession): Boolean {
        fun shaped(value: JsonElement?): Boolean = value is JsonObject &&
            (value["stat_data"] is JsonObject || value["initialized_lorebooks"] is JsonObject ||
                value["initialized_lorebooks"] is JsonArray)
        if (shaped(session.variables)) return true
        if (session.messages.any { message ->
                shaped(message.variables) ||
                    shaped(message.extra["variablesSnapshot"]) ||
                    shaped(message.extra["variables_snapshot"]) ||
                    message.extra["swipes_data"] is JsonArray ||
                    message.extra["mvuRawSwipes"] is JsonArray ||
                    message.extra["mvuUpdateHashes"] is JsonArray ||
                    message.extra["mvuUpdateHash"] is JsonPrimitive
            }) return true
        if (session.messages.any { message ->
                rawTexts(message).any { it.contains("<initvar", ignoreCase = true) }
            }) return true
        return openingBooks(state, session).isNotEmpty()
    }

    fun openingBooks(state: AppState, session: ChatSession): List<InitLorebook> {
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val primaryRole = roles.firstOrNull()
        val books = mutableListOf<InitLorebook>()
        val seen = mutableSetOf<String>()
        fun add(
            id: String, kind: String, primary: Boolean,
            entries: List<JsonObject>, charName: String?, schema: JsonElement? = null,
        ) {
            if (id.isBlank() || !seen.add(id)) return
            val initEntries = entries.mapIndexedNotNull { index, entry ->
                if (!hasInitVarMarker(entry)) null else InitLorebookEntry(
                    entryId = entryIdentity(entry, index),
                    content = entryContent(entry),
                    charName = charName,
                )
            }
            val mapped = initEntries.toMutableList()
            if (schema != null) mapped += InitLorebookEntry("\$schema", "", schema, charName)
            if (mapped.isNotEmpty()) books += InitLorebook(id, kind, primary, mapped)
        }
        fun addDocument(reference: String, kind: String, primary: Boolean, charName: String?, schema: JsonElement? = null) {
            val document = resolveDocument(state, session.id, reference) ?: return
            val root = documentRoot(document)
            if (!bookEnabled(root)) return
            add(
                document.name.ifBlank { document.id }, kind, primary,
                entriesOf(root), charName, if (primary) schema else null,
            )
        }
        fun addEmbedded(value: JsonElement?, fallbackId: String, charName: String, schema: JsonElement?, primary: Boolean = true) {
            val root = value as? JsonObject ?: return
            if (!bookEnabled(root)) return
            val name = root.text("name", fallbackId).ifBlank { fallbackId }
            add(name, "character", primary, entriesOf(root), charName, if (primary) schema else null)
        }
        fun addRole(role: RoleCard) {
            val data = roleData(role)
            val extensions = data["extensions"].objectOrEmpty()
            val schema = roleSchema(role)
            var schemaAttached = false
            val primaryReference = extensions.str("world").ifBlank {
                data.str("world", "worldbook", "world_book")
            }
            if (primaryReference.isNotBlank()) {
                val before = books.size
                addDocument(primaryReference, "character", true, role.name, schema)
                schemaAttached = books.size > before
            }
            val embedded = listOf(
                data["character_book"], data["characterBook"], data["worldbook"], data["world_book"],
                extensions["character_book"],
            )
            embedded.forEachIndexed { index, value ->
                val before = books.size
                addEmbedded(value, "character:book-$index", role.name, schema)
                schemaAttached = schemaAttached || books.size > before
            }
            val additional = extensions["tavern_helper"].objectOrEmpty()["additional_worldbooks"].strings() +
                extensions["world_info"].objectOrEmpty()["extraBooks"].strings() +
                extensions["world_info"].objectOrEmpty()["extra_books"].strings() +
                extensions["charLore"].strings()
            additional.filter(String::isNotBlank).distinct().forEach { reference ->
                addDocument(reference, "character", false, role.name)
            }
            if (schema != null && !schemaAttached) {
                add("character-schema:${role.id}", "character", true, emptyList(), role.name, schema)
            }
        }
        roles.forEach(::addRole)
        val globalNames = session.extra["globalWorldBookNames"].strings()
        globalNames.filter(String::isNotBlank).forEach { addDocument(it, "global", false, primaryRole?.name) }
        val chatName = session.extra.str("chatWorldBookName")
        if (chatName.isNotBlank()) addDocument(chatName, "chat", false, primaryRole?.name)
        session.worldbookIds.filter(String::isNotBlank).forEach { reference ->
            addDocument(reference, "chat", false, primaryRole?.name)
        }
        state.library.filter {
            it.enabled && it.kind in bookKinds &&
                (it.extra.text("xujing_session_id").isEmpty() || it.extra.text("xujing_session_id") == session.id) &&
                it.extra.flag("global", "isGlobal", "is_global")
        }.forEach { document ->
            val root = documentRoot(document)
            if (bookEnabled(root)) add(document.name.ifBlank { document.id }, "global", false, entriesOf(root), primaryRole?.name)
        }
        return books
    }

    fun initialSnapshot(state: AppState, session: ChatSession, text: String, roleId: String?): Pair<JsonObject, List<String>> {
        val role = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
            .find { it.id == roleId } ?: session.roleIds.firstOrNull()?.let { id -> state.roles.find { it.id == id } }
        val user = session.userName.ifBlank { "我" }
        val character = role?.name.orEmpty()
        val expanded = replaceMacros(text, user, character)
        val books = openingBooks(state, session).map { book ->
            book.copy(entries = book.entries.map { entry ->
                if (entry.schema != null) entry else entry.copy(content = replaceMacros(entry.content, user, entry.charName ?: character))
            })
        }
        val initialized = MvuInit.initialize(MvuInit.defaultState(), books, expanded)
        val diagnostics = mutableListOf<String>()
        val updated = VariableUpdates.apply(expanded, initialized, diagnostics)
        return updated.variables to diagnostics.distinct()
    }

    fun replay(state: AppState, session: ChatSession, startIndex: Int = 0): MvuReplayResult {
        val messages = session.messages
        if (messages.isEmpty()) return MvuReplayResult(messages, session.variables, emptyList())
        val start = startIndex.coerceIn(0, messages.size)
        val diagnostics = mutableListOf<String>()
        val roles = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
        val user = session.userName.ifBlank { "我" }
        val books = openingBooks(state, session).map { book ->
            book.copy(entries = book.entries.map { entry ->
                if (entry.schema != null) entry else entry.copy(content = replaceMacros(entry.content, user, entry.charName ?: ""))
            })
        }
        var current = if (start > 0) snapshotBefore(messages, start) else MvuInit.defaultState()
        val updated = messages.toMutableList()
        for (index in start until messages.size) {
            val message = messages[index]
            val role = roles.find { it.id == message.speakerId } ?: roles.firstOrNull()
            val character = role?.name.orEmpty()
            val raws = rawSwipes(message)
            val opening = index == 0 && isOpening(message)
            val snapshots = raws.map { raw ->
                val expanded = replaceMacros(raw, user, character)
                val base = if (opening) MvuInit.initialize(MvuInit.defaultState(), books, expanded) else stripTransient(current)
                VariableUpdates.apply(expanded, base, diagnostics).variables
            }.ifEmpty { listOf(stripTransient(current)) }
            val selected = message.swipeIndex.coerceIn(0, snapshots.lastIndex)
            val snapshot = snapshots[selected]
            updated[index] = message.copy(
                variables = snapshot,
                extra = JsonObject(message.extra + ("xujing_swipe_variables" to JsonArray(snapshots.map { MvuInit.clone(it) }))),
            )
            current = snapshot
        }
        return MvuReplayResult(updated, current, diagnostics.distinct())
    }

    fun parseMessage(state: AppState, session: ChatSession, text: String, oldData: JsonObject, roleId: String?): JsonObject {
        val role = session.roleIds.mapNotNull { id -> state.roles.find { it.id == id } }
            .find { it.id == roleId } ?: session.roleIds.firstOrNull()?.let { id -> state.roles.find { it.id == id } }
        val expanded = replaceMacros(text, session.userName.ifBlank { "我" }, role?.name.orEmpty())
        val diagnostics = mutableListOf<String>()
        return VariableUpdates.apply(expanded, MvuInit.normalize(oldData), diagnostics).variables
    }

    fun reloadInitVar(state: AppState, session: ChatSession, data: JsonObject): Pair<JsonObject, Boolean> {
        val user = session.userName.ifBlank { "我" }
        val books = openingBooks(state, session).map { book ->
            book.copy(entries = book.entries.map { entry ->
                if (entry.schema != null) entry else entry.copy(content = replaceMacros(entry.content, user, entry.charName ?: ""))
            })
        }
        val base = MvuInit.normalize(data)
        val next = MvuInit.initialize(base, books, "")
        return next to (next != base)
    }

    private fun stripTransient(state: JsonObject): JsonObject = MvuInit.normalize(
        JsonObject(state.filterKeys { it != "snapshot" && it != "ignore_cleanup" })
    )

    private fun rawSwipes(message: ChatMessage): List<String> {
        val swipes = message.swipes
        val sources = (message.extra["xujing_swipe_sources"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (!sources.isNullOrEmpty() && swipes.isNotEmpty() && sources.size == swipes.size) return sources
        val raw = (message.extra["mvuRawSwipes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (!raw.isNullOrEmpty() && swipes.isNotEmpty() && raw.size == swipes.size) return raw
        if (swipes.isNotEmpty()) return swipes
        return listOf(message.extra.text("sourceText").ifBlank { message.content })
    }

    private fun rawTexts(message: ChatMessage): List<String> {
        val values = mutableListOf(message.content)
        values += message.swipes
        (message.extra["xujing_swipe_sources"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(values::add) }
        (message.extra["mvuRawSwipes"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.contentOrNull?.let(values::add) }
        message.extra.text("sourceText").takeIf(String::isNotBlank)?.let(values::add)
        return values
    }

    private fun isOpening(message: ChatMessage): Boolean {
        val explicit = (message.extra["isOpening"] as? JsonPrimitive)?.booleanOrNull
        return explicit ?: (message.role == "assistant")
    }

    private fun snapshotBefore(messages: List<ChatMessage>, start: Int): JsonObject {
        for (index in (start - 1) downTo 0) {
            val variables = CandidateSnapshots.variablesFor(messages[index])
            if (variables["stat_data"] is JsonObject || variables["initialized_lorebooks"] is JsonObject ||
                variables["initialized_lorebooks"] is JsonArray) return stripTransient(variables)
        }
        return MvuInit.defaultState()
    }

    private fun resolveDocument(state: AppState, sessionId: String, reference: String): LibraryDocument? {
        if (reference.isBlank()) return null
        val visible = state.library.filter {
            it.enabled && it.kind in bookKinds &&
                (it.extra.text("xujing_session_id").isEmpty() || it.extra.text("xujing_session_id") == sessionId)
        }
        visible.firstOrNull { it.id == reference }?.let { return it }
        val matches = visible.filter { it.name == reference }
        return matches.firstOrNull { it.extra.text("xujing_session_id") == sessionId } ?: matches.firstOrNull()
    }

    private fun documentRoot(document: LibraryDocument): JsonObject = when (val data = document.data) {
        is JsonArray -> buildJsonObject { put("entries", data) }
        is JsonObject -> {
            val nested = data["data"] as? JsonObject
            if (nested != null && data["entries"] !is JsonArray && data["entries"] !is JsonObject &&
                (nested["entries"] is JsonArray || nested["entries"] is JsonObject)) nested else data
        }
        else -> EmptyObject
    }

    private fun entriesOf(root: JsonObject): List<JsonObject> {
        val container = root["entries"] ?: root["world_book_entries"] ?: root["worldBookEntries"]
        return when (container) {
            is JsonArray -> container.filterIsInstance<JsonObject>()
            is JsonObject -> container.values.filterIsInstance<JsonObject>()
            else -> emptyList()
        }
    }

    private fun bookEnabled(root: JsonObject): Boolean =
        !root.flag("disable", "disabled") && root.flag("enabled", default = true)

    private fun hasInitVarMarker(entry: JsonObject): Boolean =
        listOf("name", "comment", "title").mapNotNull { key ->
            (entry[key] as? JsonPrimitive)?.contentOrNull
        }.plus(entry["keys"].strings()).plus(entry["key"].strings())
            .any { it.contains("[initvar]", ignoreCase = true) }

    private fun entryIdentity(entry: JsonObject, index: Int): String =
        entry.text("name").ifBlank { entry.text("comment") }.ifBlank {
            entry.text("title").ifBlank { entry.text("uid", entry.text("id", index.toString())) }
        }

    private fun entryContent(entry: JsonObject): String =
        entry.text("content").ifBlank { entry.text("value") }.ifBlank { entry.text("text") }

    private fun roleSchema(role: RoleCard): JsonElement? {
        val data = roleData(role)
        val extensions = data["extensions"].objectOrEmpty()
        val candidates = listOf(
            data["schema"], data["mvu_schema"], data["mvuSchema"],
            extensions["schema"], extensions["mvu_schema"], extensions["mvuSchema"],
            extensions["characterSchema"], extensions["character_schema"],
        )
        for (value in candidates) {
            when (value) {
                is JsonObject -> return value
                is JsonPrimitive -> if (value.isString) {
                    val parsed = runCatching { AppJson.parseToJsonElement(value.content) }.getOrNull()
                    if (parsed is JsonObject) return parsed
                }
                else -> Unit
            }
        }
        return null
    }
}

data class MvuReplayResult(val messages: List<ChatMessage>, val variables: JsonObject, val diagnostics: List<String>)
