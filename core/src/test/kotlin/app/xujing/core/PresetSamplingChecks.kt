package app.xujing.core

import app.xujing.core.engine.GenerationSettings
import app.xujing.core.engine.PromptEngine
import kotlinx.serialization.json.*

object PresetSamplingChecks {
    fun run() {
        val connection = ApiConnection(
            id = "connection", model = "keep-model", baseUrl = "https://keep.example/v1",
            headers = mapOf("X-User" to "keep-header"), contextTokens = 2400,
            parameters = buildJsonObject {
                put("temperature", 0.8); put("max_tokens", 256); put("seed", 42)
                put("stop", "old-stop"); put("provider_option", "keep-extension")
            },
        )
        val role = RoleCard(id = "role", name = "角色")
        val preset = LibraryDocument(id = "preset", kind = "preset", data = buildJsonObject {
            put("temperature", 0.3); put("topP", 0.9); put("frequency_pen", 0.2); put("presence_pen", -0.2)
            put("openai_max_tokens", 1800); put("seed", -1)
            put("stop_sequences", buildJsonArray { add("END"); add("STOP") })
            put("model", "untrusted-model"); put("baseUrl", "https://untrusted.example")
            put("headers", buildJsonObject { put("X-User", "untrusted-header") })
            put("prompts", buildJsonArray { })
        })
        val state = AppState(roles = listOf(role), library = listOf(preset), connections = listOf(connection))
        val plain = ChatSession(roleIds = listOf(role.id), connectionId = connection.id, presetId = preset.id)
        val session = plain.copy(extra = buildJsonObject { put("applyPresetSampling", true) })
        check(GenerationSettings.connection(state, plain, connection) == connection) { "Preset sampling must default off" }
        check(GenerationSettings.connection(state, session, connection, usePreset = false) == connection) { "Summary must use its own connection settings" }
        val effective = GenerationSettings.connection(state, session, connection)
        check(effective.baseUrl == connection.baseUrl && effective.model == connection.model && effective.headers == connection.headers)
        check(effective.parameters["temperature"]?.jsonPrimitive?.double == 0.3)
        check(effective.parameters["max_tokens"]?.jsonPrimitive?.int == 1800)
        check(effective.parameters["top_p"]?.jsonPrimitive?.double == 0.9)
        check(effective.parameters["frequency_penalty"]?.jsonPrimitive?.double == 0.2)
        check(effective.parameters["presence_penalty"]?.jsonPrimitive?.double == -0.2)
        check(effective.parameters["provider_option"] == connection.parameters["provider_option"])
        check("seed" !in effective.parameters && effective.parameters["stop"]?.jsonArray?.size == 2)
        check("prompts" !in effective.parameters && "headers" !in effective.parameters)

        fun withPreset(data: JsonObject) = state.copy(library = listOf(preset.copy(data = data)))
        val invalid = GenerationSettings.connection(withPreset(buildJsonObject {
            put("temperature", 5); put("top_p", "0.1"); put("frequency_penalty", -3)
            put("max_tokens", 1.5); put("presence_penalty", JsonNull)
            put("stop", buildJsonArray { add(1) })
        }), session, connection)
        check(invalid == connection) { "Invalid preset values must preserve connection settings" }
        val completion = GenerationSettings.connection(withPreset(buildJsonObject {
            put("max_completion_tokens", 700); put("max_tokens", 900)
            put("stop", buildJsonArray { })
        }), session, connection)
        check("max_tokens" !in completion.parameters && "stop" !in completion.parameters)
        check(completion.parameters["max_completion_tokens"]?.jsonPrimitive?.int == 700)
        check(GenerationSettings.connection(state.copy(library = listOf(preset.copy(enabled = false))), session, connection) == connection)
        check(GenerationSettings.connection(state, session.copy(extra = buildJsonObject { put("applyPresetSampling", EmptyObject) }), connection) == connection)

        val history = (0 until 10).map { index -> ChatMessage(role = if (index % 2 == 0) "user" else "assistant", content = "history-$index:" + "a".repeat(400)) }
        val before = PromptEngine.build(state, plain.copy(messages = history))
        val after = PromptEngine.build(state, session.copy(messages = history))
        check(after.messages.count { it.content.startsWith("history-") } < before.messages.count { it.content.startsWith("history-") }) {
            "Prompt budget must reserve the effective preset output tokens"
        }
        val summaryConnection = connection.copy(id = "summary", contextTokens = 4000)
        val summaryState = state.copy(connections = listOf(connection, summaryConnection))
        val summary = PromptEngine.build(summaryState, session.copy(messages = history, summaryConnectionId = "summary"), mode = "summary")
        check(summary.messages.count { it.content.startsWith("history-") } == 10) { "Summary budget must use summary connection and ignore chat preset sampling" }
    }
}
