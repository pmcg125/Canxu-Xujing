package app.xujing.core.compat

import app.xujing.core.*
import app.xujing.core.engine.objectOrEmpty
import app.xujing.core.engine.unwrap
import kotlinx.serialization.json.*

data class SessionScript(val id: String, val name: String, val content: String, val info: String = "", val buttons: JsonArray = JsonArray(emptyList()))

/** Supported modern/legacy TavernHelper tree shapes; disabled folders disable descendants. */
object SessionScripts {
    fun collect(state: AppState, session: ChatSession, includeDisabled: Boolean=false): List<SessionScript> {
        val result=mutableListOf<SessionScript>()
        fun visit(value: JsonElement?, parentEnabled: Boolean, prefix: String, owner: String, depth: Int=0) {
            if(depth>32) return
            (value as? JsonArray)?.forEachIndexed { index,item ->
                val node=item as? JsonObject ?: return@forEachIndexed
                val record=if(node.text("type")=="script") node["value"] as? JsonObject ?: node else node
                val enabled=parentEnabled && (record["enabled"] as? JsonPrimitive)?.booleanOrNull!=false
                val children=node["scripts"] ?: if(node.text("type")=="folder") node["value"] else null
                val key="$prefix.$index"
                if(children is JsonArray) visit(children,enabled,key,owner,depth+1)
                else if(enabled) {
                    val source=record.text("content").trim()
                    val body=Regex("(?is)^```(?:javascript|js)?\\s*\\n([\\s\\S]*?)\\n```$").matchEntire(source)?.groupValues?.get(1) ?: source
                    val buttons=(record["buttons"] ?: record.obj("button")["buttons"]) as? JsonArray ?: JsonArray(emptyList())
                    if(body.isNotBlank()) result+=SessionScript("$owner:${record.text("id",key)}",record.text("name","角色脚本"),body,record.text("info"),buttons)
                }
            }
        }
        state.roles.filter { it.id in session.roleIds }.forEach { role ->
            val data=role.raw["data"] as? JsonObject ?: role.raw
            val body=data["data"] as? JsonObject ?: data
            val extensions=body.obj("extensions")
            val modern=extensions["tavern_helper"]
            val helper=modern as? JsonObject ?: (modern as? JsonArray)?.mapNotNull { item ->
                val pair=item as? JsonArray ?: return@mapNotNull null
                val key=(pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                key to (pair.getOrNull(1) ?: JsonNull)
            }?.toMap()?.let(::JsonObject)
            visit(if(extensions.containsKey("tavern_helper")) helper?.get("scripts") else extensions["TavernHelper_scripts"],true,"script",role.id)
        }
        state.library.firstOrNull { it.enabled && it.kind=="preset" && it.id==session.presetId }?.let { preset ->
            val extensions=unwrap(preset.data as? JsonObject ?: EmptyObject)["extensions"].objectOrEmpty()
            val modern=extensions["tavern_helper"]
            val helper=modern as? JsonObject ?: (modern as? JsonArray)?.mapNotNull { item ->
                val pair=item as? JsonArray ?: return@mapNotNull null
                val key=(pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                key to (pair.getOrNull(1) ?: JsonNull)
            }?.toMap()?.let(::JsonObject)
            visit(if(extensions.containsKey("tavern_helper")) helper?.get("scripts") else extensions["TavernHelper_scripts"],true,"preset","preset:${preset.id}")
        }
        val disabled=session.extra.array("disabledSessionScriptIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        return result.distinctBy { it.id to it.content }.filter { includeDisabled || it.id !in disabled }
    }
}
