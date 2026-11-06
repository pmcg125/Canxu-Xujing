package app.xujing.ui

import app.xujing.core.*

/** Host-rendered floating panel requested by a card/preset script. */
data class ScriptPanel(
    val sessionId: String, val sourceId: String, val title: String, val html: String,
    val width: Int = 320, val height: Int = 240, val position: String = "bottom-end",
)

data class UiRuntime(
    val activeSessionId: String? = null, val generating: Boolean = false,
    val busy: Boolean = false, val status: String = "", val error: String? = null,
    val promptPreview: String? = null, val localModels: List<LocalModelPackage> = emptyList(),
    val suggesting: Boolean = false, val suggestionSessionId: String? = null,
    val suggestions: List<String> = emptyList(),
    val fetchingModels: Boolean = false, val modelOptions: List<String> = emptyList(),
    val modelOptionsConnectionId: String? = null, val modelFetchVersion: Int = 0,
    val openLibraryKind: String? = null, val scriptPanel: ScriptPanel? = null,
    val streamingMessageId: String? = null,
)
interface AppActions {
    fun openSession(id: String?)
    fun createSession(roleIds: List<String>)
    fun updateSession(session: ChatSession)
    fun deleteSession(id: String)
    fun saveRole(role: RoleCard)
    fun deleteRole(id: String)
    fun saveLibrary(document: LibraryDocument)
    fun deleteLibrary(id: String)
    fun saveConnection(connection: ApiConnection, apiKey: String?)
    fun deleteConnection(id: String)
    fun savePreferences(preferences: UiPreferences)
    fun saveEmbedding(settings: EmbeddingSettings)
    fun send(text: String, targetRoleId: String? = null)
    fun stop()
    fun regenerate()
    fun continueReply()
    fun editMessage(id: String, content: String)
    fun deleteMessage(id: String)
    fun selectSwipe(id: String, index: Int)
    fun rewind(id: String)
    fun summarize()
    fun requestImport(kind: String)
    fun requestExport(kind: String, id: String? = null)
    fun importModel()
    fun openModelSource(url: String)
    fun deleteModel(id: String)
    fun rebuildMemory()
    fun showPrompt()
    fun clearError()
    fun clearPrompt()
    fun suggestReplies()
    fun clearSuggestions()
    fun fetchModels(connection: ApiConnection, apiKey: String?)
    fun consumeOpenLibrary()
    fun clearScriptPanel()
}
