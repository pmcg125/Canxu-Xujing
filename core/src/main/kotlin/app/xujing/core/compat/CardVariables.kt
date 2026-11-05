package app.xujing.core.compat

import app.xujing.core.*
import app.xujing.core.engine.CandidateSnapshots
import kotlinx.serialization.json.*

/** Variable scopes supported by the Android card host, with stale-write rejection. */
object CardVariables {
    private fun message(session:ChatSession,option:JsonObject):ChatMessage {
        option.text("messageId").takeIf { it.isNotEmpty() }?.let { id ->
            return session.messages.firstOrNull { it.id==id } ?: error("消息已删除")
        }
        val index=option.text("message_id","latest")
        return if(index=="latest") session.messages.lastOrNull { it.role!="system" }
            ?: error("没有可用消息")
        else index.toIntOrNull()?.let { session.messages.getOrNull(if(it<0) session.messages.size+it else it) }
            ?: error("消息不存在：$index")
    }
    fun read(state:AppState,session:ChatSession,option:JsonObject):JsonObject = when(option.text("type","chat")) {
        "chat" -> session.variables
        "message" -> CandidateSnapshots.variablesFor(message(session,option))
        "preset" -> state.compatibility["presetVariables"] as? JsonObject ?: EmptyObject
        else -> error("尚未实现的变量作用域：${option.text("type")}")
    }
    fun apply(state:AppState,sessionId:String,payload:JsonObject):CardLibrary.Result {
        val session=state.sessions.firstOrNull { it.id==sessionId } ?: error("会话已关闭")
        val option=payload.obj("option")
        val prior=read(state,session,option)
        require(payload["expected"]==null || payload["expected"]==prior) { "变量已变化，请读取最新状态后重试" }
        val variables=payload["variables"] as? JsonObject ?: error("变量必须是对象")
        val type=option.text("type","chat")
        if(type=="preset") return CardLibrary.Result(state.copy(compatibility=JsonObject(state.compatibility+("presetVariables" to variables))),variables)
        val updated=if(type=="message") {
            val target=message(session,option)
            require(option["swipe_id"]==null || option["swipe_id"]?.jsonPrimitive?.intOrNull==target.swipeIndex) { "候选已变化，请重试" }
            // Message scope affects only that candidate. Chat scope is an explicit separate write.
            session.copy(messages=session.messages.map { if(it.id==target.id) CandidateSnapshots.attach(it,variables) else it })
        } else session.copy(variables=variables)
        val result=updated.copy(updatedAt=System.currentTimeMillis())
        return CardLibrary.Result(state.copy(sessions=state.sessions.map { if(it.id==sessionId) result else it }),variables)
    }
}
