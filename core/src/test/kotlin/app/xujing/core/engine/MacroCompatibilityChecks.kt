package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import kotlin.random.Random

object MacroCompatibilityChecks {
    fun run() {
        val role=RoleCard(id="role",name="A+B")
        val connection=ApiConnection(id="api",model="mock",parameters=buildJsonObject { put("max_tokens",256) })
        val preset=LibraryDocument(id="preset",kind="preset",data=buildJsonObject { put("openai_max_tokens",700) })
        val session=ChatSession(roleIds=listOf(role.id),presetId=preset.id,extra=buildJsonObject { put("applyPresetSampling",true) })
        val state=AppState(roles=listOf(role),connections=listOf(connection),library=listOf(preset),preferences=UiPreferences(defaultConnectionId=connection.id))
        val diagnostics=mutableListOf<String>()
        val macro=MacroProcessor(state,session,role,EmptyObject,diagnostics,Random(0))
        check(macro.expand("{{setvar::style::温柔}}{{addvar::style::细腻}}{{getvar::style}}") == "温柔细腻")
        check(macro.expand("{{setvar::n::2}}{{addvar::n::3}}{{getvar::n}}") == "5")
        check(macro.expand("{{setvar::mood}}  {{getvar::style}}  {{/setvar}}{{getvar::mood}}") == "温柔细腻")
        check(macro.expand("{{#setvar::pad}}  hello  {{/setvar}}{{getvar::pad}}") == "  hello  ")
        check(macro.expand("{{if::{{getvar::n}}}}{{setvar::chosen::yes}}true{{else}}{{setvar::chosen::no}}false{{/if}}") == "true")
        check(macro.expand("{{getvar::chosen}}") == "yes")
        check(macro.expand("{{if::false}}{{setvar::bad::1}}x{{else}}{{if::true}}ok{{/if}}{{/if}}") == "ok")
        check(macro.variables["bad"] == null)
        check(macro.expand("before\n{{trim}}\nafter") == "beforeafter")
        check(macro.expand("{{//}}hidden {{setvar::bad::1}}{{///}}visible") == "visible")
        check(macro.variables["bad"] == null)
        check(macro.expand("{{setvar::preview::changed}}",effects=false).isEmpty() && macro.variables["preview"] == null)
        check(macro.expand("{{maxresponse}}") == "700")
        val rule=LibraryDocument(id="regex",kind="regex",data=buildJsonObject { put("scripts",buildJsonArray { add(buildJsonObject {
            put("findRegex","/{{char}}/g"); put("replaceString","MATCH"); put("substituteRegex",2); put("placement",buildJsonArray { add(2) })
        }) }) })
        val regexState=state.copy(library=state.library+rule)
        val processor=RegexProcessor(regexState,session.copy(regexIds=listOf(rule.id)),listOf(role),macro,diagnostics)
        check(processor.apply("A+B AAAB A+B",TextStage.OUTPUT) == "MATCH AAAB MATCH")
        check(diagnostics.none { it.contains("无法处理") }) { diagnostics.joinToString() }
        println("MacroCompatibilityChecks passed: block macros, lazy conditions, string addition, preset budget and escaped regex")
    }
}
