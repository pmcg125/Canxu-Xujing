package app.xujing.core

import app.xujing.core.compat.CompatibilityCodec
import app.xujing.core.compat.ExchangeZip
import app.xujing.core.compat.ImportMerger
import kotlinx.serialization.json.*

object ImportIntegrityChecks {
    fun run() {
        duplicateGroupBackup()
        libraryMedia()
        summaryCursor()
    }

    private fun duplicateGroupBackup() {
        val a = RoleCard(id = "1", name = "成员甲")
        val b = RoleCard(id = "2", name = "成员乙")
        val group = RoleCard(id = "3", name = "雨港小组", raw = buildJsonObject {
            put("data", buildJsonObject {
                put("name", "雨港小组"); put("type", "group")
                put("memberIds", buildJsonArray { add(1); add(2) })
                put("customExtension", buildJsonObject { put("untouchedIdText", "1") })
            })
        })
        val session = ChatSession(id = "7", title = "会话", roleIds = listOf(a.id, b.id),
            messages = listOf(ChatMessage(id = "m1", role = "assistant", content = "测试", speakerId = a.id)),
            extra = buildJsonObject { put("_piupiuBackupRoleId", group.id) })
        // Remove the local state extension to exercise the PiuPiu-only archive path.
        fun piupiuArchive(state: AppState) = ExchangeZip.write(ExchangeZip.read(CompatibilityCodec.exportBackup(state)) - "xujing-state.json")
        val incoming = CompatibilityCodec.decode("group.zip", piupiuArchive(AppState(roles = listOf(a, b, group), sessions = listOf(session))))
        val first = ImportMerger.merge(AppState(), incoming)
        val merged = ImportMerger.merge(first, incoming)
        val secondRoles = merged.roles.drop(first.roles.size)
        val second = merged.sessions.last()
        check(second.id != first.sessions.single().id)
        check(second.roleIds.none { id -> first.roles.any { it.id == id } }) { "Repeated import reused first group members" }
        check(second.messages.single().speakerId == second.roleIds.first()) { "Speaker did not follow remapped member" }
        val secondGroup = secondRoles.single { it.id == second.extra.text("_piupiuBackupRoleId") }
        check(secondGroup.raw.obj("data").array("memberIds").map { it.jsonPrimitive.content } == second.roleIds)
        check(secondGroup.raw.obj("data").obj("customExtension").text("untouchedIdText") == "1")
        val retainedSecond = merged.copy(roles = secondRoles, sessions = listOf(second))
        val exported = CompatibilityCodec.decode("second.zip", piupiuArchive(retainedSecond))
        check(exported.roles.size == 3) { "Export created an unnecessary replacement group" }
        check(exported.sessions.single().roleIds.toSet() == exported.roles.filter { it.raw.obj("data").text("type") != "group" }.map { it.id }.toSet())
        check(exported.sessions.single().messages.single().speakerId in exported.sessions.single().roleIds) { "Native export lost the speaker/member relation" }
        val ownRoundtrip = CompatibilityCodec.decode("own.zip", CompatibilityCodec.exportBackup(retainedSecond))
        check(ownRoundtrip.sessions.single().messages.single().speakerId in ownRoundtrip.sessions.single().roleIds)
    }

    private fun libraryMedia() {
        val source = "piupiu/media/test/image.png"
        val target = "media/local-image.png"
        val unknown = buildJsonObject { put("custom", 17); put("path", source) }
        val document = LibraryDocument(id = "book", name = "资料", data = buildJsonObject {
            put("image", source)
            put("embeddedHtml", "<img src=\"$source\">")
            put("nested", buildJsonArray { add(unknown) })
        }, extra = buildJsonObject { put("thumbnail", source); put("unknown", unknown) })
        val bundle = ImportBundle(library = listOf(document), roles = listOf(RoleCard(avatarPath = source)),
            sessions = listOf(ChatSession(extra = buildJsonObject { put("attachment", source) })),
            compatibility = buildJsonObject { put("image", source) })
        val rewritten = ImportMerger.rewriteMedia(bundle, mapOf(source to target))
        val doc = rewritten.library.single()
        check(doc.data.jsonObject.text("image") == target)
        check(doc.extra.text("thumbnail") == target)
        check(doc.extra.obj("unknown")["custom"] == JsonPrimitive(17))
        check(doc.extra.obj("unknown").text("path") == target)
        check(doc.data.jsonObject.text("embeddedHtml") == "<img src=\"$source\">")
        check(rewritten.roles.single().avatarPath == target)
        check(rewritten.sessions.single().extra.text("attachment") == target)
        check(rewritten.compatibility.text("image") == target)
        val media = mapOf(target to byteArrayOf(1, 2, 3))
        val roundtrip = CompatibilityCodec.decode("media.zip", CompatibilityCodec.exportBackup(AppState(library = listOf(doc)), media))
        val archivePath = roundtrip.library.single().data.jsonObject.text("image")
        check(archivePath in roundtrip.media) { "Library media path does not point to exported attachment" }
    }

    private fun summaryCursor() {
        val message = ChatMessage(id = "covered", content = "旧剧情")
        val session = ChatSession(id = "summary", messages = listOf(message), summary = "已有摘要",
            extra = buildJsonObject { put("xujing_summary_through_message_id", message.id) })
        val merged = ImportMerger.merge(AppState(), ImportBundle(sessions = listOf(session)))
        val imported = merged.sessions.single()
        check(imported.extra.text("xujing_summary_through_message_id") == imported.messages.single().id)
        check(imported.messages.single().id != message.id)
        val duplicates = ImportMerger.merge(AppState(), ImportBundle(sessions = listOf(session.copy(messages = listOf(message, message.copy(content = "第二条剧情")))))).sessions.single()
        check(duplicates.messages.map { it.id }.distinct().size == 2) { "Duplicate source message IDs must not collapse stored records" }
        check("xujing_summary_through_message_id" !in duplicates.extra) { "An ambiguous summary cursor must not be reused" }
        val replaced = ImportMerger.merge(merged, ImportBundle(sessions = listOf(session)), "chat", imported.id).sessions.single()
        check(replaced.summary.isEmpty())
        check("xujing_summary_through_message_id" !in replaced.extra)
    }
}
