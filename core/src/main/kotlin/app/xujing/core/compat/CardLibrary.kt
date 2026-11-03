package app.xujing.core.compat

import app.xujing.core.*
import kotlinx.serialization.json.*

/** Data-only card operations. The Android host commits the returned state before replying. */
object CardLibrary {
    private val bookKinds=setOf("worldbook","world_book","lorebook")
    private val positions=listOf("before_character_definition","after_character_definition","before_author_note","after_author_note","at_depth","before_example_messages","after_example_messages","outlet")
    private fun books(state:AppState,sessionId:String)=state.library.filter {
        it.kind in bookKinds && (it.extra.text("xujing_session_id").isEmpty() || it.extra.text("xujing_session_id")==sessionId)
    }
    fun names(state:AppState,sessionId:String):List<String> = books(state,sessionId).map { it.name }.distinct()
    fun presets(state:AppState):List<LibraryDocument> = state.library.filter { it.kind=="preset" }
    fun preset(state:AppState,idOrName:String):LibraryDocument? {
        val all=presets(state)
        all.firstOrNull { it.id==idOrName }?.let { return it }
        val matches=all.filter { it.name==idOrName }
        require(matches.size<=1) { "存在多个同名预设，请使用资料 ID：$idOrName" }
        return matches.firstOrNull()
    }
    private fun find(state:AppState,sessionId:String,name:String):LibraryDocument? {
        val candidates=books(state,sessionId)
        candidates.firstOrNull { it.id==name }?.let { return it }
        val matching=candidates.filter { it.name==name }
        val local=matching.filter { it.extra.text("xujing_session_id")==sessionId }
        val selected=local.ifEmpty { matching }
        require(selected.size<=1) { "存在多个同名世界书，请使用资料 ID：$name" }
        return selected.firstOrNull()
    }
    private fun content(document:LibraryDocument):JsonObject = when(val value=document.data) {
        is JsonObject -> (value["data"] as? JsonObject)?.takeIf { !value.containsKey("entries") && it.containsKey("entries") } ?: value
        is JsonArray -> buildJsonObject { put("entries",value) }
        else -> error("世界书格式不是对象或数组：${document.name}")
    }
    /** PiuPiu stores the session's dynamic books inside the session JSON as arrays. */
    private fun nativeBook(document:LibraryDocument):JsonObject {
        val root=content(document)
        val source=when(val value=root["entries"]) {
            is JsonArray -> value.mapIndexed { index,item -> index.toString() to item }
            is JsonObject -> value.entries.map { it.key to it.value }
            else -> emptyList()
        }
        val entries=JsonArray(source.map { (key,item) ->
            val row=item as? JsonObject ?: error("世界书条目不是对象：$key")
            val fields=row.toMutableMap()
            if(!fields.containsKey("uid")) fields["uid"]=key.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(key)
            if(row["key"] is JsonArray) fields["keys"]=row.getValue("key")
            if(row["keysecondary"] is JsonArray) fields["secondary_keys"]=row.getValue("keysecondary")
            if(row["comment"] is JsonPrimitive) fields["name"]=row.getValue("comment")
            if(row["disable"] is JsonPrimitive) fields["enabled"]=JsonPrimitive(row["disable"]?.jsonPrimitive?.booleanOrNull!=true)
            if(row["order"] is JsonPrimitive) fields["insertion_order"]=row.getValue("order")
            (row["position"] as? JsonPrimitive)?.intOrNull?.let { fields["position"]=JsonPrimitive(positions.getOrElse(it) { positions[0] }) }
            (row["role"] as? JsonPrimitive)?.intOrNull?.let { fields["role"]=JsonPrimitive(when(it) { 1 -> "user"; 2 -> "assistant"; else -> "system" }) }
            JsonObject(fields)
        })
        return JsonObject(mapOf("description" to JsonPrimitive(""),"scan_depth" to JsonPrimitive(10),"token_budget" to JsonPrimitive(1536),"enabled" to JsonPrimitive(true))+root+mapOf("name" to JsonPrimitive(document.name),"entries" to entries))
    }
    private fun syncSessionBook(state:AppState,document:LibraryDocument,remove:Boolean=false):List<ChatSession> {
        val scope=document.extra.text("xujing_session_id")
        if(scope.isBlank()) return state.sessions
        val key=document.extra.text("xujing_original_worldbook_key").ifBlank { document.name }
        return state.sessions.map { session ->
            if(session.id!=scope) session else {
                val dynamic=session.extra.obj("dynamicWorldBooks").toMutableMap()
                if(remove) dynamic.remove(key) else dynamic[key]=nativeBook(document)
                session.copy(extra=JsonObject(session.extra+("dynamicWorldBooks" to JsonObject(dynamic))))
            }
        }
    }
    fun load(state:AppState,sessionId:String,name:String):JsonElement {
        val doc=find(state,sessionId,name) ?: return JsonNull
        val root=content(doc)
        val array=root["entries"] as? JsonArray ?: return root
        val entries=linkedMapOf<String,JsonElement>()
        array.forEachIndexed { index,value ->
            val item=value as? JsonObject ?: error("世界书条目不是对象：$index")
            var uid=(item["uid"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..9_007_199_254_740_991L } ?: index.toLong()
            while(entries.containsKey(uid.toString())) uid++
            val fields=item.toMutableMap()
            fields["uid"]=JsonPrimitive(uid)
            if("key" !in fields && item["keys"] is JsonArray) fields["key"]=item.getValue("keys")
            if("keysecondary" !in fields && item["secondary_keys"] is JsonArray) fields["keysecondary"]=item.getValue("secondary_keys")
            if("comment" !in fields && item["name"] is JsonPrimitive) fields["comment"]=item.getValue("name")
            if("disable" !in fields && item["enabled"] is JsonPrimitive) fields["disable"]=JsonPrimitive(item["enabled"]?.jsonPrimitive?.booleanOrNull==false)
            if("order" !in fields && item["insertion_order"] is JsonPrimitive) fields["order"]=item.getValue("insertion_order")
            (item["position"] as? JsonPrimitive)?.contentOrNull?.let { position ->
                val number=positions.indexOf(position)
                if(number>=0) fields["position"]=JsonPrimitive(number)
            }
            (item["role"] as? JsonPrimitive)?.contentOrNull?.let { role ->
                if(role in setOf("system","user","assistant")) fields["role"]=JsonPrimitive(when(role) { "user" -> 1; "assistant" -> 2; else -> 0 })
            }
            entries[uid.toString()]=JsonObject(fields)
        }
        return JsonObject(root+("entries" to JsonObject(entries)))
    }
    fun entries(state:AppState,sessionId:String,name:String):JsonArray {
        val doc=find(state,sessionId,name) ?: error("未找到世界书：$name")
        return nativeBook(doc).array("entries")
    }
    data class Result(val state:AppState,val value:JsonElement)
    private fun updateRoleBody(raw:JsonObject,edit:(JsonObject)->JsonObject):JsonObject {
        val data=raw["data"] as? JsonObject ?: return edit(raw)
        val inner=data["data"] as? JsonObject
        return if(inner!=null && !data.containsKey("name") && inner.containsKey("name"))
            JsonObject(raw+("data" to JsonObject(data+("data" to edit(inner)))))
        else JsonObject(raw+("data" to edit(data)))
    }
    fun apply(state:AppState,sessionId:String,method:String,payload:JsonObject):Result {
        val session=state.sessions.firstOrNull { it.id==sessionId } ?: error("会话已关闭")
        if(method=="selectPreset") {
            val requested=payload.text("id").trim()
            val selected=if(requested.isEmpty()) null else (preset(state,requested)?.takeIf { it.enabled } ?: return Result(state,JsonPrimitive(false)))
            return Result(state.copy(sessions=state.sessions.map { if(it.id==sessionId) it.copy(presetId=selected?.id) else it }),JsonPrimitive(true))
        }
        if(method=="rebindCharWorldbooks") {
            require(payload.text("character","current")=="current") { "只支持绑定当前角色" }
            val roleId=payload.text("characterId").takeIf { it in session.roleIds } ?: session.roleIds.firstOrNull() ?: error("当前会话没有角色")
            val role=state.roles.firstOrNull { it.id==roleId } ?: error("当前角色不存在")
            val options=payload.obj("options")
            val primary=options["primary"]
            val additional=options["additional"]
            require(primary==null || primary is JsonNull || primary is JsonPrimitive && primary.isString) { "主世界书名称无效" }
            require(additional==null || additional is JsonArray && additional.all { it is JsonPrimitive && it.isString }) { "附加世界书名称无效" }
            val requested=listOfNotNull((primary as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)) +
                (additional as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty().filter(String::isNotBlank)
            requested.forEach { require(find(state,sessionId,it)!=null) { "未找到世界书：$it" } }
            val raw=updateRoleBody(role.raw) { body ->
                val ext=body.obj("extensions").toMutableMap()
                if(primary!=null) {
                    val selected=(primary as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if(selected.isBlank()) ext.remove("world") else ext["world"]=JsonPrimitive(selected)
                }
                if(additional!=null) {
                    val helper=(ext["tavern_helper"] as? JsonObject ?: EmptyObject).toMutableMap()
                    helper["additional_worldbooks"]=additional
                    ext["tavern_helper"]=JsonObject(helper)
                }
                JsonObject(body+("extensions" to JsonObject(ext)))
            }
            return Result(state.copy(roles=state.roles.map { if(it.id==roleId) role.copy(raw=raw) else it }),JsonPrimitive(true))
        }
        val name=payload.text("name").trim()
        require(name.isNotEmpty()) { "世界书名称不能为空" }
        val existing=find(state,sessionId,name)
        return when(method) {
            "createWorldbook" -> {
                if(existing!=null) Result(state,JsonPrimitive(false)) else {
                    val entries=payload["entries"] as? JsonArray ?: JsonArray(emptyList())
                    require(entries.all { it is JsonObject }) { "世界书条目必须是对象数组" }
                    val doc=LibraryDocument(name=name,kind="worldbook",data=buildJsonObject { put("name",name); put("entries",entries) },extra=buildJsonObject { put("xujing_session_id",sessionId) })
                    Result(state.copy(library=state.library+doc,sessions=syncSessionBook(state,doc)),JsonPrimitive(true))
                }
            }
            "saveWorldInfo" -> {
                val input=payload["data"] as? JsonObject ?: error("世界书内容需要 JSON 对象")
                val entries=input["entries"]
                require(entries is JsonObject || entries is JsonArray) { "世界书 entries 必须是数组或对象" }
                require(when(entries) { is JsonArray -> entries.all { it is JsonObject }; is JsonObject -> entries.values.all { it is JsonObject }; else -> false }) { "世界书条目必须是对象" }
                val doc=existing ?: LibraryDocument(name=name,kind="worldbook",extra=buildJsonObject { put("xujing_session_id",sessionId) })
                // A script's complete object is authoritative; preserve fields it did not mention.
                val outer=doc.data as? JsonObject ?: EmptyObject
                val nested=(outer["data"] as? JsonObject)?.takeIf { !outer.containsKey("entries") && it.containsKey("entries") }
                val updated=doc.copy(data=if(nested!=null) JsonObject(outer+("data" to JsonObject(nested+input))) else JsonObject(outer+input))
                Result(state.copy(library=if(existing==null) state.library+updated else state.library.map { if(it.id==doc.id) updated else it },sessions=syncSessionBook(state,updated)),JsonPrimitive(true))
            }
            "deleteWorldbook" -> {
                if(existing==null) Result(state,JsonPrimitive(false)) else {
                    // Names may also identify a different imported book; only drop a name binding
                    // when no other book with that name remains for the session.
                    val remaining=state.library.filterNot { it.id==existing.id }
                    val sessions=syncSessionBook(state,existing,remove=true).map { s ->
                        val sameNameRemains=names(state.copy(library=remaining),s.id).contains(existing.name)
                        val extra=s.extra.toMutableMap()
                        if(!sameNameRemains && s.extra.text("chatWorldBookName")==existing.name) extra["chatWorldBookName"]=JsonNull
                        (s.extra["globalWorldBookNames"] as? JsonArray)?.let { values ->
                            if(!sameNameRemains) extra["globalWorldBookNames"]=JsonArray(values.filterNot { (it as? JsonPrimitive)?.contentOrNull==existing.name })
                        }
                        s.copy(worldbookIds=s.worldbookIds.filterNot { it==existing.id || (!sameNameRemains && it==existing.name) },extra=JsonObject(extra))
                    }
                    val roles=if(remaining.any { it.kind in bookKinds && it.name==existing.name }) state.roles else state.roles.map { role ->
                        role.copy(raw=updateRoleBody(role.raw) { body ->
                            val ext=body.obj("extensions").toMutableMap()
                            if((ext["world"] as? JsonPrimitive)?.contentOrNull in setOf(existing.id,existing.name)) ext.remove("world")
                            (ext["tavern_helper"] as? JsonObject)?.let { original ->
                                val helper=original.toMutableMap()
                                (helper["additional_worldbooks"] as? JsonArray)?.let { items ->
                                    helper["additional_worldbooks"]=JsonArray(items.filterNot { (it as? JsonPrimitive)?.contentOrNull in setOf(existing.id,existing.name) })
                                }
                                ext["tavern_helper"]=JsonObject(helper)
                            }
                            if(body.containsKey("extensions")) JsonObject(body+("extensions" to JsonObject(ext))) else body
                        })
                    }
                    Result(state.copy(library=remaining,sessions=sessions,roles=roles),JsonPrimitive(true))
                }
            }
            else -> error("尚未实现的世界书操作：$method")
        }
    }
}
