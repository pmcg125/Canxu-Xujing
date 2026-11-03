package app.xujing.core

import app.xujing.core.compat.CardLibrary
import app.xujing.core.compat.CompatibilityCodec
import app.xujing.core.compat.ExchangeZip
import app.xujing.core.compat.ImportMerger
import app.xujing.core.engine.PromptEngine
import kotlinx.serialization.json.*

object CardLibraryChecks {
    fun run() {
        val role=RoleCard(id="role",name="角色",description="角色描述参照",raw=buildJsonObject { put("spec","chara_card_v2"); put("data",buildJsonObject { put("name","角色"); put("custom","preserve") }) })
        val first=ChatSession(id="first",roleIds=listOf(role.id),messages=listOf(ChatMessage(role="user",content="星星")))
        val other=first.copy(id="other")
        var state=AppState(roles=listOf(role),sessions=listOf(first,other))
        val created=CardLibrary.apply(state,first.id,"createWorldbook",buildJsonObject { put("name","剧本"); put("entries",buildJsonArray { add(buildJsonObject { put("uid",4); put("key",buildJsonArray { add("星星") }); put("content","天空中有红色的星星"); put("position",0); put("role",0); put("enabled",true) }) }) })
        check(created.value==JsonPrimitive(true)); state=created.state
        check(CardLibrary.names(state,first.id)==listOf("剧本"))
        check(state.sessions.first().extra.obj("dynamicWorldBooks").obj("剧本").array("entries").size==1)
        val nativeEntry=state.sessions.first().extra.obj("dynamicWorldBooks").obj("剧本").array("entries").single().jsonObject
        check(nativeEntry.text("position")=="before_character_definition" && nativeEntry.array("keys").single().jsonPrimitive.content=="星星")
        check(CardLibrary.names(state,other.id).isEmpty())
        check(CardLibrary.load(state,other.id,"剧本") is JsonNull)
        val loaded=CardLibrary.load(state,first.id,"剧本").jsonObject
        check(loaded.obj("entries").obj("4").text("content")=="天空中有红色的星星")
        check(CardLibrary.entries(state,first.id,"剧本").single().jsonObject["uid"]==JsonPrimitive(4))
        check(CardLibrary.apply(state,first.id,"createWorldbook",buildJsonObject { put("name","剧本") }).value==JsonPrimitive(false))
        val changed=JsonObject(loaded+("extra_field" to JsonPrimitive("保留")))
        state=CardLibrary.apply(state,first.id,"saveWorldInfo",buildJsonObject { put("name","剧本"); put("data",changed) }).state
        check(CardLibrary.load(state,first.id,"剧本").jsonObject.text("extra_field")=="保留")
        state=CardLibrary.apply(state,first.id,"rebindCharWorldbooks",buildJsonObject { put("character","current"); put("characterId",role.id); put("options",buildJsonObject { put("primary","剧本") }) }).state
        check(state.roles.single().raw.obj("data").obj("extensions").text("world")=="剧本")
        check(state.roles.single().raw.obj("data").text("custom")=="preserve")
        check(PromptEngine.build(state,first).messages.any { it.content.contains("天空中有红色的星星") }) { "Bound character worldbook did not affect prompt" }
        check(PromptEngine.build(state,other).messages.none { it.content.contains("天空中有红色的星星") }) { "Session-scoped worldbook leaked into another session" }
        val shared=LibraryDocument(id="shared",name="剧本",kind="worldbook",data=buildJsonObject { put("entries",buildJsonArray { add(buildJsonObject { put("constant",true); put("content","另一世界书") }) }) })
        val collided=state.copy(library=state.library+shared)
        check(CardLibrary.load(collided,first.id,"剧本").jsonObject.obj("entries").obj("4").text("content")=="天空中有红色的星星")
        val collisionPrompt=PromptEngine.build(collided,first).messages.joinToString("\n") { it.content }
        check("天空中有红色的星星" in collisionPrompt && "另一世界书" !in collisionPrompt) { "Name binding must prefer the session book" }
        val archive=CompatibilityCodec.exportBackup(state)
        val settings=AppJson.parseToJsonElement(CompatibilityCodec.exportSettings(state).toString(Charsets.UTF_8)).jsonObject
        check(settings.obj("settings").array("globalWorldBooks").isEmpty()) { "Session books must not leak into global settings" }
        val native=ExchangeZip.write(ExchangeZip.read(archive)-"xujing-state.json")
        val nativeState=ImportMerger.merge(AppState(),CompatibilityCodec.decode("native.zip",native))
        val nativeFirst=nativeState.sessions.first { it.extra.obj("dynamicWorldBooks").containsKey("剧本") }
        check(CardLibrary.names(nativeState,nativeFirst.id).contains("剧本")) { "PiuPiu session books were not restored" }
        val nativeMessages=PromptEngine.build(nativeState,nativeFirst).messages
        val worldPosition=nativeMessages.indexOfFirst { it.content.contains("天空中有红色的星星") }
        val rolePosition=nativeMessages.indexOfFirst { it.content.contains("角色描述参照") }
        check(worldPosition>=0 && rolePosition>worldPosition) { "Imported PiuPiu book activation or position incorrect" }
        val imported=ImportMerger.merge(state,CompatibilityCodec.decode("test.zip",archive))
        val importedFirst=imported.sessions.drop(state.sessions.size).first { it.id!=first.id && it.messages.isNotEmpty() }
        check(CardLibrary.names(imported,importedFirst.id).contains("剧本")) { "Imported session worldbook scope not remapped" }
        val originalId=state.library.single().id
        state=state.copy(sessions=state.sessions.map { if(it.id==first.id) it.copy(worldbookIds=listOf(originalId,"剧本")) else it })
        state=CardLibrary.apply(state,first.id,"deleteWorldbook",buildJsonObject { put("name","剧本") }).state
        check(state.library.isEmpty())
        check(state.sessions.first().worldbookIds.isEmpty())
        check(state.roles.single().raw.obj("data").obj("extensions").text("world").isEmpty())
        check(runCatching { CardLibrary.apply(state,first.id,"rebindCharWorldbooks",buildJsonObject { put("options",buildJsonObject { put("primary","不存在") }) }) }.isFailure)
        val wrapped=LibraryDocument(id="wrapped",name="包装",kind="worldbook",data=buildJsonObject { put("extra_top",true); put("data",buildJsonObject { put("extra_body","keep"); put("entries",EmptyObject) }) })
        val before=state.copy(library=listOf(wrapped))
        check(CardLibrary.load(before,first.id,"包装").jsonObject.obj("entries").isEmpty())
        val saved=CardLibrary.apply(before,first.id,"saveWorldInfo",buildJsonObject { put("name","包装"); put("data",buildJsonObject { put("entries",buildJsonObject { put("3",buildJsonObject { put("content","更新") }) }) }) }).state.library.single().data.jsonObject
        check(saved["extra_top"]==JsonPrimitive(true) && saved.obj("data").text("extra_body")=="keep")
        check(saved.obj("data").obj("entries").obj("3").text("content")=="更新")
        check(runCatching { CardLibrary.apply(before,first.id,"saveWorldInfo",buildJsonObject { put("name","包装"); put("data",EmptyObject) }) }.isFailure)
        val preset=LibraryDocument(id="p",name="预设甲",kind="preset",data=buildJsonObject { put("prompts",buildJsonArray { }) })
        val presetState=before.copy(library=before.library+preset)
        check(CardLibrary.preset(presetState,"预设甲")?.id=="p")
        val chosen=CardLibrary.apply(presetState,first.id,"selectPreset",buildJsonObject { put("id","预设甲") })
        check(chosen.value==JsonPrimitive(true) && chosen.state.sessions.first().presetId=="p")
        val missing=CardLibrary.apply(chosen.state,first.id,"selectPreset",buildJsonObject { put("id","缺失") })
        check(missing.value==JsonPrimitive(false) && missing.state==chosen.state)
        val cleared=CardLibrary.apply(chosen.state,first.id,"selectPreset",EmptyObject)
        check(cleared.value==JsonPrimitive(true) && cleared.state.sessions.first().presetId==null)
        println("CardLibraryChecks passed: scoped CRUD, binding, prompt activation, duplicate backup and cleanup")
    }
}
