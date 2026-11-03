package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

/** Source-derived checks for the recovered Sl/jl InitVar and replay behavior. */
object MvuLifecycleChecks {
    fun run() {
        val failures = mutableListOf<String>()
        fun parse(text: String) = AppJson.parseToJsonElement(text).jsonObject
        fun stateOf(variables: JsonObject) = variables["stat_data"] as? JsonObject ?: EmptyObject

        val primary = InitLorebook("Char", "character", true, listOf(
            InitLorebookEntry("char-init", """{"stat_data":{"hp":1,"mp":2}}"""),
        ))
        val global = InitLorebook("Global", "global", false, listOf(
            InitLorebookEntry("gold", """{"stat_data":{"hp":99,"gold":5}}"""),
        ))

        val withInitVar = MvuInit.initialize(
            MvuInit.defaultState(), listOf(primary, global),
            """<initvar>{"stat_data":{"hp":7}}</initvar>""",
        )
        if (stateOf(withInitVar) != parse("""{"hp":7,"gold":5}""")) {
            failures += "initvar-priority: ${withInitVar["stat_data"]}"
        }
        val loreAfterInit = withInitVar["initialized_lorebooks"]?.jsonObject ?: EmptyObject
        if (loreAfterInit["Char"] != JsonArray(emptyList()) ||
            loreAfterInit["Global"] != JsonArray(listOf(JsonPrimitive("gold")))
        ) failures += "initvar-lorebooks: $loreAfterInit"

        val withoutInitVar = MvuInit.initialize(MvuInit.defaultState(), listOf(primary, global), "")
        if (stateOf(withoutInitVar) != parse("""{"hp":1,"mp":2,"gold":5}""")) {
            failures += "lorebook-priority: ${withoutInitVar["stat_data"]}"
        }
        val loreNoInit = withoutInitVar["initialized_lorebooks"]?.jsonObject ?: EmptyObject
        if (loreNoInit["Char"] != JsonArray(listOf(JsonPrimitive("char-init")))) {
            failures += "lorebook-ids: $loreNoInit"
        }

        val alreadyInitialized = MvuInit.initialize(
            parse("""{"initialized_lorebooks":{"Char":["char-init"]},"stat_data":{"hp":4}}"""),
            listOf(primary, global), "",
        )
        if (stateOf(alreadyInitialized) != parse("""{"hp":4,"gold":5}""")) {
            failures += "initialized-skip: ${alreadyInitialized["stat_data"]}"
        }

        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("hp", buildJsonObject { put("type", "number") }) })
        }
        val schemaBook = InitLorebook("Char", "character", true, listOf(
            InitLorebookEntry("char-init", """{"stat_data":{"hp":1}}"""),
            InitLorebookEntry("\$schema", "", schema),
        ))
        val schemaState = MvuInit.initialize(MvuInit.defaultState(), listOf(schemaBook), "")
        if (schemaState["schema"] != schema) failures += "schema-entry: ${schemaState["schema"]}"

        val unclean = parse("""{"hp":7,"arr":["a","${'$'}__META_EXTENSIBLE__${'$'}"],"${'$'}meta":{"extensible":true}}""")
        if (MvuInit.clean(unclean) != parse("""{"hp":7,"arr":["a"]}""")) {
            failures += "clean-meta: ${MvuInit.clean(unclean)}"
        }
        val wrapped = MvuInit.normalize(parse("""{"label":"A&B"}"""))
        if (stateOf(wrapped) != parse("""{"label":"A&B"}""")) failures += "normalize-flat: $wrapped"

        if (MvuSession.replaceMacros("{{user}}/{{char}}/<USER>/<BOT>/<CHAR>", "U", "C") != "U/C/U/C/C") {
            failures += "macro-replace"
        }

        val role = RoleCard(id = "r1", name = "QA", firstMessage = "opening")
        val opening = ChatMessage(
            id = "m1", role = "assistant", content = "opening", swipeIndex = 0,
            swipes = listOf(
                """<initvar>{"stat_data":{"hp":7,"bag":["a"]}}</initvar><UpdateVariable>_.add('hp', 1)</UpdateVariable>""",
                """<initvar>{"stat_data":{"hp":7,"bag":["a"]}}</initvar><UpdateVariable>_.add('hp', 2)</UpdateVariable>""",
            ),
            extra = buildJsonObject { put("isOpening", true) },
        )
        val follow = ChatMessage(
            id = "m2", role = "assistant", content = "<UpdateVariable>_.add('hp', 5)</UpdateVariable>",
        )
        val session = ChatSession(id = "s1", roleIds = listOf("r1"), userName = "U", messages = listOf(opening, follow))
        val state = AppState(roles = listOf(role), sessions = listOf(session))
        val replayed = MvuSession.replay(state, session, 0)
        val snapshots = replayed.messages[0].extra["xujing_swipe_variables"]?.jsonArray ?: JsonArray(emptyList())
        val hp0 = (snapshots.getOrNull(0)?.jsonObject?.get("stat_data") as? JsonObject)?.get("hp")?.jsonPrimitive?.intOrNull
        val hp1 = (snapshots.getOrNull(1)?.jsonObject?.get("stat_data") as? JsonObject)?.get("hp")?.jsonPrimitive?.intOrNull
        if (hp0 != 8 || hp1 != 9) failures += "replay-swipes: hp0=$hp0 hp1=$hp1"
        if ((replayed.variables["stat_data"] as? JsonObject)?.get("hp")?.jsonPrimitive?.intOrNull != 13) {
            failures += "replay-latest: ${replayed.variables}"
        }
        if (replayed.diagnostics.isNotEmpty()) failures += "replay-diagnostics: ${replayed.diagnostics}"

        val parsed = MvuSession.parseMessage(
            state, session, "<UpdateVariable>_.add('hp', 2)</UpdateVariable>",
            parse("""{"stat_data":{"hp":1}}"""), "r1",
        )
        if ((parsed["stat_data"] as? JsonObject)?.get("hp")?.jsonPrimitive?.intOrNull != 3) failures += "parse-message: $parsed"

        val missingSnapshots = session.copy(extra = buildJsonObject { put("xujing_replay_version", 2) })
        if (!MvuSession.needsReplay(missingSnapshots)) failures += "needs-replay-missing-swipes"
        val complete = session.copy(messages = replayed.messages, extra = buildJsonObject { put("xujing_replay_version", 2) })
        if (MvuSession.needsReplay(complete)) failures += "needs-replay-complete"

        check(failures.isEmpty()) { failures.joinToString("\n") }
        println("MvuLifecycleChecks passed: InitVar precedence, lorebooks, schema, replay and host parse")
    }
}
