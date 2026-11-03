package app.xujing.core.compat

import app.xujing.core.*
import kotlinx.serialization.json.*

/** Pure merge/relinking shared by Android import and compatibility checks. */
object ImportMerger {
    /** Only complete media-key values are replaced; embedded HTML/CSS remains untouched. */
    fun rewriteMedia(bundle: ImportBundle, paths: Map<String, String>): ImportBundle {
        fun rewrite(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { rewrite(it.value) })
            is JsonArray -> JsonArray(value.map(::rewrite))
            is JsonPrimitive -> if (value.isString) paths[value.content]?.let(::JsonPrimitive) ?: value else value
            else -> value
        }
        return bundle.copy(
            roles = bundle.roles.map { role -> role.copy(
                avatarPath = role.avatarPath?.let { paths[it] ?: it }, raw = rewrite(role.raw).jsonObject,
            ) },
            sessions = bundle.sessions.map { AppJson.decodeFromJsonElement(rewrite(AppJson.encodeToJsonElement(it))) },
            library = bundle.library.map { it.copy(data = rewrite(it.data), extra = rewrite(it.extra).jsonObject) },
            compatibility = rewrite(bundle.compatibility).jsonObject,
        )
    }

    fun merge(old: AppState, bundle: ImportBundle, kind: String = "auto", activeSessionId: String? = null): AppState {
        val own = bundle.compatibility["xujingState"] as? JsonObject
        val settings = bundle.compatibility["xujingSettings"] as? JsonObject
        val restored = own?.let { AppJson.decodeFromJsonElement<AppState>(it) }
        val roleIds = allocateIds(old.roles.map { it.id }, bundle.roles.map { it.id })
        val libraryIds = allocateIds(old.library.map { it.id }, bundle.library.map { it.id })
        val active = old.sessions.firstOrNull { it.id == activeSessionId }
        val sessionIds = if (kind == "chat" && active != null && bundle.sessions.size == 1)
            mapOf(bundle.sessions.single().id to active.id)
        else allocateIds(old.sessions.map { it.id }, bundle.sessions.map { it.id })
        val extraConnections = settings?.get("connections")?.let {
            runCatching { AppJson.decodeFromJsonElement<List<ApiConnection>>(it) }.getOrNull()
        }.orEmpty()
        val incomingConnections = restored?.connections.orEmpty().ifEmpty { extraConnections }
        val connectionIds = allocateIds(old.connections.map { it.id }, incomingConnections.map { it.id })
        fun documentId(id: String, documentKind: String) = libraryIds[id] ?: libraryIds["$documentKind:$id"]
            ?: old.library.firstOrNull { it.kind == documentKind && it.extra.text("piupiuId") == id }?.id ?: id
        val roles = bundle.roles.map { it.copy(id = roleIds.getValue(it.id), raw = remapMembers(it.raw, roleIds)) }
        val documents = bundle.library.map { document ->
            val scope=document.extra.text("xujing_session_id")
            val extra=if(scope.isNotEmpty() && sessionIds.containsKey(scope)) JsonObject(document.extra+("xujing_session_id" to JsonPrimitive(sessionIds.getValue(scope)))) else document.extra
            document.copy(id=libraryIds.getValue(document.id),extra=extra)
        }
        fun sessionExtra(extra: JsonObject) = JsonObject(extra.toMutableMap().apply {
            (extra["styleIds"] as? JsonArray)?.let { values ->
                put("styleIds", JsonArray(values.map { JsonPrimitive(documentId(it.jsonPrimitive.content, "style")) }))
            }
            extra.text("personaId").takeIf { it.isNotBlank() }?.let { put("personaId", JsonPrimitive(documentId(it, "persona"))) }
            extra.text("_piupiuBackupRoleId").let { id -> roleIds[id]?.let { put("_piupiuBackupRoleId", JsonPrimitive(it)) } }
        })
        val nextSessions = if (kind == "chat" && active != null && bundle.sessions.size == 1) {
            val messages = bundle.sessions.single().messages.map { it.copy(id = newId(), speakerId = it.speakerId?.let { id -> roleIds[id] ?: id }) }
            old.sessions.map { if (it.id == active.id) it.copy(messages = messages, summary = "", worldState = EmptyObject,
                variables = messages.lastOrNull()?.variables ?: EmptyObject, extra = JsonObject(it.extra - "xujing_summary_through_message_id")) else it }
        } else {
            old.sessions + bundle.sessions.map { session ->
                val messageIds = session.messages.map { newId() }
                val extra = sessionExtra(session.extra).toMutableMap()
                val cursor = session.extra.text("xujing_summary_through_message_id")
                if (cursor.isNotEmpty()) {
                    val matches = session.messages.indices.filter { session.messages[it].id == cursor }
                    if (matches.size == 1) extra["xujing_summary_through_message_id"] = JsonPrimitive(messageIds[matches.single()])
                    else extra.remove("xujing_summary_through_message_id")
                }
                session.copy(
                id = sessionIds.getValue(session.id), roleIds = session.roleIds.map { roleIds[it] ?: it },
                connectionId = session.connectionId?.let { connectionIds[it] ?: it },
                summaryConnectionId = session.summaryConnectionId?.let { connectionIds[it] ?: it },
                presetId = session.presetId?.let { documentId(it, "preset") },
                worldbookIds = session.worldbookIds.map { documentId(it, "worldbook") },
                regexIds = session.regexIds.map { documentId(it, "regex") }, extra = JsonObject(extra),
                messages = session.messages.mapIndexed { index, message -> message.copy(id = messageIds[index], speakerId = message.speakerId?.let { roleIds[it] ?: it }) },
            ) }
        }
        val piupiu = bundle.compatibility.obj("piupiuSettings").obj("settings")
        val preferences = restored?.preferences ?: settings?.get("preferences")?.let {
            runCatching { AppJson.decodeFromJsonElement<UiPreferences>(it) }.getOrNull()
        } ?: if (piupiu.isNotEmpty()) old.preferences.copy(userName = piupiu.obj("profile").text("username", old.preferences.userName)) else null
        val importedEmbedding = restored?.embedding ?: settings?.get("embedding")?.let {
            runCatching { AppJson.decodeFromJsonElement<EmbeddingSettings>(it) }.getOrNull()
        }
        // PiuPiu stores dynamically created worldbooks inside session JSON. Recreate
        // scoped documents so scripts and the prompt engine see the same books.
        val known=old.library+documents
        val dynamicDocuments=nextSessions.filter { it.id in sessionIds.values }.flatMap { session ->
            val seen=known.filter { it.kind in setOf("worldbook","world_book","lorebook") && it.extra.text("xujing_session_id")==session.id }.map { it.name }.toMutableSet()
            session.extra.obj("dynamicWorldBooks").mapNotNull { (key,value) ->
                val book=value as? JsonObject ?: return@mapNotNull null
                val name=book.text("name",key).ifBlank { key }
                if(!seen.add(name)) null
                else LibraryDocument(name=name,kind="worldbook",data=book,extra=buildJsonObject { put("xujing_session_id",session.id); put("xujing_original_worldbook_key",key) })
            }
        }
        return old.copy(
            roles = old.roles + roles, sessions = nextSessions, library = old.library + documents + dynamicDocuments,
            connections = old.connections + incomingConnections.map { it.copy(id = connectionIds.getValue(it.id)) },
            preferences = preferences?.copy(defaultConnectionId = preferences.defaultConnectionId?.let { connectionIds[it] ?: it }) ?: old.preferences,
            embedding = importedEmbedding?.copy(localModelId = old.embedding.localModelId,
                connectionId = importedEmbedding.connectionId?.let { connectionIds[it] ?: it }) ?: old.embedding,
            compatibility = JsonObject(old.compatibility + (bundle.compatibility - "xujingState")),
        )
    }

    private fun allocateIds(existing: List<String>, incoming: List<String>): Map<String, String> {
        require(incoming.distinct().size == incoming.size) { "导入资料包含重复 ID" }
        val reserved = (existing + incoming).toMutableSet()
        return incoming.associateWith { id ->
            if (id !in existing) id else generateSequence(::newId).first { reserved.add(it) }
        }
    }

    /** Relink the supported card envelopes, without changing custom fields with coincidental ID text. */
    private fun remapMembers(raw: JsonObject, ids: Map<String, String>): JsonObject {
        val fields = raw.toMutableMap()
        (raw["memberIds"] as? JsonArray)?.let { members ->
            fields["memberIds"] = JsonArray(members.map { value ->
                (value as? JsonPrimitive)?.contentOrNull?.let { ids[it] }?.let(::JsonPrimitive) ?: value
            })
        }
        (raw["data"] as? JsonObject)?.let { fields["data"] = remapMembers(it, ids) }
        return JsonObject(fields)
    }
}
