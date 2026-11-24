package app.xujing.core.compat

import app.xujing.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** Original implementation of exchange formats observed in PiuPiu 2.7.9. No original JS is executed. */
object CompatibilityCodec {
    private const val PIUPIU_BACKUP = "piupiu_role_chat_backup"
    private const val PIUPIU_SETTINGS = "piupiu_settings_backup"
    private const val EXCHANGE = "xujing_exchange"
    private val compactJson = Json(AppJson) { prettyPrint = false }

    fun decode(fileName: String, bytes: ByteArray, kind: String = "auto"): ImportBundle {
        require(bytes.isNotEmpty() && bytes.size <= ExchangeLimits.MAX_FILE) { "文件为空或超过 256 MiB" }
        return when {
            PngCards.isPng(bytes) -> decodePng(fileName, bytes)
            bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte() -> decodeBackup(bytes)
            else -> {
                require(bytes.size <= ExchangeLimits.MAX_JSON) { "JSON 文件超过 32 MiB" }
                val parsed = AppJson.parseToJsonElement(ExchangeLimits.utf8(bytes))
                decodeJson(fileName, parsed, kind.lowercase())
            }
        }
    }

    fun exportRole(role: RoleCard, avatarPng: ByteArray? = null): ByteArray {
        val original = updatedCard(role)
        val v2 = JsonObject(original.toMutableMap().apply {
            put("spec", JsonPrimitive("chara_card_v2")); put("spec_version", JsonPrimitive("2.0"))
        })
        val preservedV3 = role.raw.obj(EXCHANGE).obj("pngCards")["ccv3"] as? JsonObject
        val v3 = when {
            original.text("spec") in setOf("chara_card_v3", "character_card_v3") -> original
            preservedV3 != null -> updatedCard(role.copy(raw = preservedV3))
            else -> null
        }
        fun encoded(value: JsonObject) = Base64.getEncoder().encodeToString(compactJson.encodeToString(value).toByteArray(Charsets.UTF_8))
        return PngCards.insert(avatarPng, encoded(v2), v3?.let(::encoded))
    }

    /** The original chat importer accepts a message array or an object with a history array. */
    fun exportChat(session: ChatSession): ByteArray = jsonBytes(JsonObject(session.extra.toMutableMap().apply {
        remove("_piupiuBackupSession"); remove("_piupiuBackupRoleId")
        put("app", JsonPrimitive("PiuPiuChat")); put("history", JsonArray(session.messages.map { exportMessage(it) }))
        put("xujing_session", AppJson.encodeToJsonElement(session))
    }))

    fun exportDocument(document: LibraryDocument): ByteArray = jsonBytes(
        if (document.kind == "preset") PresetDocuments.body(document.data) else document.data
    )

    /** PiuPiu settings remain in their original envelope; Xujing preferences use an extension. */
    fun exportSettings(state: AppState): ByteArray {
        val old = state.compatibility["piupiuSettings"] as? JsonObject ?: EmptyObject
        val settings = old.obj("settings").toMutableMap()
        val profile = (settings["profile"] as? JsonObject ?: EmptyObject).toMutableMap()
        profile["username"] = JsonPrimitive(state.preferences.userName)
        val personas = state.library.filter { it.kind == "persona" }
        if (personas.isNotEmpty()) {
            val personaIds = numericIds(personas.map { it.id })
            profile["personaList"] = JsonArray(personas.map { doc ->
                val value = doc.data as? JsonObject ?: EmptyObject
                JsonObject(value.toMutableMap().apply {
                    put("id", JsonPrimitive(personaIds.getValue(doc.id)))
                    put("name", JsonPrimitive(doc.name))
                    put("text", JsonPrimitive(documentText(doc)))
                })
            })
            // Imported IDs are namespaced locally and may be reassigned on merge. Resolve the
            // original active persona through its preserved source ID before exporting new IDs.
            val activeId = (profile["activePersonaId"] as? JsonPrimitive)?.contentOrNull
            val active = personas.firstOrNull { document ->
                activeId != null && activeId in setOf(document.id, document.extra.text("piupiuId"), (document.data as? JsonObject)?.text("id"))
            } ?: personas.firstOrNull { state.preferences.persona.isNotBlank() && documentText(it) == state.preferences.persona }
            if (active != null) profile["activePersonaId"] = JsonPrimitive(personaIds.getValue(active.id))
            else profile.remove("activePersonaId")
        } else if (state.preferences.persona.isNotBlank()) {
            profile["personaList"] = buildJsonArray { add(buildJsonObject { put("id", 1); put("text", state.preferences.persona) }) }
            profile["activePersonaId"] = JsonPrimitive(1)
        } else {
            profile["personaList"] = JsonArray(emptyList())
            profile.remove("activePersonaId")
        }
        settings["profile"] = JsonObject(profile)
        val appearance = (settings["appearance"] as? JsonObject ?: EmptyObject).toMutableMap()
        // A system-following mode is kept in the extension; PiuPiu only accepts this boolean.
        appearance["darkMode"] = JsonPrimitive(state.preferences.darkMode == "dark")
        settings["appearance"] = JsonObject(appearance)
        fun documents(kind: String): JsonArray {
            val docs = state.library.filter { it.kind == kind && it.extra.text("xujing_session_id").isEmpty() }
            val ids = numericIds(docs.map { it.id })
            return JsonArray(docs.map { document ->
                if (kind == "preset") {
                    val envelope = document.extra["piupiuPresetEnvelope"] as? JsonObject
                        ?: (document.data as? JsonObject)?.takeIf { it["preset"] is JsonObject } ?: EmptyObject
                    return@map JsonObject(envelope.toMutableMap().apply {
                        put("id", JsonPrimitive(ids.getValue(document.id)))
                        put("name", JsonPrimitive(document.name))
                        put("preset", PresetDocuments.body(document.data))
                    })
                }
                val value = document.data as? JsonObject ?: buildJsonObject {
                    put(if (kind == "style") "content" else "data", document.data)
                }
                JsonObject(value.toMutableMap().apply {
                    put("id", JsonPrimitive(ids.getValue(document.id)))
                    put("name", JsonPrimitive(document.name))
                })
            })
        }
        // Empty lists are authoritative too: retaining an old list revives deleted materials
        // when this settings file is imported into PiuPiu or without the Xujing extension.
        settings["globalWorldBooks"] = documents("worldbook")
        settings["globalPresets"] = documents("preset")
        settings["globalRegexConfigs"] = documents("regex")
        if (settings.containsKey("globalWorldBook")) settings["globalWorldBook"] = settings.getValue("globalWorldBooks")
        if (settings.containsKey("globalRegex")) settings["globalRegex"] = settings.getValue("globalRegexConfigs")
        val reply = (settings["reply"] as? JsonObject ?: EmptyObject).toMutableMap()
        val style = (reply["style"] as? JsonObject ?: EmptyObject).toMutableMap()
        style["customStyles"] = documents("style")
        reply["style"] = JsonObject(style)
        settings["reply"] = JsonObject(reply)
        val result = JsonObject(old.toMutableMap().apply {
            put("app", JsonPrimitive(PIUPIU_SETTINGS)); put("schemaVersion", JsonPrimitive(1))
            put("exportedAt", JsonPrimitive(Instant.now().toString())); put("settings", JsonObject(settings))
            put("xujing", buildJsonObject {
                put("schemaVersion", 1)
                put("preferences", AppJson.encodeToJsonElement(state.preferences))
                put("embedding", AppJson.encodeToJsonElement(state.embedding))
                put("library", AppJson.encodeToJsonElement(state.library))
                put("connections", AppJson.encodeToJsonElement(sanitizedConnections(state.connections)))
                put("credentialHeadersOmitted", true)
            })
        })
        return jsonBytes(result)
    }

