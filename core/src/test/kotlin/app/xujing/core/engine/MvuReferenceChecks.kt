package app.xujing.core.engine

import app.xujing.core.AppJson
import kotlinx.serialization.json.*

/** Synthetic examples derived from the recovered PiuPiu Ls/Hs/Ps/Ns paths. */
object MvuReferenceChecks {
    fun run() {
        data class Case(val name:String,val command:String,val initial:String,val expected:String)
        val cases=listOf(
            Case("lodash-prefix", "lodash.set('hp', 7)", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("lodash-whitespace", "lodash . set('hp', 7)", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("replace-expected-ignored-host", "_.replace('hp', 999, 7)", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("move", "_.move('old', 'new')", """{"stat_data":{"old":2}}""", """{"stat_data":{"new":2}}"""),
            Case("assign-key", "_.assign('bag', 'key', 7)", """{"stat_data":{"bag":{"old":2}}}""", """{"stat_data":{"bag":{"old":2,"key":7}}}"""),
            Case("insert-index", "_.insert('bag', 1, 'x')", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["a","x","b"]}}"""),
            Case("insert-negative-index", "_.insert('bag', -1, 'x')", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["a","x","b"]}}"""),
            Case("insert-dash", "_.insert('bag', '-', 'x')", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["a","b","x"]}}"""),
            Case("delete-object-key", "_.delete('bag', 'a')", """{"stat_data":{"bag":{"a":1,"b":2}}}""", """{"stat_data":{"bag":{"b":2}}}"""),
            Case("delete-object-ordinal", "_.remove('bag', 0)", """{"stat_data":{"bag":{"a":1,"b":2}}}""", """{"stat_data":{"bag":{"b":2}}}"""),
            Case("delete-array-value", "_.remove('bag', 'b')", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["a"]}}"""),
            Case("add-description-pair", "_.add('hp', 0.2)", """{"stat_data":{"hp":[0.1,"health"]}}""", """{"stat_data":{"hp":[0.3,"health"]}}"""),
            Case("set-description-pair", "_.set('hp', '7')", """{"stat_data":{"hp":[1,"health"]}}""", """{"stat_data":{"hp":[7,"health"]}}"""),
            Case("explicit-stat-path", "_.set('stat_data.hp', 7)", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("array-path", "_.set(['stat_data','hp'], 7)", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("assign-array-append", "_.assign('bag', 'x')", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["a","b","x"]}}"""),
            Case("json-object", """{"op":"replace","path":"hp","value":7}""", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
            Case("json-array", """[{"op":"replace","path":"hp","value":7},{"op":"add","path":"hp","value":2}]""", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":9}}"""),
            Case("json-updates-wrapped-index", """{"updates":[{"op":"insert","path":"bag","index":1,"value":"x"},{"op":"delete","path":"bag","index":0}]}""", """{"stat_data":{"bag":["a","b"]}}""", """{"stat_data":{"bag":["x","b"]}}"""),
            Case("json-operations-key-alias", """{"operations":[{"operation":"set","key":"hp","value":7}]}""", """{"stat_data":{"hp":1}}""", """{"stat_data":{"hp":7}}"""),
        )
        val failures=mutableListOf<String>()
        for (case in cases) {
            val diagnostics=mutableListOf<String>()
            val initial=AppJson.parseToJsonElement(case.initial).jsonObject
            val expected=AppJson.parseToJsonElement(case.expected).jsonObject
            val actual=VariableUpdates.apply("<UpdateVariable>${case.command}</UpdateVariable>",initial,diagnostics)
            if (actual.variables!=expected || actual.text.isNotEmpty() || diagnostics.isNotEmpty())
                failures += "${case.name}: actual=${actual.variables}, text=${actual.text}, diagnostics=$diagnostics, expected=$expected"
        }
        // Xujing promises atomic updates for each block. An unfinished comment
        // must not silently commit prior operations or erase the malformed block.
        val diagnostics=mutableListOf<String>()
        val initial=AppJson.parseToJsonElement("""{"stat_data":{"hp":1}}""").jsonObject
        val malformed="<UpdateVariable>_.set('hp',7); /* incomplete</UpdateVariable>"
        val actual=VariableUpdates.apply(malformed,initial,diagnostics)
        if(actual.variables!=initial || actual.text!=malformed || diagnostics.isEmpty())
            failures += "unterminated-comment: actual=${actual.variables}, text=${actual.text}, diagnostics=$diagnostics"
        for (command in listOf(
            """{"op":"replace","path":"hp","value":7,"scope":"global"}""",
            """{"op":"unknown","path":"hp","value":7}""",
            """[{"op":"replace","path":"hp","value":7},{"op":"unknown","path":"hp","value":9}]"""
        )) {
            val block="<UpdateVariable>$command</UpdateVariable>"
            val errors=mutableListOf<String>()
            val result=VariableUpdates.apply(block,initial,errors)
            if(result.variables!=initial || result.text!=block || errors.isEmpty())
                failures += "unsupported JSON: input=$command, actual=${result.variables}, text=${result.text}, diagnostics=$errors"
        }
        data class PatchCase(val name:String,val initial:String,val patch:String,val expected:String)
        val patchCases=listOf(
            PatchCase("relative-stat", """{"stat_data":{"hp":1}}""", """[{"op":"replace","path":"/hp","value":7}]""", """{"stat_data":{"hp":7}}"""),
            PatchCase("explicit-stat", """{"stat_data":{"hp":1}}""", """[{"op":"replace","path":"/stat_data/hp","value":7}]""", """{"stat_data":{"hp":7}}"""),
            PatchCase("replace-description-pair", """{"stat_data":{"hp":[1,"health"]}}""", """[{"op":"replace","path":"/hp","value":7}]""", """{"stat_data":{"hp":[7,"health"]}}"""),
            PatchCase("delta-description-pair", """{"stat_data":{"hp":[0.1,"health"]}}""", """[{"op":"delta","path":"/hp","value":0.2}]""", """{"stat_data":{"hp":[0.3,"health"]}}"""),
            PatchCase("add-array-index", """{"stat_data":{"bag":["a","b"]}}""", """[{"op":"add","path":"/bag/1","value":"x"}]""", """{"stat_data":{"bag":["a","x","b"]}}"""),
            PatchCase("move", """{"stat_data":{"old":2}}""", """[{"op":"move","from":"/old","path":"/new"}]""", """{"stat_data":{"new":2}}"""),
        )
        for(case in patchCases) {
            val errors=mutableListOf<String>()
            val result=VariableUpdates.apply("<JSONPatch>${case.patch}</JSONPatch>",AppJson.parseToJsonElement(case.initial).jsonObject,errors)
            val expected=AppJson.parseToJsonElement(case.expected).jsonObject
            if(result.variables!=expected || result.text.isNotEmpty() || errors.isNotEmpty())
                failures += "patch-${case.name}: actual=${result.variables}, text=${result.text}, diagnostics=$errors, expected=$expected"
        }
        val invalidPatches=listOf(
            """[{"op":"replace","value":7}]""",
            """[{"op":"replace","path":null,"value":7}]""",
            """[{"op":"replace","path":7,"value":7}]""",
            """[{"op":"replace","path":{},"value":7}]""",
            """[{"op":"move","path":"/new"}]""",
            """[{"op":"copy","path":"/new"}]""",
            """[{"op":"move","from":null,"path":"/new"}]""",
            """[{"op":"copy","from":7,"path":"/new"}]""",
            """[{"op":"replace","path":"/hp","value":7},{"op":"replace","value":9}]"""
        )
        for(patch in invalidPatches) {
            val block="<JSONPatch>$patch</JSONPatch>"
            val errors=mutableListOf<String>()
            val result=VariableUpdates.apply(block,initial,errors)
            if(result.variables!=initial || result.text!=block || errors.isEmpty())
                failures += "invalid JSONPatch: input=$patch, actual=${result.variables}, text=${result.text}, diagnostics=$errors"
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
        println("MvuReferenceChecks passed: ${cases.size} source-derived synthetic cases")
    }
}
