package app.xujing.core

import app.xujing.core.compat.CardVariables
import app.xujing.core.engine.CandidateSnapshots
import kotlinx.serialization.json.*

object CardVariableChecks {
    fun run() {
        fun vars(n:Int)=buildJsonObject { put("hp",n) }
        val first=CandidateSnapshots.attach(ChatMessage(id="m",role="assistant",swipes=listOf("a","b")),vars(1))
        val session=ChatSession(id="s",variables=vars(9),messages=listOf(first))
        val state=AppState(sessions=listOf(session))
        fun payload(type:String,expected:JsonObject)=buildJsonObject {
            put("option",buildJsonObject { put("type",type); put("messageId","m"); put("swipe_id",0) })
            put("expected",expected); put("variables",vars(3))
        }
        val updated=CardVariables.apply(state,"s",payload("message",vars(1))).state.sessions.single()
        check(updated.variables==vars(9))
        check(CandidateSnapshots.variablesFor(updated.messages.single())==vars(3))
        check(runCatching { CardVariables.apply(state,"s",payload("chat",vars(1))) }.isFailure)
        check(runCatching { CardVariables.apply(state.copy(sessions=listOf(session.copy(messages=listOf(first.copy(swipeIndex=1))))),"s",payload("message",vars(1))) }.isFailure)
        check(runCatching { CardVariables.read(state,session,buildJsonObject { put("type","global") }) }.isFailure)
        check(CardVariables.apply(state,"s",payload("chat",vars(9))).state.sessions.single().variables==vars(3))
        check(CardVariables.read(state,session,buildJsonObject { put("type","message"); put("message_id",-1) })==vars(1))
        println("CardVariableChecks passed: scopes, candidate snapshots and stale writes")
    }
}
