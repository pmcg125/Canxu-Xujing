package app.xujing.core.engine

import app.xujing.core.AppJson
import kotlinx.serialization.json.*

/** Recognized JSON-form MVU commands. Returns null for unsupported or ambiguous input. */
internal object MvuJsonOperations {
    fun parse(input:String):List<Pair<String,List<String>>>? {
        val text=input.trim().replace(Regex("^```(?:json|js|javascript)?\\s*",RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*$"), "").trim()
        val root=runCatching { AppJson.parseToJsonElement(text) }.getOrNull() ?: return null
        fun collect(value:JsonElement):List<JsonObject>? = when(value) {
            is JsonArray -> if(value.all { it is JsonObject }) value.map { it as JsonObject } else null
            is JsonObject -> {
                val nested=listOf("updates","operations","ops").firstNotNullOfOrNull { value[it] }
                if(nested!=null) collect(nested) else if(listOf("path","key","name").any { it in value }) listOf(value) else null
            }
            else -> null
        }
        val objects=collect(root) ?: return null
        if(objects.isEmpty() || objects.size>512) return null
        val operations=mutableListOf<Pair<String,List<String>>>()
        for(obj in objects) {
            // Other scopes need their own host state; do not silently apply to chat.
            val scope=obj.str("scope","variableType","varType").lowercase()
            if(scope.isNotEmpty() && scope!="message") return null
            val op=obj.str("op","operation","type",default="replace").lowercase()
            if(op !in setOf("set","replace","add","assign","insert","delete","remove","unset","move")) return null
            val path=obj.first("path","key","name") ?: return null
            val args=mutableListOf(path.toString())
            when(op) {
                "delete","remove","unset" -> obj["index"]?.let { args+=it.toString() }
                "move" -> args+=(obj.first("from","targetPath","to") ?: return null).toString()
                "insert","assign" -> {
                    obj["index"]?.let { args+=it.toString() }
                    args+=(obj["value"] ?: return null).toString()
                }
                else -> args+=(obj["value"] ?: return null).toString()
            }
            operations+=op to args
        }
        return operations
    }
}
