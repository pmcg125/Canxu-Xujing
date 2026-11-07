package app.xujing.core

import app.xujing.core.compat.CompatibilityCodec
import app.xujing.core.compat.ExchangeZip
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Regressions for native settings export as well as the lossless Xujing extension. */
object SettingsRoundtripChecks {
    fun run() {
        // PiuPiu exports may contain trailing commas and comments; import must not reject them.
        check(AppJson.parseToJsonElement("""{"trailing":1,}""").jsonObject["trailing"] == JsonPrimitive(1))
        check(AppJson.parseToJsonElement("""{"commented":2 /* keep */}""").jsonObject["commented"] == JsonPrimitive(2))
        val original = AppJson.parseToJsonElement("""{
          "app":"piupiu_settings_backup","schemaVersion":1,"unknownEnvelope":{"keep":true},
          "settings":{
            "unknownSetting":{"nested":[1,2]},
            "profile":{"username":"Tester","activePersonaId":42,"unknownProfile":true,
              "personaList":[{"id":42,"name":"Original persona","data":{"description":"Nested persona","unknownBody":7},"unknownPersona":8}]},
            "globalWorldBooks":[{"id":11,"name":"World","data":{"entries":{}},"unknownWorld":9}],
            "globalWorldBook":[{"id":999,"name":"Obsolete alias"}],
            "globalPresets":[{"id":12,"name":"Preset","prompts":[]}],
            "globalRegexConfigs":[{"id":13,"name":"Regex","scripts":[]}],
            "globalRegex":[{"id":998,"name":"Obsolete regex alias"}],
            "reply":{"unknownReply":10,"style":{"unknownStyle":11,"customStyles":[
              {"id":14,"name":"Original style","data":{"content":"Original prose","unknownBody":12},"unknownRecord":13}]}}
          }
        }""").jsonObject
        fun decode(root: JsonObject) = CompatibilityCodec.decode("settings.json", AppJson.encodeToString(root).toByteArray())
        fun export(state: AppState) = AppJson.parseToJsonElement(CompatibilityCodec.exportSettings(state).toString(Charsets.UTF_8)).jsonObject
        fun native(root: JsonObject) = JsonObject(root - "xujing")
        val imported = decode(original)
        check(imported.library.size == 5) { "Each native settings category should import once" }
        val state = AppState(library = imported.library, compatibility = imported.compatibility)

        val exported = export(state)
        val nativeSettings = exported.obj("settings")
        val persona = nativeSettings.obj("profile").array("personaList").single().jsonObject
        check(persona.text("text") == "Nested persona") { "Nested persona text must be usable by PiuPiu" }
        check(persona.obj("data")["unknownBody"] == JsonPrimitive(7)) { "Persona wrapper must be retained" }
        check(persona["unknownPersona"] == JsonPrimitive(8))
        check(nativeSettings.obj("profile")["activePersonaId"] == persona["id"]) { "Active persona must reference the exported ID" }
        check(exported["unknownEnvelope"] == original["unknownEnvelope"])
        check(nativeSettings["unknownSetting"] == original.obj("settings")["unknownSetting"])
        check(nativeSettings.obj("reply")["unknownReply"] == JsonPrimitive(10))
        check(nativeSettings.obj("reply").obj("style")["unknownStyle"] == JsonPrimitive(11))
        check(decode(exported).library == state.library) { "Xujing library must replace native entries without duplication" }
        check(decode(native(exported)).library.size == 5)

        val edited = state.copy(library = state.library.map { document ->
            if (document.kind != "style") document else document.copy(name = "Edited style", data = JsonObject(document.data.jsonObject +
                ("data" to JsonObject(document.data.jsonObject.obj("data") + ("content" to JsonPrimitive("Edited prose"))))))
        })
        val editedExport = export(edited)
        val style = editedExport.obj("settings").obj("reply").obj("style").array("customStyles").single().jsonObject
        check(style.text("name") == "Edited style")
        check(style.obj("data").text("content") == "Edited prose")
        check(style.obj("data")["unknownBody"] == JsonPrimitive(12))
        check(style["unknownRecord"] == JsonPrimitive(13))
        check(decode(native(editedExport)).library.single { it.kind == "style" }.data.jsonObject.obj("data").text("content") == "Edited prose")

        val deleted = export(state.copy(library = emptyList()))
        check(decode(deleted).library.isEmpty())
        check(decode(native(deleted)).library.isEmpty()) { "Deleted native materials must not return after export" }
        val deletedSettings = deleted.obj("settings")
        check(deletedSettings.array("globalWorldBook").isEmpty()) { "Legacy alias cannot retain deleted worldbooks" }
        check(deletedSettings.array("globalRegex").isEmpty())
        check(!deletedSettings.obj("profile").containsKey("activePersonaId"))
        check(deletedSettings.obj("profile")["unknownProfile"] == JsonPrimitive(true))
        check(deletedSettings.obj("reply").obj("style")["unknownStyle"] == JsonPrimitive(11))

        val remapped = state.copy(library = state.library.map { if (it.kind == "persona") it.copy(id = "merged-persona") else it })
        val remappedProfile = export(remapped).obj("settings").obj("profile")
        check(remappedProfile["activePersonaId"] == remappedProfile.array("personaList").single().jsonObject["id"])
        val noSelectedPersona = export(state.copy(library = state.library.filterNot { it.kind == "persona" }))
        check(!noSelectedPersona.obj("settings").obj("profile").containsKey("activePersonaId"))
        val fallback = export(state.copy(library = emptyList(), preferences = UiPreferences(persona = "Current global persona")))
        check(fallback.obj("settings").obj("profile").array("personaList").single().jsonObject.text("text") == "Current global persona")
        nativeMessageSpeakers()
    }

    private fun nativeMessageSpeakers() {
        val member = RoleCard(id = "merged-member-uuid", name = "Member")
        val message = ChatMessage(role = "assistant", speakerId = member.id, content = "Reply", extra = buildJsonObject {
            put("speaker_id", "old-member-id")
            put("custom", buildJsonObject { put("speakerId", member.id) })
        })
        val session = ChatSession(roleIds = listOf(member.id), messages = listOf(message))
        val archive = ExchangeZip.read(CompatibilityCodec.exportBackup(AppState(roles = listOf(member), sessions = listOf(session))))
        val row = AppJson.parseToJsonElement(archive.getValue("messages/1.ndjson").toString(Charsets.UTF_8).trim()).jsonObject
        val nativeMessage = AppJson.parseToJsonElement(row.text("messageJson")).jsonObject
        check(nativeMessage["speakerId"] == JsonPrimitive(1))
        check(nativeMessage["speaker_id"] == nativeMessage["speakerId"]) { "Known aliases must agree with typed speaker" }
        check(nativeMessage.obj("custom").text("speakerId") == member.id) { "Custom IDs must not be rewritten by coincidence" }
        val native = CompatibilityCodec.decode("native.zip", ExchangeZip.write(archive - "xujing-state.json"))
        check(native.sessions.single().messages.single().speakerId in native.sessions.single().roleIds)
        val chat = AppJson.parseToJsonElement(CompatibilityCodec.exportChat(session).toString(Charsets.UTF_8)).jsonObject
        check(chat.array("history").single().jsonObject.text("speakerId") == member.id) { "Standalone chat should retain local IDs" }
    }
}
