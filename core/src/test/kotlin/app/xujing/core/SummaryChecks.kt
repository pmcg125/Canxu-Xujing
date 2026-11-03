package app.xujing.core

import app.xujing.core.engine.SummaryPlanner
import kotlinx.serialization.json.*

object SummaryChecks {
    fun run() {
        val history = (0 until 40).map { ChatMessage(id = "m$it", role = if (it % 2 == 0) "user" else "assistant", content = "事实$it") }
        val connection = ApiConnection(contextTokens = 16384)
        val session = ChatSession(messages = history, extra = buildJsonObject { put("autoSummaryEnabled", true); put("summaryInterval", 3); put("summaryKeepMessages", 8) })
        val plan = checkNotNull(SummaryPlanner.plan(session, connection, true))
        check(plan.throughMessageId == "m31")
        check(plan.messages.last().content.contains("事实31"))
        check(!plan.messages.last().content.contains("事实32"))
        val saved = SummaryPlanner.commit(session, plan, "旧事实摘要")
        check(SummaryPlanner.plan(saved, connection, true) == null)
        check(SummaryPlanner.invalidateIfCovered(saved, "m10").summary.isEmpty())
        check(SummaryPlanner.invalidateIfCovered(saved, "m39").summary == saved.summary)
        check(SummaryPlanner.invalidateIfCovered(saved.copy(extra = EmptyObject), "m39").summary.isEmpty())
        val expanded = saved.copy(messages = history + (40 until 46).map { ChatMessage(id = "m$it", role = if (it % 2 == 0) "user" else "assistant", content = "事实$it") })
        val next = checkNotNull(SummaryPlanner.plan(expanded, connection, true))
        check(next.throughMessageId == "m37")
        check(next.messages.last().content.contains("旧事实摘要"))
        check(next.messages.last().content.contains("事实32"))
        check(!next.messages.last().content.contains("事实31"))
        check(runCatching { SummaryPlanner.commit(session.copy(messages = history.drop(1)), plan, "stale") }.isFailure)
        check(runCatching { SummaryPlanner.commit(session, plan, " ") }.isFailure)
        check(SummaryPlanner.plan(session.copy(extra = EmptyObject), connection, true) == null)
        check(SummaryPlanner.plan(session.copy(messages = history.take(6)), connection, true) == null)
        check(SummaryPlanner.plan(saved, connection, false)?.throughMessageId == "m39")
        val small = connection.copy(contextTokens = 256, parameters = buildJsonObject { put("max_tokens", 50) })
        check(runCatching { SummaryPlanner.plan(session, small, false) }.isFailure)
        val bounded = connection.copy(contextTokens = 2000, parameters = buildJsonObject { put("max_tokens", 100) })
        val long = session.copy(messages = history.map { it.copy(content = "剧情".repeat(250)) })
        val batch = checkNotNull(SummaryPlanner.plan(long, bounded, false))
        check(batch.sourcePrefix.size in 1 until long.messages.size)
        check(!batch.messages.last().content.contains("角色卡"))
        println("SummaryChecks passed: incremental boundary, threshold, bounded batch, stale/empty rejection")
    }
}
