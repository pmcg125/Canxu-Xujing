package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

internal data class InitLorebookEntry(
    val entryId: String,
    val content: String,
    val schema: JsonElement? = null,
    val charName: String? = null,
)

internal data class InitLorebook(
    val id: String,
    val kind: String,
    val primaryCharacter: Boolean = false,
    val entries: List<InitLorebookEntry>,
) {
    val primaryBook: Boolean get() = kind == "character" && primaryCharacter
}

/** Port of the recovered Sl/kl/xl/Ml/ll/ul MVU state helpers. */
internal object MvuInit {
    private const val META = "\$meta"
    private const val ARRAY_META = "\$arrayMeta"
    private const val EXTENSIBLE_MARKER = "\$__META_EXTENSIBLE__\$"
    private val initVarBlock = Regex("(?is)<\\s*initvar\\b[^>]*>([\\s\\S]*?)<\\s*/\\s*initvar\\s*>")
    private val codeFence = Regex("(?s)^```[^\\n]*\\n([\\s\\S]*?)\\n```$")

    fun clone(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { clone(it.value) })
        is JsonArray -> JsonArray(value.map(::clone))
        else -> value
    }

    fun defaultState(): JsonObject = buildJsonObject {
        put("initialized_lorebooks", buildJsonObject {})
        put("stat_data", buildJsonObject {})
        put("display_data", buildJsonObject {})
        put("delta_data", buildJsonObject {})
        put("schema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
    }

    /** hl(): an object without stat_data/initialized_lorebooks is treated as stat_data itself. */
    fun normalize(value: JsonElement?): JsonObject {
        val root = value as? JsonObject ?: return defaultState()
        val stat = root["stat_data"]
        val lore = root["initialized_lorebooks"]
        if (stat is JsonObject || lore is JsonArray || lore is JsonObject) {
            val out = root.toMutableMap()
            out["initialized_lorebooks"] = normalizeLorebooks(lore)
            out["stat_data"] = if (stat is JsonObject) clone(stat) else EmptyObject
            return JsonObject(out)
        }
        val out = defaultState().toMutableMap()
        out["stat_data"] = clone(root)
        return JsonObject(out)
    }

    private fun normalizeLorebooks(value: JsonElement?): JsonObject = when (value) {
        is JsonArray -> JsonObject(value.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull?.let { it to JsonArray(emptyList()) }
        }.toMap())
        is JsonObject -> JsonObject(value.mapValues { (it.value as? JsonArray)?.let(::clone) ?: JsonArray(emptyList()) })
        else -> EmptyObject
    }

    /** kl(): optional wrapper, Markdown fence and JSON object only. */
    fun parseFragment(text: String): JsonObject {
        var body = text.trim()
        initVarBlock.find(body)?.let { body = it.groupValues[1] }
        codeFence.matchEntire(body.trim())?.let { body = it.groupValues[1] }
        val parsed = runCatching { AppJson.parseToJsonElement(body.trim()) }.getOrNull()
        return parsed as? JsonObject ?: EmptyObject
    }

    fun parseInitVars(text: String): List<JsonObject> =
        initVarBlock.findAll(text).mapNotNull { match ->
            parseFragment(match.groupValues[1]).takeIf(JsonObject::isNotEmpty)
        }.toList()

    /** Ml(). Arrays from the update replace the old array. */
    fun deepMerge(base: JsonObject, update: JsonObject): JsonObject {
        val out = base.toMutableMap()
        update.forEach { (key, value) ->
            val old = out[key]
            out[key] = if (old is JsonObject && value is JsonObject) deepMerge(old, value) else clone(value)
        }
        return JsonObject(out)
    }

    /** xl(init=true): initialization fills missing fields; the existing value wins. */
    fun mergeInitial(state: JsonObject, addition: JsonObject): JsonObject {
        val normalized = normalize(state)
        val incoming = (addition["stat_data"] as? JsonObject) ?: addition
        val existing = normalized["stat_data"] as? JsonObject ?: EmptyObject
        val out = normalized.toMutableMap()
        out["stat_data"] = JsonObject(incoming.toMutableMap().apply { putAll(existing) })
        for (key in listOf("schema", "display_data", "delta_data")) addition[key]?.let { out[key] = clone(it) }
        return JsonObject(out)
    }

    /** Sl(). */
    fun initialize(existing: JsonObject, books: List<InitLorebook>, initialText: String): JsonObject {
        var state = normalize(existing)
        val initialized = (state["initialized_lorebooks"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val blocks = parseInitVars(initialText)
        val hasInitVar = blocks.isNotEmpty()
        for (book in books) {
            if (initialized.containsKey(book.id)) continue
            val entryIds = JsonArray(book.entries.map { JsonPrimitive(it.entryId) })
            initialized[book.id] = entryIds
            if (!hasInitVar || !book.primaryBook) {
                val merged = book.entries.fold(EmptyObject) { acc, entry -> deepMerge(acc, parseFragment(entry.content)) }
                state = mergeInitial(state, merged)
            }
        }
        state = JsonObject(state + ("initialized_lorebooks" to JsonObject(initialized)))
        if (hasInitVar) {
            val fromInitVar = blocks.fold(EmptyObject) { acc, block ->
                deepMerge(acc, (block["stat_data"] as? JsonObject) ?: block)
            }
            state = JsonObject(state + ("stat_data" to clone(fromInitVar)))
            initialized.clear()
            for (book in books) {
                if (!book.primaryBook) continue
                initialized[book.id] = JsonArray(emptyList())
            }
            for (book in books) {
                if (book.primaryBook) continue
                val merged = book.entries.fold(EmptyObject) { acc, entry -> deepMerge(acc, parseFragment(entry.content)) }
                state = mergeInitial(state, merged)
                initialized[book.id] = JsonArray(book.entries.map { JsonPrimitive(it.entryId) })
            }
            state = JsonObject(state + ("initialized_lorebooks" to JsonObject(initialized)))
        }
        val schemas = books.flatMap { book -> book.entries.mapNotNull { it.schema } }
        if (schemas.isNotEmpty()) {
            state = JsonObject(state + ("schema" to clone(schemas.last())))
        } else {
            val statData = state["stat_data"] as? JsonObject ?: EmptyObject
            state = JsonObject(state + ("schema" to schemaFor(statData, state["schema"])))
            state = JsonObject(state + ("stat_data" to clean(statData)))
        }
        return state
    }

    fun clean(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value
            .filterNot { it is JsonPrimitive && it.contentOrNull == EXTENSIBLE_MARKER }
            .filterNot { it is JsonObject && it.flag(ARRAY_META) && it.containsKey(META) }
            .map(::clean))
        is JsonObject -> JsonObject(value
            .filterKeys { it != META }
            .mapValues { clean(it.value) })
        else -> value
    }

    /** ll()/cl(), generated for stat_data when no card schema entry exists. */
    fun schemaFor(value: JsonElement, base: JsonElement?, inherited: Boolean = false): JsonObject {
        val baseObject = base as? JsonObject ?: EmptyObject
        if (value is JsonArray) {
            val metaEntry = value.filterIsInstance<JsonObject>().firstOrNull {
                it.flag(ARRAY_META) && it[META] is JsonObject
            }
            val meta = metaEntry?.get(META) as? JsonObject ?: EmptyObject
            val sample = value.firstOrNull { item ->
                !(item is JsonPrimitive && item.contentOrNull == EXTENSIBLE_MARKER) &&
                    !(item is JsonObject && item.flag(ARRAY_META) && item.containsKey(META))
            }
            val recursive = baseObject.flag("recursiveExtensible") || inherited
            val extensible = baseObject.flag("extensible") ||
                value.any { it is JsonPrimitive && it.contentOrNull == EXTENSIBLE_MARKER } ||
                meta.flag("extensible") || inherited
            val out = mutableMapOf<String, JsonElement>(
                "type" to JsonPrimitive("array"),
                "extensible" to JsonPrimitive(extensible),
                "recursiveExtensible" to JsonPrimitive(recursive),
                "elementType" to if (sample == null) {
                    (baseObject["elementType"] as? JsonObject) ?: buildJsonObject { put("type", "any") }
                } else schemaFor(sample, baseObject["elementType"], recursive),
            )
            (meta["template"] ?: baseObject["template"])?.let { out["template"] = clone(it) }
            return JsonObject(out)
        }
        if (value is JsonObject) {
            val meta = value[META] as? JsonObject ?: EmptyObject
            val extensible = baseObject.flag("extensible") || meta.flag("extensible") ||
                meta.flag("recursiveExtensible") || inherited
            val recursive = baseObject.flag("recursiveExtensible") || meta.flag("recursiveExtensible") || inherited
            val properties = linkedMapOf<String, JsonElement>()
            value.forEach { (key, child) ->
                if (key == META) return@forEach
                val baseProperty = (baseObject["properties"] as? JsonObject)?.get(key)
                val schema = schemaFor(child, baseProperty, extensible && recursive).toMutableMap()
                val requiredInMeta = (meta["required"] as? JsonArray)?.any {
                    it is JsonPrimitive && it.contentOrNull == key
                } == true
                schema["required"] = JsonPrimitive(when {
                    requiredInMeta -> true
                    baseProperty is JsonObject && baseProperty["required"] is JsonPrimitive -> baseProperty.flag("required")
                    else -> !extensible
                })
                properties[key] = JsonObject(schema)
            }
            val out = mutableMapOf<String, JsonElement>(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(properties),
                "extensible" to JsonPrimitive(extensible),
                "recursiveExtensible" to JsonPrimitive(recursive),
            )
            (meta["template"] ?: baseObject["template"])?.let { out["template"] = clone(it) }
            for (key in listOf("strictTemplate", "strictSet", "concatTemplateArray")) {
                (meta[key] ?: baseObject[key])?.let { out[key] = clone(it) }
            }
            return JsonObject(out)
        }
        if (value is JsonNull) return buildJsonObject {
            put("type", "any"); put("nullable", true)
        }
        val type = when (value) {
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.doubleOrNull != null -> "number"
                else -> "any"
            }
            else -> "any"
        }
        return buildJsonObject { put("type", type) }
    }
}
