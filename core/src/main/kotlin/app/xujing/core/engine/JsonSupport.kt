package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

internal fun JsonElement?.objectOrEmpty(): JsonObject = this as? JsonObject ?: EmptyObject
internal fun JsonElement?.asText(default: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull ?: default
internal fun JsonObject.str(vararg keys: String, default: String = ""): String =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull } ?: default
internal fun JsonObject.num(vararg keys: String, default: Int = 0): Int =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull?.toInt() } ?: default
internal fun JsonObject.decimal(vararg keys: String, default: Double = 0.0): Double =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull } ?: default
internal fun JsonObject.flag(vararg keys: String, default: Boolean = false): Boolean =
    keys.firstNotNullOfOrNull {
        when (val p = this[it] as? JsonPrimitive) {
            null -> null
            else -> p.booleanOrNull ?: p.intOrNull?.let { n -> n != 0 }
                ?: when (p.content.lowercase()) { "true", "yes", "on" -> true; "false", "no", "off" -> false; else -> null }
        }
    } ?: default
internal fun JsonObject.first(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
internal fun JsonElement?.strings(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> contentOrNull?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
    else -> emptyList()
}
internal fun unwrap(value: JsonElement): JsonObject {
    val root = value.objectOrEmpty()
    val data = root["data"] as? JsonObject
    val config = root["config"] as? JsonObject
    val preset = root["preset"] as? JsonObject
    return when {
        preset != null && root.keys.none { it in setOf("entries", "prompts", "prompt_order", "findRegex", "find_regex") } -> preset
        data != null && (root.containsKey("spec") || root.size <= 3) -> data
        config != null && root.keys.none { it in setOf("entries", "prompts", "findRegex", "find_regex") } -> config
        else -> root
    }
}
internal fun roleData(role: RoleCard?): JsonObject = role?.raw?.let(::unwrap) ?: EmptyObject
internal fun mergeJson(base: JsonObject, update: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
    update.forEach { (key, value) ->
        val old = this[key]
        this[key] = if (old is JsonObject && value is JsonObject) mergeJson(old, value) else value
    }
})
internal fun records(value: JsonElement?, vararg containerKeys: String): List<Pair<String, JsonObject>> {
    if (value is JsonArray) return value.mapIndexedNotNull { i, item -> (item as? JsonObject)?.let { i.toString() to it } }
    val root = value as? JsonObject ?: return emptyList()
    for (key in containerKeys) {
        val child = root[key]
        if (child is JsonArray || child is JsonObject) return records(child)
    }
    if (root.containsKey("data") && root["data"] is JsonObject && root.size <= 4) return records(root["data"], *containerKeys)
    return if (root.keys.any { it in setOf("content", "findRegex", "find_regex", "identifier", "keys", "key") }) {
        listOf(root.str("uid", "id", "identifier", default = "0") to root)
    } else root.mapNotNull { (key, item) -> (item as? JsonObject)?.let { key to it } }
}

/** JSON paths accept RFC 6901 pointers or dotted / bracketed MVU paths. */
internal fun pathParts(path: String): List<String> = if (path.startsWith('/')) {
    path.drop(1).split('/').map { it.replace("~1", "/").replace("~0", "~") }
} else path.trim().removePrefix("$").removePrefix(".")
    .replace(Regex("""\[['"]?([^\]'"]+)['"]?\]"""), ".$1")
    .split('.').filter(String::isNotEmpty)

internal fun readPath(root: JsonElement, path: String): JsonElement? {
    var current: JsonElement = root
    for (part in pathParts(path)) current = when (current) {
        is JsonObject -> current[part] ?: return null
        is JsonArray -> current.getOrNull(part.toIntOrNull() ?: return null) ?: return null
        else -> return null
    }
    return current
}

internal fun writePath(root: JsonElement, path: String, value: JsonElement?, append: Boolean = false): JsonElement {
    val parts = pathParts(path)
    fun write(node: JsonElement, depth: Int): JsonElement {
        if (depth == parts.size) return value ?: JsonNull
        val key = parts[depth]
        return when (node) {
            is JsonArray -> {
                val values = node.toMutableList()
                val index = if (key == "-") values.size else key.toIntOrNull() ?: error("数组路径需要索引: $key")
                require(index in 0..values.size) { "数组索引超出范围: $index" }
                if (depth == parts.lastIndex) {
                    if (value == null) { require(index < values.size); values.removeAt(index) }
                    else if (append || index == values.size) values.add(index, value) else values[index] = value
                } else {
                    require(index < values.size) { "数组中间路径不存在" }
                    values[index] = write(values[index], depth + 1)
                }
                JsonArray(values)
            }
            else -> {
                val map = (node as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                if (depth == parts.lastIndex && value == null) map.remove(key)
                else map[key] = write(map[key] ?: EmptyObject, depth + 1)
                JsonObject(map)
            }
        }
    }
    return write(root, 0)
}

internal fun parseValue(text: String): JsonElement = runCatching { AppJson.parseToJsonElement(text) }
    .getOrElse { JsonPrimitive(text.removeSurrounding("'")) }
internal fun JsonElement.plain(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()
internal fun estimatedTokens(text: String): Int = if (text.isEmpty()) 0 else {
    // A conservative local estimate, deliberately not advertised as the remote model's tokenizer.
    val cjk = text.count { it.code in 0x2e80..0x9fff || it.code in 0xac00..0xd7af }
    cjk + ((text.length - cjk + 2) / 3)
}

/** An assistant candidate owns a full variable snapshot. Switching a swipe must restore it. */
object CandidateSnapshots {
    fun variablesFor(message: ChatMessage, swipe: Int = message.swipeIndex): JsonObject {
        val snapshots = message.extra["xujing_swipe_variables"] as? JsonArray
        return snapshots?.getOrNull(swipe) as? JsonObject
            ?: (message.extra["variablesSnapshot"] as? JsonObject)
            ?: (message.extra["variables_snapshot"] as? JsonObject)
            ?: message.variables
    }
    fun attach(message: ChatMessage, variables: JsonObject, swipe: Int = message.swipeIndex): ChatMessage {
        val snapshots = (message.extra["xujing_swipe_variables"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        while (snapshots.size <= swipe.coerceAtLeast(0)) snapshots += JsonNull
        snapshots[swipe.coerceAtLeast(0)] = variables
        return message.copy(variables = variables, extra = JsonObject(message.extra + ("xujing_swipe_variables" to JsonArray(snapshots))))
    }
    fun beforeGeneration(session: ChatSession, regenerate: Boolean): JsonObject {
        if (!regenerate) return session.variables
        (session.messages.lastOrNull()?.extra?.get("xujing_variables_before") as? JsonObject)?.let { return it }
        val history = if (regenerate && session.messages.lastOrNull()?.role == "assistant") session.messages.dropLast(1) else session.messages
        return history.lastOrNull {
            it.variables.isNotEmpty() || it.extra["xujing_swipe_variables"] is JsonArray ||
                it.extra["variablesSnapshot"] is JsonObject || it.extra["variables_snapshot"] is JsonObject
        }?.let { variablesFor(it) } ?: if (regenerate) {
            session.extra["xujing_initial_variables"] as? JsonObject ?: session.variables
        } else session.variables
    }
}