    private fun documentText(document: LibraryDocument): String {
        val value = document.data as? JsonObject ?: return (document.data as? JsonPrimitive)?.contentOrNull.orEmpty()
        val body = value["data"] as? JsonObject ?: value
        return body.text("text", body.text("description", body.text("content", body.text("persona"))))
            .ifBlank { (value["data"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    /** A valid PiuPiu archive plus xujing-state.json for lossless local features. */
    fun exportBackup(state: AppState, media: Map<String, ByteArray> = emptyMap()): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        val mediaNames = linkedMapOf<String, String>()
        media.forEach { (original, content) ->
            require(content.size <= ExchangeLimits.MAX_ENTRY) { "单个媒体超过 64 MiB" }
            val normalized = original.removePrefix("media/")
            val path = if (normalized.startsWith("piupiu/media/")) ExchangeLimits.safePath(normalized)
            else "piupiu/media/xujing/${sha256(content).take(24)}.${safeExtension(original)}"
            require(files["media/$path"]?.contentEquals(content) != false) { "媒体文件名重复但内容不同" }
            files["media/$path"] = content
            mediaNames[original] = path
            if (original != normalized) mediaNames[normalized] = path
        }
        val roleIds = numericIds(state.roles.map { it.id }).toMutableMap()
        val sessionIds = numericIds(state.sessions.map { it.id })
        val cards = state.roles.map { role ->
            role to rewrite(updatedCard(role), mediaNames).jsonObject
        }.toMutableList()
        val sessionRoleIds = linkedMapOf<String, String>()
        state.sessions.forEach { session ->
            require(session.roleIds.isNotEmpty()) { "会话「${session.title}」没有关联角色，无法导出为原版备份" }
            require(session.roleIds.all { it in roleIds }) { "会话「${session.title}」引用了不存在的角色" }
            val importedGroupId = session.extra.text("_piupiuBackupRoleId")
            val groupRole = state.roles.find { it.id == importedGroupId && isGroup(it.raw) }
            val selectedId = when {
                groupRole != null -> groupRole.id
                session.roleIds.size == 1 -> session.roleIds.first()
                else -> {
                    val groupId = "xujing-group:${session.id}"
                    val role = RoleCard(id = groupId, name = session.title, raw = buildJsonObject {
                        put("spec", "chara_card_v2"); put("spec_version", "2.0")
                        put("data", buildJsonObject {
                            put("name", session.title); put("type", "group")
                            put("memberIds", JsonArray(session.roleIds.map { JsonPrimitive(it) }))
                        })
                    })
                    var next = 1L
                    while (next in roleIds.values) next++
                    roleIds[groupId] = next
                    cards += role to updatedCard(role)
                    groupId
                }
            }
            sessionRoleIds[session.id] = selectedId
        }
        val roleRows = cards.mapIndexed { index, (role, card) ->
            val updated = remapMembers(card, roleIds)
            val imagePath = role.avatarPath?.let { mediaNames[it] ?: it }
            if (imagePath?.startsWith("piupiu/media/") == true) {
                require(files.containsKey("media/$imagePath")) { "缺少角色「${role.name}」的头像媒体，请先提供完整媒体" }
            }
            val old = role.raw.obj(EXCHANGE).obj("backupRole")
            JsonObject(old.toMutableMap().apply {
                put("id", JsonPrimitive(roleIds.getValue(role.id)))
                put("roleJson", JsonPrimitive(compactJson.encodeToString(updated)))
                put("imagePath", imagePath?.let(::JsonPrimitive) ?: JsonNull)
                if (!containsKey("createdAt")) put("createdAt", JsonPrimitive(System.currentTimeMillis()))
                if (!containsKey("updatedAt")) put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
                if (!containsKey("isPinned")) put("isPinned", JsonPrimitive(false))
                if (!containsKey("pinnedAt")) put("pinnedAt", JsonNull)
                put("backupOrder", JsonPrimitive(index + 1))
            })
        }
        val sessionRows = state.sessions.map { session ->
            val id = sessionIds.getValue(session.id)
            val roleId = roleIds.getValue(sessionRoleIds.getValue(session.id))
            val payload = exportSession(session, roleId, id)
            val old = session.extra.obj("_piupiuBackupSession")
            files["messages/$id.ndjson"] = ndjson(session.messages.mapIndexed { index, message ->
                val original = message.extra.obj("_piupiuBackupMessage")
                JsonObject(original.toMutableMap().apply {
                    put("index", JsonPrimitive(index)); put("messageId", JsonPrimitive(message.id))
                    put("role", JsonPrimitive(message.role)); put("content", JsonPrimitive(message.content))
                    put("timestamp", JsonPrimitive(message.createdAt))
                    put("messageJson", JsonPrimitive(compactJson.encodeToString(rewrite(exportMessage(message, roleIds), mediaNames))))
                })
            })
            JsonObject(old.toMutableMap().apply {
                put("id", JsonPrimitive(id)); put("roleId", JsonPrimitive(roleId)); put("title", JsonPrimitive(session.title))
                put("sessionJson", JsonPrimitive(compactJson.encodeToString(rewrite(payload, mediaNames))))
                put("messageCount", JsonPrimitive(session.messages.size))
                put("lastMessage", JsonPrimitive(session.messages.lastOrNull()?.content ?: "暂无消息"))
                if (!containsKey("createdAt")) put("createdAt", JsonPrimitive(session.updatedAt))
                put("updatedAt", JsonPrimitive(session.updatedAt))
            })
        }
        files["roles.ndjson"] = ndjson(roleRows)
        files["sessions.ndjson"] = ndjson(sessionRows)
        val safeState = state.copy(connections = sanitizedConnections(state.connections))
        val safeStateJson = rewrite(AppJson.encodeToJsonElement(safeState), mediaNames)
        files["xujing-state.json"] = jsonBytes(buildJsonObject {
            put("appId", "app.xujing"); put("version", 1); put("credentialHeadersOmitted", true); put("state", safeStateJson)
        })
        files["manifest.json"] = jsonBytes(buildJsonObject {
            put("appId", PIUPIU_BACKUP); put("version", 1); put("platform", "android")
            put("databaseVersion", 4); put("exportedAt", Instant.now().toString())
            put("counts", buildJsonObject {
                put("roles", roleRows.size); put("sessions", sessionRows.size)
                put("messages", state.sessions.sumOf { it.messages.size }); put("media", files.keys.count { it.startsWith("media/piupiu/media/") })
            })
            put("xujing", buildJsonObject { put("stateFile", "xujing-state.json"); put("version", 1); put("credentialHeadersOmitted", true) })
        })
        return ExchangeZip.write(files)
    }

    private fun decodePng(fileName: String, bytes: ByteArray): ImportBundle {
        val metadata = PngCards.metadata(bytes)
        require(metadata.isNotEmpty()) { "PNG 不包含 chara 或 ccv3 角色资料" }
        val warnings = mutableListOf<String>()
        val parsed = linkedMapOf<String, JsonObject>()
        metadata.forEach { (key, value) ->
            try { parsed[key] = parseCardText(value) }
            catch (error: Exception) { warnings += "$key 角色数据无法解析：${error.message}" }
        }
        val preferred = parsed["chara"] ?: parsed["ccv3"] ?: error("PNG 中的角色资料无效")
        val path = "piupiu/media/import/${newId()}.png"
        val preserved = JsonObject(preferred.toMutableMap().apply {
            put(EXCHANGE, JsonObject(preferred.obj(EXCHANGE).toMutableMap().apply { put("pngCards", JsonObject(parsed)) }))
        })
        val role = roleFrom(preserved, fallbackName = stem(fileName)).copy(avatarPath = path)
        return ImportBundle(roles = listOf(role), warnings = warnings, media = mapOf(path to bytes))
    }

    private fun parseCardText(text: String): JsonObject {
        val trimmed = text.trim()
        val json = if (trimmed.startsWith('{')) trimmed else {
            val decoded = Base64.getDecoder().decode(trimmed.filterNot { it.isWhitespace() })
            require(decoded.size <= ExchangeLimits.MAX_JSON)
            ExchangeLimits.utf8(decoded)
        }
        return AppJson.parseToJsonElement(json) as? JsonObject ?: error("角色资料不是 JSON 对象")
    }

    private fun decodeJson(fileName: String, parsed: JsonElement, requested: String): ImportBundle {
        val obj = parsed as? JsonObject
        if (obj?.text("app") == PIUPIU_SETTINGS) return decodeSettings(obj)
        if (obj?.text("appId") == "app.xujing" && obj.containsKey("state")) return ownState(obj.obj("state"), emptyMap())
        if (obj?.containsKey("xujing_session") == true && (requested == "auto" || requested == "chat")) {
            val session = AppJson.decodeFromJsonElement<ChatSession>(obj.getValue("xujing_session"))
            val history = obj["history"] as? JsonArray
            val messages = history?.mapIndexed { i, value ->
                val message = messageFrom(value.jsonObject, i)
                // Older chat exports may carry the image only in the extension copy.
                if (message.image.isNotEmpty()) message else message.copy(image = session.messages.getOrNull(i)?.image.orEmpty())
            } ?: session.messages
            return ImportBundle(sessions = listOf(session.copy(messages = messages)))
        }
        val kind = if (requested == "auto") detectKind(fileName, parsed) else requested
        return when (kind) {
            "role", "character" -> ImportBundle(roles = listOf(roleFrom(obj ?: error("角色 JSON 必须是对象"), fallbackName = stem(fileName))))
            "chat", "history" -> {
                val messages = when (parsed) {
                    is JsonArray -> parsed
                    is JsonObject -> parsed["history"] as? JsonArray ?: parsed["messages"] as? JsonArray ?: error("聊天记录缺少 history 数组")
                    else -> error("聊天记录格式无效")
                }
                val session = sessionFrom(obj ?: EmptyObject, messages, fallbackTitle = stem(fileName))
                ImportBundle(sessions = listOf(session), warnings = listOf("独立聊天记录不含可靠角色关联，请选择对应角色后继续会话。"))
            }
            "settings" -> if (obj?.containsKey("settings") == true) decodeSettings(obj) else documentBundle(fileName, parsed, "settings")
            "worldbook", "regex", "preset", "persona", "style" -> documentBundle(fileName, parsed, kind)
            else -> documentBundle(fileName, parsed, "unknown").copy(warnings = listOf("无法自动识别资料类别，已原样保留；请手动选择世界书、预设、正则、人设或文风。"))
        }
    }

    private fun documentBundle(fileName: String, parsed: JsonElement, kind: String): ImportBundle {
        val obj = parsed as? JsonObject ?: EmptyObject
        val document = LibraryDocument(id = obj.text("id").ifBlank { newId() }, name = obj.text("name", obj.text("title", stem(fileName))), kind = kind, data = parsed)
        return ImportBundle(library = listOf(document), warnings = if (kind == "preset") PresetDocuments.diagnostics(parsed) else emptyList())
    }

    private fun detectKind(fileName: String, parsed: JsonElement): String {
        val obj = parsed as? JsonObject
        if (obj != null) {
            val body = cardBody(obj)
            val cardSpec = obj.text("spec") in setOf("chara_card_v2", "chara_card_v3", "character_card_v2", "character_card_v3")
            val cardFields = listOf("first_mes", "firstMessage", "alternate_greetings", "mes_example", "examples",
                "personality", "scenario", "system_prompt", "post_history_instructions")
            val hasCardFields = cardFields.any { body.containsKey(it) || obj.containsKey(it) }
            val hasEntries = obj.containsKey("entries") || body.containsKey("entries") ||
                obj.containsKey("character_book") || body.containsKey("character_book")
            // 角色卡优先，但只在有角色专属字段时成立；带 character_book 的角色卡不能被误判成世界书。
            if (cardSpec || hasCardFields || (obj["data"] as? JsonObject)?.containsKey("character_book") == true) return "role"
            if (obj["history"] is JsonArray || obj["messages"] is JsonArray) return "chat"
            // PiuPiu 世界书固定带 name/description/entries，不能再用 name+description 判成角色卡。
            if (hasEntries) return "worldbook"
            if (body.containsKey("name") && (body.containsKey("description") || body.containsKey("personality"))) return "role"
            if (obj.containsKey("regex_scripts") || obj.containsKey("findRegex") || obj.containsKey("scriptName")) return "regex"
            if (obj.containsKey("prompt_order") || obj.containsKey("prompts") || obj.containsKey("preset")) return "preset"
            if (obj.containsKey("styles") || obj.containsKey("customStyles")) return "style"
        }
        if (parsed is JsonArray && parsed.isNotEmpty()) {
            val first = parsed.firstOrNull() as? JsonObject
            if (first != null) {
                if (first.containsKey("role") && (first.containsKey("content") || first.containsKey("mes"))) return "chat"
                if (first.containsKey("findRegex") || first.containsKey("scriptName")) return "regex"
            }
        }
        val lower = fileName.lowercase()
        return when {
            "world" in lower || "世界书" in lower -> "worldbook"
            "regex" in lower || "正则" in lower -> "regex"
            "preset" in lower || "预设" in lower -> "preset"
            "persona" in lower || "人设" in lower -> "persona"
            "style" in lower || "文风" in lower -> "style"
            "chat" in lower || "聊天" in lower -> "chat"
            "setting" in lower || "设置" in lower -> "settings"
            else -> "unknown"
        }
    }

    private fun roleFrom(raw: JsonObject, id: String? = null, fallbackName: String = "导入角色"): RoleCard {
        val body = cardBody(raw)
        require(body.text("name").isNotBlank() || body.containsKey("description") || isGroup(raw)) { "角色卡缺少名称或角色资料" }
        return RoleCard(
            id = id ?: newId(), name = body.text("name", fallbackName), description = body.text("description"),
            personality = body.text("personality"), scenario = body.text("scenario"), firstMessage = body.text("first_mes", body.text("firstMessage")),
            alternateGreetings = body.array("alternate_greetings").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            examples = body.text("mes_example", body.text("examples")), systemPrompt = body.text("system_prompt"),
            postHistoryInstructions = body.text("post_history_instructions"), raw = raw,
        )
    }

    private fun messageFrom(raw: JsonObject, index: Int = 0): ChatMessage {
        val swipeIndex = raw.int("swipe_id", raw.int("swipeIndex", 0)).coerceAtLeast(0)
        val swipes = raw.array("swipes").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val snapshot = (raw["variables"] as? JsonObject) ?: (raw["variablesSnapshot"] as? JsonObject)
            ?: (raw["variables_snapshot"] as? JsonObject) ?: (raw.array("swipes_data").getOrNull(swipeIndex) as? JsonObject)
            ?: raw.obj("extra").obj("variables")
        val role = raw.text("role", if (raw.bool("is_user")) "user" else "assistant").let { if (it == "model") "assistant" else it }
        return ChatMessage(
            id = raw.text("id", raw.text("messageId", raw.text("message_id"))).ifBlank { newId() }, role = role,
            content = raw.text("content", raw.text("mes", raw.text("message", swipes.getOrNull(swipeIndex) ?: ""))),
            name = raw.text("speakerName", raw.text("name", raw.text("author"))).ifBlank { null },
            speakerId = raw.text("speakerId", raw.text("speaker_id")).ifBlank { null },
            createdAt = raw.long("timestamp", raw.long("createdAt", System.currentTimeMillis() + index)),
            swipes = swipes, swipeIndex = swipeIndex.coerceAtMost(maxOf(0, swipes.lastIndex)), variables = snapshot,
            extra = raw, image = raw.text("image"),
        )
    }

    private fun sessionFrom(raw: JsonObject, messages: JsonArray = JsonArray(emptyList()), id: String? = null,
                            roleIds: List<String>? = null, fallbackTitle: String = "导入会话"): ChatSession = ChatSession(
        id = id ?: raw.text("id", raw.text("sessionId")).ifBlank { newId() },
        title = raw.text("title", fallbackTitle), roleIds = roleIds ?: raw.array("roleIds").map { it.stringContent() },
        persona = raw.text("persona", raw.text("userPersona")), userName = raw.text("userName", "我"),
        summary = raw.text("summary"), presetId = raw.text("presetId", raw.text("globalPresetId")).ifBlank { null },
        worldbookIds = raw.array("worldbookIds").map { it.stringContent() }, regexIds = raw.array("regexIds").map { it.stringContent() },
        messages = messages.mapIndexed { index, value -> messageFrom(value as? JsonObject ?: error("聊天记录含非对象消息"), index) },
        variables = raw["variables"] as? JsonObject ?: raw.obj("chatVariables"),
        extra = raw, updatedAt = raw.long("updatedAt", System.currentTimeMillis()),
    )

    private fun decodeSettings(raw: JsonObject): ImportBundle {
        require(raw.int("schemaVersion", 1) == 1) { "设置备份版本暂不支持" }
        val settings = raw["settings"] as? JsonObject ?: error("设置备份缺少 settings 对象")
        val documents = mutableListOf<LibraryDocument>()
        fun add(values: JsonElement?, kind: String, prefix: String) {
            val list = values as? JsonArray ?: values?.let { JsonArray(listOf(it)) } ?: return
            list.forEachIndexed { index, value ->
                if (value == JsonNull) return@forEachIndexed
                val obj = value as? JsonObject ?: EmptyObject
                val originalId = obj.text("id")
                documents += LibraryDocument(id = if (originalId.isBlank()) newId() else "$kind:$originalId",
                    name = obj.text("name", obj.text("title", "$prefix ${index + 1}")), kind = kind,
                    data = if (kind == "preset") PresetDocuments.body(value) else value,
                    extra = buildJsonObject {
                        put("piupiuId", originalId); put("source", "settings")
                        if (kind in setOf("worldbook", "regex")) put("global", true)
                        if (kind == "preset" && obj["preset"] is JsonObject) put("piupiuPresetEnvelope", obj)
                    })
            }
        }
        add(settings["globalWorldBooks"] ?: settings["globalWorldBook"], "worldbook", "世界书")
        add(settings["globalRegexConfigs"] ?: settings["globalRegex"], "regex", "正则")
        add(settings["globalPresets"], "preset", "预设")
        add(settings.obj("profile")["personaList"], "persona", "人设")
        add(settings.obj("reply").obj("style")["customStyles"], "style", "文风")
        val extension = raw["xujing"] as? JsonObject
        val extendedLibrary = extension?.get("library")?.let { runCatching { AppJson.decodeFromJsonElement<List<LibraryDocument>>(it) }.getOrNull() }
        return ImportBundle(library = extendedLibrary ?: documents,
            compatibility = buildJsonObject {
                put("piupiuSettings", raw)
                if (extension != null) put("xujingSettings", extension)
            }, warnings = listOf("原版设置完整保留；账号、VIP、语音、生图和 Live2D 配置不启用。API 认证请求头需重新填写。"))
    }

    private fun decodeBackup(bytes: ByteArray): ImportBundle {
        val files = ExchangeZip.read(bytes)
        val manifest = parseObject(requireNotNull(files["manifest.json"]) { "备份缺少 manifest.json" }, 1024 * 1024)
        if (manifest.text("appId") == "app.xujing") {
            require(manifest.int("version") == 1) { "残叙·残念备份版本不支持" }
            val state = parseObject(requireNotNull(files["xujing-state.json"]) { "缺少残叙·残念状态文件" })
            return ownState(state["state"] as? JsonObject ?: state, archiveMedia(files))
        }
        require(manifest.text("appId") == PIUPIU_BACKUP && manifest.int("version") == 1) { "不是支持的 PiuPiu v1 备份" }
        val roles = readNdjson(requireNotNull(files["roles.ndjson"]) { "备份缺少 roles.ndjson" })
        val sessions = readNdjson(requireNotNull(files["sessions.ndjson"]) { "备份缺少 sessions.ndjson" })
        val roleIds = mutableSetOf<String>()
        val roleCards = roles.map { row ->
            val id = positiveId(row["id"])
            require(roleIds.add(id)) { "备份角色 ID 重复" }
            val raw = parseStringObject(row["roleJson"], "roleJson")
            val preserved = JsonObject(raw.toMutableMap().apply {
                put(EXCHANGE, JsonObject(raw.obj(EXCHANGE).toMutableMap().apply { put("backupRole", row) }))
            })
            roleFrom(preserved, id).copy(avatarPath = row.text("imagePath").ifBlank { null })
        }
        roleCards.forEach { card -> require(memberIds(card.raw).all { it in roleIds }) { "群聊角色缺少成员卡" } }
        val byRoleId = roleCards.associateBy { it.id }
        val sessionIds = mutableSetOf<String>()
        var messageCount = 0
        val importedSessions = sessions.map { row ->
            val id = positiveId(row["id"])
            require(sessionIds.add(id)) { "备份会话 ID 重复" }
            val roleId = positiveId(row["roleId"])
            val role = requireNotNull(byRoleId[roleId]) { "会话关联角色不存在" }
            val expectedMessages = row.int("messageCount", -1)
            require(expectedMessages >= 0) { "会话消息数量无效" }
            val messageRows = readNdjson(requireNotNull(files["messages/$id.ndjson"]) { "缺少会话消息文件 $id" })
            require(messageRows.size == expectedMessages) { "会话消息数与声明不符" }
            val messages = messageRows.mapIndexed { index, message ->
                require(message.int("index", -1) == index) { "消息序号不连续" }
                require(message["content"] is JsonPrimitive && message["role"] is JsonPrimitive) { "消息内容格式无效" }
                val data = parseStringObject(message["messageJson"], "messageJson")
                JsonObject(data.toMutableMap().apply {
                    put("id", JsonPrimitive(message.text("messageId").ifBlank { newId() }))
                    put("role", JsonPrimitive(message.text("role"))); put("content", JsonPrimitive(message.text("content")))
                    put("timestamp", message["timestamp"] ?: JsonPrimitive(System.currentTimeMillis()))
                    put("_piupiuBackupMessage", message)
                })
            }
            messageCount += messages.size
            val data = parseStringObject(row["sessionJson"], "sessionJson")
            val merged = JsonObject(data.toMutableMap().apply {
                put("title", JsonPrimitive(row.text("title", data.text("title", "导入会话"))))
                put("updatedAt", row["updatedAt"] ?: JsonPrimitive(System.currentTimeMillis()))
                put("_piupiuBackupSession", row); put("_piupiuBackupRoleId", JsonPrimitive(roleId))
            })
            sessionFrom(merged, JsonArray(messages), id, if (isGroup(role.raw)) memberIds(role.raw) else listOf(roleId))
        }
        files.keys.filter { it.startsWith("messages/") && it.endsWith(".ndjson") }.forEach { path ->
            require(path.removePrefix("messages/").removeSuffix(".ndjson") in sessionIds) { "备份含未关联会话的消息" }
        }
        val media = archiveMedia(files)
        roleCards.forEach { role -> if (role.avatarPath?.startsWith("piupiu/media/") == true) require(role.avatarPath in media) { "备份缺少角色头像" } }
        val counts = manifest.obj("counts")
        require(counts.int("roles", -1) == roles.size && counts.int("sessions", -1) == sessions.size &&
            counts.int("messages", -1) == messageCount && counts.int("media", -1) == media.size) { "备份统计与实际文件不符" }
        files["xujing-state.json"]?.let { own ->
            val root = parseObject(own)
            require(root.text("appId") == "app.xujing" && root.int("version") == 1) { "残叙·残念附加备份版本不支持" }
            return ownState(root.obj("state"), media).copy(compatibility = buildJsonObject {
                put("piupiuManifest", manifest); put("xujingState", root.obj("state"))
            })
        }
        return ImportBundle(roles = roleCards, sessions = importedSessions, media = media,
            compatibility = buildJsonObject { put("piupiuManifest", manifest) },
            warnings = listOf("这是角色与聊天备份；原版全局世界书、预设、正则和人设需要另行导入设置 JSON。原有向量数据保留在消息字段中，跨嵌入模型需重建索引。"))
    }

    private fun ownState(json: JsonObject, media: Map<String, ByteArray>): ImportBundle {
        val state = AppJson.decodeFromJsonElement<AppState>(json)
        require(state.schemaVersion == 1) { "残叙·残念数据版本暂不支持" }
        return ImportBundle(roles = state.roles, sessions = state.sessions, library = state.library, media = media,
            compatibility = JsonObject(state.compatibility.toMutableMap().apply { put("xujingState", json) }),
            warnings = listOf("已读取残叙·残念全备份；导出时省略了认证请求头，请重新填写 API 密钥。"))
    }

    private fun archiveMedia(files: Map<String, ByteArray>): Map<String, ByteArray> = files.filterKeys { it.startsWith("media/piupiu/media/") }
        .mapKeys { it.key.removePrefix("media/") }

    private fun updatedCard(role: RoleCard): JsonObject {
        val original = role.raw
        val body = cardBody(original).toMutableMap().apply {
            remove(EXCHANGE)
            put("name", JsonPrimitive(role.name)); put("description", JsonPrimitive(role.description))
            put("personality", JsonPrimitive(role.personality)); put("scenario", JsonPrimitive(role.scenario))
            put("first_mes", JsonPrimitive(role.firstMessage)); put("mes_example", JsonPrimitive(role.examples))
            put("alternate_greetings", JsonArray(role.alternateGreetings.map(::JsonPrimitive)))
            put("system_prompt", JsonPrimitive(role.systemPrompt)); put("post_history_instructions", JsonPrimitive(role.postHistoryInstructions))
        }
        val result = original.toMutableMap()
        result.remove(EXCHANGE)
        result["spec"] = JsonPrimitive(original.text("spec", "chara_card_v2"))
        result["spec_version"] = JsonPrimitive(original.text("spec_version", if (original.text("spec").endsWith("v3")) "3.0" else "2.0"))
        val nested = original.obj("data")
        if (!nested.containsKey("name") && nested["data"] is JsonObject && nested.obj("data").containsKey("name")) {
            // Retain the complete malformed legacy envelope as an extension while normalizing it.
            body["xujing_legacy_nested_data"] = nested
        }
        result["data"] = JsonObject(body)
        return JsonObject(result)
    }

    private fun exportMessage(message: ChatMessage, roleIds: Map<String, Long>? = null): JsonObject = JsonObject(message.extra.toMutableMap().apply {
        remove("_piupiuBackupMessage")
        put("id", JsonPrimitive(message.id)); put("role", JsonPrimitive(message.role)); put("content", JsonPrimitive(message.content))
        put("timestamp", JsonPrimitive(message.createdAt))
        message.name?.let { put("speakerName", JsonPrimitive(it)) }
        message.speakerId?.let { speaker ->
            val exportedId = roleIds?.get(speaker)?.let(::JsonPrimitive) ?: JsonPrimitive(speaker)
            put("speakerId", exportedId)
            // The importer accepts this legacy alias too. Keep it in sync after a merged
            // role's local UUID is converted to a numeric PiuPiu archive ID.
            if (containsKey("speaker_id")) put("speaker_id", exportedId)
        }
        if (message.swipes.isNotEmpty()) {
            put("swipes", JsonArray(message.swipes.map(::JsonPrimitive))); put("swipe_id", JsonPrimitive(message.swipeIndex))
        }
        if (message.variables.isNotEmpty()) put("variables", message.variables)
        // PiuPiu keeps one image string per message (base64 / file reference / remote URL).
        if (message.image.isNotEmpty()) put("image", JsonPrimitive(message.image))
    })

    private fun exportSession(session: ChatSession, roleId: Long, id: Long): JsonObject = JsonObject(session.extra.toMutableMap().apply {
        remove("_piupiuBackupSession"); remove("_piupiuBackupRoleId")
        put("id", JsonPrimitive(id)); put("sessionId", JsonPrimitive(id)); put("roleId", JsonPrimitive(roleId))
        put("title", JsonPrimitive(session.title)); put("messages", JsonArray(emptyList())); remove("history")
        put("variables", session.variables); put("summary", JsonPrimitive(session.summary))
        put("userName", JsonPrimitive(session.userName)); put("persona", JsonPrimitive(session.persona))
        put("messageCount", JsonPrimitive(session.messages.size)); put("updatedAt", JsonPrimitive(session.updatedAt))
    })

    private fun cardBody(raw: JsonObject): JsonObject {
        val inner = raw["data"] as? JsonObject ?: return raw
        return if (!inner.containsKey("name") && inner.obj("data").containsKey("name")) inner.obj("data") else inner
    }

    private fun isGroup(raw: JsonObject) = cardBody(raw).let { it.text("type") == "group" || it["memberIds"] is JsonArray }
    private fun memberIds(raw: JsonObject) = cardBody(raw).array("memberIds").map { it.stringContent() }
    private fun remapMembers(card: JsonObject, ids: Map<String, Long>): JsonObject {
        val data = cardBody(card)
        if (data["memberIds"] !is JsonArray) return card
        val updated = JsonObject(data.toMutableMap().apply {
            put("memberIds", JsonArray(memberIds(card).map { original -> JsonPrimitive(requireNotNull(ids[original]) { "群聊成员 $original 不存在" }) }))
        })
        return JsonObject(card.toMutableMap().apply { put("data", updated) })
    }

    private fun positiveId(element: JsonElement?): String {
        val value = (element as? JsonPrimitive)?.longOrNull
        require(value != null && value > 0 && value <= 9_007_199_254_740_991L) { "备份 ID 必须是正安全整数" }
        return value.toString()
    }

    private fun numericIds(ids: List<String>): Map<String, Long> {
        require(ids.distinct().size == ids.size) { "资料 ID 重复" }
        val result = linkedMapOf<String, Long>()
        val used = mutableSetOf<Long>()
        ids.forEach { id -> id.toLongOrNull()?.takeIf { it > 0 && it <= 9_007_199_254_740_991L && used.add(it) }?.let { result[id] = it } }
        var next = 1L
        ids.forEach { id -> if (id !in result) { while (next in used) next++; result[id] = next; used += next++ } }
        return result
    }

    private fun rewrite(value: JsonElement, paths: Map<String, String>): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { rewrite(it.value, paths) })
        is JsonArray -> JsonArray(value.map { rewrite(it, paths) })
        is JsonPrimitive -> if (!value.isString) value else JsonPrimitive(paths.entries.fold(value.content) { text, (source, target) -> if (source == target) text else text.replace(source, target) })
        else -> value
    }

    private fun sanitizedConnections(connections: List<ApiConnection>): List<ApiConnection> = connections.map { connection ->
        connection.copy(headers = connection.headers.filterKeys { !sensitiveName(it) }, baseUrl = sanitizeUrl(connection.baseUrl))
    }

    private fun sensitiveName(name: String): Boolean = Regex("authorization|api[-_]?key|token|secret|cookie|credential", RegexOption.IGNORE_CASE).containsMatchIn(name)
    private fun sanitizeUrl(url: String): String = runCatching {
        val uri = java.net.URI(url)
        val safeQuery = uri.rawQuery?.split('&')?.filterNot { pair -> sensitiveName(java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8")) }?.joinToString("&")
        // Use raw URL pieces to avoid double-encoding signed or escaped endpoint paths.
        buildString {
            uri.scheme?.let { append(it); append(":") }
            uri.rawAuthority?.let { append("//"); append(it.substringAfterLast('@')) }
            append(uri.rawPath ?: "")
            if (!safeQuery.isNullOrBlank()) { append('?'); append(safeQuery) }
            uri.rawFragment?.let { append('#'); append(it) }
        }
    }.getOrDefault("")

    private fun readNdjson(bytes: ByteArray): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var start = 0
        for (index in 0..bytes.size) {
            if (index == bytes.size || bytes[index] == 10.toByte()) {
                require(index - start <= ExchangeLimits.MAX_LINE) { "单条 NDJSON 记录超过 8 MiB" }
                val line = ExchangeLimits.utf8(bytes.copyOfRange(start, index)).trim()
                if (line.isNotEmpty()) result += AppJson.parseToJsonElement(line) as? JsonObject ?: error("NDJSON 含非对象记录")
                start = index + 1
            }
        }
        return result
    }
    private fun ndjson(rows: List<JsonObject>): ByteArray = rows.joinToString(separator = "\n", postfix = if (rows.isEmpty()) "" else "\n") {
        val line = compactJson.encodeToString(it)
        require(line.toByteArray(Charsets.UTF_8).size <= ExchangeLimits.MAX_LINE) { "单条记录超过 8 MiB" }; line
    }.toByteArray(Charsets.UTF_8)
    private fun parseObject(bytes: ByteArray, maximum: Int = ExchangeLimits.MAX_JSON): JsonObject {
        require(bytes.size <= maximum) { "JSON 文件过大" }
        return AppJson.parseToJsonElement(ExchangeLimits.utf8(bytes)) as? JsonObject ?: error("JSON 必须是对象")
    }
    private fun parseStringObject(value: JsonElement?, name: String): JsonObject {
        require(value is JsonPrimitive && value.isString) { "$name 必须是 JSON 字符串" }
        return AppJson.parseToJsonElement(value.content) as? JsonObject ?: error("$name 必须编码 JSON 对象")
    }
    private fun jsonBytes(value: JsonElement): ByteArray = AppJson.encodeToString(value).toByteArray(Charsets.UTF_8)
    private fun JsonObject.int(key: String, fallback: Int = 0) = (this[key] as? JsonPrimitive)?.intOrNull ?: fallback
    private fun JsonObject.long(key: String, fallback: Long = 0) = (this[key] as? JsonPrimitive)?.longOrNull ?: fallback
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonElement.stringContent() = (this as? JsonPrimitive)?.contentOrNull ?: toString()
    private fun stem(fileName: String) = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "导入资料" }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safeExtension(path: String): String = path.substringBefore('?').substringAfterLast('.', "bin").lowercase().takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() && ch.code < 128 } } ?: "bin"
}
