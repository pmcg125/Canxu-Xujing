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
    val streamingMessageId: String? = null, val inputDraft: String? = null,
    val inputDraftSessionId: String? = null,
    val composerDrafts: Map<String, ComposerDraft> = emptyMap(),
    val generationStage: GenerationStage? = null,
    val importResult: String? = null,
    val restoringSessionId: String? = null,
    val pendingAttachment: PendingAttachment? = null,
)

data class ComposerDraft(val text: String = "", val revision: Long = 0L)

data class PendingAttachment(val sessionId: String, val displayName: String, val mediaPath: String)

enum class GenerationPhase { SAVING_MESSAGE, PREPARING, WAITING, RECEIVING, FINALIZING }

data class GenerationStage(val sessionId: String, val phase: GenerationPhase)

interface AppActions {
    fun openSession(id: String?)
    fun createSession(roleIds: List<String>)
    fun updateSession(session: ChatSession, onSaved: (() -> Unit)? = null)
    fun deleteSession(id: String)
    fun saveRole(role: RoleCard, onSaved: (() -> Unit)? = null)
    fun deleteRole(id: String)
    fun saveLibrary(document: LibraryDocument, onSaved: (() -> Unit)? = null)
    fun deleteLibrary(id: String)
    fun saveConnection(connection: ApiConnection, apiKey: String?, onSaved: (() -> Unit)? = null)
    fun deleteConnection(id: String)
    fun savePreferences(preferences: UiPreferences)
    fun saveEmbedding(settings: EmbeddingSettings)
    fun send(text: String, targetRoleId: String? = null)
    fun updateComposerDraft(sessionId: String, text: String)
    fun sendFromComposer(sessionId: String, text: String, targetRoleId: String? = null)
    fun retryReply(sessionId: String, fallbackTargetRoleId: String? = null)
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
    fun clearImportResult()
    fun clearError()
    fun clearPrompt()
    fun suggestReplies()
    fun clearSuggestions()
    fun fetchModels(connection: ApiConnection, apiKey: String?)
    fun consumeOpenLibrary()
    fun clearScriptPanel()
    /** Clear the draft a card script pushed into the chat input. */
    fun consumeInputDraft()
    fun clearPendingAttachment()
}
