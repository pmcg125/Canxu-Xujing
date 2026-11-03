package app.xujing.core.engine

import kotlinx.serialization.json.*

object VariableUpdateOrderChecks {
    fun run() {
        val initial=buildJsonObject { put("value",1) }
        val diagnostics=mutableListOf<String>()
        val first=VariableUpdates.apply("""story <UpdateVariable>_.set('value',2)</UpdateVariable><json-patch>[{"op":"replace","path":"/value","value":3}]</json-patch>""",initial,diagnostics)
        check(first.text=="story" && first.variables["value"]?.jsonPrimitive?.int==3 && diagnostics.isEmpty())
        val second=VariableUpdates.apply("""<JSONPatch>[{"op":"replace","path":"/value","value":3}]</JSONPatch><UpdateMVU>_.set('value',4)</UpdateMVU>""",initial,diagnostics)
        check(second.variables["value"]?.jsonPrimitive?.int==4 && second.text.isEmpty())
        val bad=VariableUpdates.apply("""<VariablesUpdate>_.unsupported('value',9)</VariablesUpdate>""",initial,diagnostics)
        check(bad.variables==initial && bad.text.contains("unsupported") && diagnostics.isNotEmpty())
        println("VariableUpdateOrderChecks passed: source order, tag aliases and invalid block preservation")
    }
}
