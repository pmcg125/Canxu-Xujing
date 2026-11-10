package app.xujing.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
@OptIn(ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    isLenient = true
    // PiuPiu/SillyTavern exports sometimes contain trailing commas or comments.
    allowTrailingComma = true
    allowComments = true
}
fun JsonObject.text(key: String, fallback: String = ""): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
val EmptyObject get() = JsonObject(emptyMap())

@Serializable data class RoleCard(
    val id: String = newId(), val name: String = "新角色", val description: String = "",
    val personality: String = "", val scenario: String = "", val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(), val examples: String = "",
    val systemPrompt: String = "", val postHistoryInstructions: String = "",
    val avatarPath: String? = null, val raw: JsonObject = EmptyObject,
)
@Serializable data class ChatMessage(
    val id: String = newId(), val role: String = "user", val content: String = "",
    val name: String? = null, val speakerId: String? = null, val createdAt: Long = System.currentTimeMillis(),
    val swipes: List<String> = emptyList(), val swipeIndex: Int = 0,
    val variables: JsonObject = EmptyObject, val extra: JsonObject = EmptyObject, val image: String = "",
)
@Serializable data class ChatSession(
    val id: String = newId(), val title: String = "新会话", val roleIds: List<String> = emptyList(),
    val connectionId: String? = null, val summaryConnectionId: String? = null,
    val readingMode: String = "reading", val messages: List<ChatMessage> = emptyList(),
    val persona: String = "", val userName: String = "我", val summary: String = "",
    val presetId: String? = null, val worldbookIds: List<String> = emptyList(),
    val regexIds: List<String> = emptyList(), val memoryEnabled: Boolean = false,
    val variables: JsonObject = EmptyObject, val worldState: JsonObject = EmptyObject,
    val extra: JsonObject = EmptyObject, val updatedAt: Long = System.currentTimeMillis(),
)
@Serializable data class LibraryDocument(
    val id: String = newId(), val name: String = "未命名资料", val kind: String = "worldbook",
    val data: JsonElement = EmptyObject, val enabled: Boolean = true, val extra: JsonObject = EmptyObject,
)
@Serializable data class ApiConnection(
    val id: String = newId(), val name: String = "自定义连接", val baseUrl: String = "",
    val model: String = "", val headers: Map<String, String> = emptyMap(),
    val parameters: JsonObject = buildJsonObject { put("temperature", 1.0); put("max_tokens", 2048) },
    val contextTokens: Int = 16384, val stream: Boolean = true, val supportsVision: Boolean = false,
)
@Serializable data class EmbeddingSettings(
    val provider: String = "local", val localModelId: String? = null,
    val connectionId: String? = null, val remoteModel: String = "", val topK: Int = 8,
    val threshold: Double = 0.35, val chunkChars: Int = 800,
)
@Serializable data class UiPreferences(
    val darkMode: String = "system", val dynamicColor: Boolean = false, val fontSize: Float = 17f,
    val defaultConnectionId: String? = null, val userName: String = "我", val persona: String = "",
    val allowCardScripts: Boolean = false, val userAvatarPath: String? = null,
)
@Serializable data class AppState(
    val schemaVersion: Int = 1, val roles: List<RoleCard> = emptyList(),
    val sessions: List<ChatSession> = emptyList(), val library: List<LibraryDocument> = emptyList(),
    val connections: List<ApiConnection> = emptyList(), val preferences: UiPreferences = UiPreferences(),
    val embedding: EmbeddingSettings = EmbeddingSettings(), val compatibility: JsonObject = EmptyObject,
)
@Serializable data class PromptMessage(val role: String, val content: String, val name: String? = null, val images: List<String> = emptyList())
data class PromptBuild(
    val messages: List<PromptMessage>, val activatedEntries: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(), val worldState: JsonObject = EmptyObject,
    val variables: JsonObject = EmptyObject,
)
data class ImportBundle(
    val roles: List<RoleCard> = emptyList(), val sessions: List<ChatSession> = emptyList(),
    val library: List<LibraryDocument> = emptyList(), val compatibility: JsonObject = EmptyObject,
    val warnings: List<String> = emptyList(), val media: Map<String, ByteArray> = emptyMap(),
)
@Serializable data class LocalModelPackage(
    val id: String, val name: String, val directory: String = "", val modelFile: String = "model.onnx",
    val tokenizerFile: String = "vocab.txt", val pooling: String = "cls", val normalize: Boolean = true,
    val queryPrefix: String = "", val passagePrefix: String = "", val maxTokens: Int = 512,
    val dimensions: Int = 0, val language: String = "zh", val sourceUrl: String = "",
    val license: String = "", val sha256: String? = null,
)
