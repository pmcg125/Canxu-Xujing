package app.xujing.core.compat

import app.xujing.core.EmptyObject
import app.xujing.core.text
import kotlinx.serialization.json.*

/** File presets contain the body; settings backups wrap it in { id, name, preset }. */
object PresetDocuments {
    fun body(value: JsonElement): JsonObject {
        var current = value as? JsonObject ?: return EmptyObject
        repeat(8) {
            if (current["prompts"] is JsonArray || current["prompt_order"] is JsonArray) return current
            val nested = wrapperKey(current)?.let { current[it] as? JsonObject } ?: return current
            current = nested
        }
        return current
    }

    private fun wrapperKey(value: JsonObject): String? = when {
        value["preset"] is JsonObject -> "preset"
        value["data"] is JsonObject && (value.size <= 3 || "spec" in value) -> "data"
        value["config"] is JsonObject -> "config"
        else -> null
    }

    /** Keep metadata on an older wrapped document when editing its body. */
    fun replaceBody(original: JsonElement, body: JsonObject): JsonObject {
        fun replace(value: JsonObject, depth: Int): JsonObject {
            if (depth >= 8 || value["prompts"] is JsonArray || value["prompt_order"] is JsonArray) return body
            val key = wrapperKey(value) ?: return body
            return JsonObject(value + (key to replace(value.getValue(key).jsonObject, depth + 1)))
        }
        return replace(original as? JsonObject ?: EmptyObject, 0)
    }

    /** Non-destructive diagnostics: unknown fields and unused prompts are legitimate. */
    fun diagnostics(value: JsonElement): List<String> {
        if (value !is JsonObject) return listOf("预设必须是 JSON 对象")
        val root = body(value)
        val issues = mutableListOf<String>()
        if (root["prompts"] !is JsonArray && root["prompt_order"] !is JsonArray) issues += "预设缺少 prompts 或 prompt_order 数组"
        if (root.containsKey("prompts") && root["prompts"] !is JsonArray) issues += "prompts 必须是数组"
        if (root.containsKey("prompt_order") && root["prompt_order"] !is JsonArray) issues += "prompt_order 必须是数组"
        val ids = mutableSetOf<String>()
        (root["prompts"] as? JsonArray).orEmpty().forEachIndexed { index, item ->
            val prompt = item as? JsonObject
            if (prompt == null) issues += "条目 ${index + 1} 必须是对象"
            else {
                val id = prompt.text("identifier").trim()
                if (id.isEmpty()) issues += "条目 ${index + 1} 缺少 identifier"
                else if (!ids.add(id)) issues += "重复 identifier：$id"
                if (prompt.containsKey("role") && prompt.text("role") !in setOf("system", "user", "assistant", "developer")) issues += "条目 ${index + 1} 的发送身份无效"
                if (prompt.containsKey("content") && (prompt["content"] as? JsonPrimitive)?.isString != true) issues += "条目 ${index + 1} 的正文必须是字符串"
            }
        }
        (root["prompt_order"] as? JsonArray).orEmpty().forEachIndexed { index, item ->
            val group = item as? JsonObject
            val order = group?.get("order") as? JsonArray
            if (order == null) issues += "顺序分组 ${index + 1} 缺少 order 数组"
            else {
                val seen = mutableSetOf<String>()
                order.forEach { entry ->
                    val id = (entry as? JsonObject)?.text("identifier").orEmpty().trim()
                    if (id.isEmpty()) issues += "顺序分组 ${index + 1} 含无效条目引用"
                    else {
                        if (id !in ids) issues += "顺序分组 ${index + 1} 引用了不存在的条目：$id"
                        if (!seen.add(id)) issues += "顺序分组 ${index + 1} 重复引用：$id"
                    }
                }
            }
        }
        return issues.distinct()
    }

    private fun selectedGroup(groups: List<JsonElement>): Int = groups.indexOfFirst {
        (it as? JsonObject)?.text("character_id") == "100001"
    }.takeIf { it >= 0 } ?: groups.indexOfFirst {
        ((it as? JsonObject)?.get("order") as? JsonArray)?.isNotEmpty() == true
    }.takeIf { it >= 0 } ?: 0

    /** PiuPiu merge imports only ordered prompts, replacing by identifier and retaining target settings. */
    fun merge(current: JsonElement, incoming: JsonElement): JsonObject {
        val target = body(current)
        val source = body(incoming)
        require(diagnostics(incoming).isEmpty()) { diagnostics(incoming).joinToString("；") }
        val prompts = (target["prompts"] as? JsonArray).orEmpty().toMutableList()
        val groups = (target["prompt_order"] as? JsonArray).orEmpty().toMutableList()
        if (groups.isEmpty()) groups += buildJsonObject { put("character_id", 100001); put("order", JsonArray(emptyList())) }
        val groupIndex = selectedGroup(groups)
        val group = groups[groupIndex] as? JsonObject ?: EmptyObject
        val order = (group["order"] as? JsonArray).orEmpty().toMutableList()
        val sourceGroups = (source["prompt_order"] as? JsonArray).orEmpty()
        val sourceOrder = (sourceGroups.getOrNull(selectedGroup(sourceGroups)) as? JsonObject)?.get("order") as? JsonArray
        val incomingPrompts = (source["prompts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.text("identifier") }
        sourceOrder.orEmpty().forEach { item ->
            val entry = item as? JsonObject ?: return@forEach
            val id = entry.text("identifier")
            val prompt = incomingPrompts[id] ?: return@forEach
            val promptIndex = prompts.indexOfFirst { (it as? JsonObject)?.text("identifier") == id }
            if (promptIndex >= 0) prompts[promptIndex] = prompt else prompts += prompt
            val orderIndex = order.indexOfFirst { (it as? JsonObject)?.text("identifier") == id }
            val enabled = (entry["enabled"] as? JsonPrimitive)?.booleanOrNull != false
            if (orderIndex >= 0) order[orderIndex] = JsonObject((order[orderIndex] as? JsonObject ?: EmptyObject) + ("enabled" to JsonPrimitive(enabled)))
            else order += JsonObject(entry + ("enabled" to JsonPrimitive(enabled)))
        }
        groups[groupIndex] = JsonObject(group + ("order" to JsonArray(order)))
        return JsonObject(target + mapOf("prompts" to JsonArray(prompts), "prompt_order" to JsonArray(groups)))
    }
}
