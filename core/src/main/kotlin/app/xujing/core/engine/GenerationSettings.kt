package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

/** A preset may override sampling only. Endpoint, model, headers and credentials stay with the connection. */
object GenerationSettings {
    fun connection(
        state: AppState,
        session: ChatSession,
        connection: ApiConnection,
        usePreset: Boolean = true,
    ): ApiConnection {
        if (!usePreset || (session.extra["applyPresetSampling"] as? JsonPrimitive)?.booleanOrNull != true) return connection
        val document = state.library.firstOrNull { it.enabled && it.kind == "preset" && it.id == session.presetId }
            ?: return connection
        val preset = unwrap(document.data)
        val parameters = connection.parameters.toMutableMap()

        fun number(target: String, range: ClosedFloatingPointRange<Double>, vararg keys: String) {
            val value = keys.firstNotNullOfOrNull { key ->
                (preset[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                    ?.takeIf { it.isFinite() && it in range }
            }
            if (value != null) parameters[target] = JsonPrimitive(value)
        }
        number("temperature", 0.0..2.0, "temperature", "temp")
        number("top_p", 0.0..1.0, "top_p", "topP")
        number("frequency_penalty", -2.0..2.0, "frequency_penalty", "frequency_pen")
        number("presence_penalty", -2.0..2.0, "presence_penalty", "presence_pen")

        fun integer(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
            (preset[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        }
        val completionTokens = integer("max_completion_tokens")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        val tokens = integer("max_tokens", "openai_max_tokens", "maxTokens")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        if (completionTokens != null || tokens != null) {
            parameters.remove("max_tokens")
            parameters.remove("max_completion_tokens")
            val key = if (completionTokens != null) "max_completion_tokens" else "max_tokens"
            parameters[key] = JsonPrimitive(completionTokens ?: tokens!!)
        }
        // -1 is the common preset sentinel for an unseeded request.
        integer("seed")?.let { seed ->
            if (seed == -1L) parameters.remove("seed")
            else if (seed >= 0) parameters["seed"] = JsonPrimitive(seed)
        }
        val stop = listOf("stop", "stop_sequences", "stopSequences").firstNotNullOfOrNull { key ->
            when (val value = preset[key]) {
                is JsonPrimitive -> value.takeIf { it.isString && it.content.isNotEmpty() }
                is JsonArray -> value.takeIf { values ->
                    values.size <= 4 && values.all { it is JsonPrimitive && it.isString && it.content.isNotEmpty() }
                }
                else -> null
            }
        }
        if (stop is JsonArray && stop.isEmpty()) parameters.remove("stop")
        else if (stop != null) parameters["stop"] = stop
        return connection.copy(parameters = JsonObject(parameters))
    }
}
