package app.xujing.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.xujing.core.*
import app.xujing.core.compat.CardLibrary
import app.xujing.core.compat.SessionScript
import app.xujing.core.compat.SessionScripts
import app.xujing.core.engine.CandidateSnapshots
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class SessionScriptButton(
    val sessionId: String,
    val scriptId: String,
    val scriptName: String,
    val name: String,
    val label: String,
    val enabled: Boolean,
)

/** Only the currently open session owns an event dispatcher. */
object SessionHooks {
    internal var owner: Any?=null
    internal var sessionId: String?=null
    internal var dispatcher: (suspend (String,JsonElement,String?)->JsonElement)?=null
    internal var synchronize: ((AppState,ChatSession)->Unit)?=null
    internal var cancelEvents: (() -> Unit)?=null
    internal var buttonDispatcher: ((String,String,String)->Unit)?=null
    private var scriptNames=linkedMapOf<String,String>()
    var buttons by mutableStateOf(emptyList<SessionScriptButton>())
        private set
    fun sync(state: AppState, session: ChatSession) { if(sessionId==session.id) synchronize?.invoke(state,session); CardRuntimeHost.sync(state,session) }
    fun cancel(id: String?) { if(id!=null && sessionId==id) cancelEvents?.invoke() }
    fun runButton(id: String, scriptId: String, name: String) {
        if(sessionId==id && buttons.any { it.sessionId==id && it.scriptId==scriptId && it.name==name && it.enabled }) buttonDispatcher?.invoke(id,scriptId,name)
    }
    fun configureScripts(id: String, scripts: List<SessionScript>) {
        if(sessionId!=id) return
        scriptNames=linkedMapOf<String,String>().apply { scripts.forEach { put(it.id,it.name) } }
        buttons=emptyList()
    }
    fun updateButtons(id: String, token: String, values: JsonArray) {
        if(sessionId!=id || !CardRuntimeHost.isTrusted(id,token)) return
        val scriptName=scriptNames[token] ?: return
        val updated=values.mapNotNull { value ->
            val item=value as? JsonObject ?: return@mapNotNull null
            val scriptId=item.text("scriptId")
            val name=item.text("name")
            if(scriptId!=token || name.isBlank() || item["visible"]?.jsonPrimitive?.booleanOrNull==false) return@mapNotNull null
            SessionScriptButton(id,token,scriptName,name,item.text("label",name),item["enabled"]?.jsonPrimitive?.booleanOrNull!=false)
        }
        val rank=scriptNames.keys.withIndex().associate { it.value to it.index }
        // Keep a deterministic order regardless of which script iframe finishes initialising first.
        buttons=(buttons.filterNot { it.scriptId==token }+updated).sortedBy { rank[it.scriptId] ?: Int.MAX_VALUE }
    }
    fun clearButtons(id: String) { if(sessionId==id) { buttons=emptyList(); scriptNames.clear(); buttonDispatcher=null } }
    /**
     * Dispatch [event] to session scripts and mounted cards.
     * A JsonArray payload is expanded into multiple callback arguments by the JS runtimes;
     * any other payload is passed as a single argument. Multi-argument events must use JsonArray.
     */
    suspend fun emit(id: String, event: String, payload: JsonElement, excludeToken:String?=null): JsonElement {
        val scriptResult=if(sessionId==id) dispatcher?.invoke(event,payload,excludeToken) ?: payload else payload
        return CardRuntimeHost.emit(id,event,scriptResult,excludeToken)
    }
}

/** Render evidence is buffered until the saved message's sent/received event has completed. */
object MessageRenders {
    private data class Pending(
        val sessionId: String,
        val messageId: String,
        val index: Int,
        val event: String,
        val type: String?,
        val content: String,
        val swipeIndex: Int,
        val updateAfterRender: Boolean,
        var armed: Boolean = false,
        var dispatching: Boolean = false,
        var job: Job? = null,
        var segments: Set<Int>? = null,
        val readySegments: MutableSet<Int> = mutableSetOf(),
        val ready: CompletableDeferred<Unit> = CompletableDeferred(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )
    private val pending = linkedMapOf<String, Pending>()
    private val openings = linkedMapOf<String, Pair<String, Int>>()
    var eventScope: CoroutineScope? = null
    var synchronize: ((String) -> Unit)? = null
    var revisionOf: ((String, String) -> Pair<String, Int>?)? = null
    /** Returns -1 for deleted messages and sessions which are no longer open. */
    var indexOf: ((String, String) -> Int)? = null
    private fun key(sessionId: String, messageId: String) = sessionId + "\u0000" + messageId

    /** Register before publishing content; arm only after its sent/received notification. */
    @Synchronized
    fun expect(sessionId: String, messageId: String, index: Int, event: String, type: String?, content: String, swipeIndex: Int, updateAfterRender: Boolean = false): CompletableDeferred<Unit> {
        if (messageId.isBlank()) return CompletableDeferred<Unit>().apply { complete(Unit) }
        val id=key(sessionId,messageId)
        if(type=="first_message" && openings[id]==(content to swipeIndex)) return CompletableDeferred<Unit>().apply { complete(Unit) }
        pending.remove(id)?.let { it.job?.cancel(); it.ready.cancel(); it.done.cancel() }
        val entry = Pending(sessionId, messageId, index, event, type, content, swipeIndex, updateAfterRender)
        pending[id] = entry
        return entry.done
    }

    fun cancel(done: CompletableDeferred<Unit>) {
        synchronized(this) {
            pending.values.firstOrNull { it.done === done }?.let { it.job?.cancel(); it.ready.cancel() }
            pending.entries.removeAll { it.value.done === done }
        }
        done.cancel()
    }

    fun arm(done: CompletableDeferred<Unit>) {
        val entry=synchronized(this) {
            pending.values.firstOrNull { it.done === done }?.also { it.armed=true }
        } ?: return
        dispatchIfReady(entry)
    }

    /** Offscreen messages never block generation indefinitely or pretend to have rendered. */
    suspend fun await(done: CompletableDeferred<Unit>, timeoutMs: Long = 1_500) {
        val entry=synchronized(this) { pending.values.firstOrNull { it.done === done } }
        if(entry==null) return
        try {
            val ready=withTimeoutOrNull(timeoutMs) { entry.ready.await(); true } ?: false
            // Once rendering happened, wait for dispatch; don't leave late updates running after a timeout.
            if(ready && (indexOf?.invoke(entry.sessionId,entry.messageId) ?: -1)>=0) {
                val completed=withTimeoutOrNull(30_000) { done.await(); true } ?: false
                if(!completed) {
                    cancel(done)
                    android.util.Log.w("XujingRuntime","消息渲染事件等待超时：${entry.messageId}")
                }
            }
        } catch(e:CancellationException) { if(!currentCoroutineContext().isActive) throw e }
    }

    fun forget(sessionId: String, messageId: String) {
        synchronized(this) {
            pending.remove(key(sessionId, messageId))?.let { it.job?.cancel(); it.ready.cancel(); it.done.cancel() }
            openings.remove(key(sessionId, messageId))
        }
    }

    fun clear(sessionId: String) {
        synchronized(this) {
            val prefix = sessionId + "\u0000"
            pending.values.filter { it.sessionId == sessionId }.forEach { it.job?.cancel(); it.ready.cancel(); it.done.cancel() }
            pending.entries.removeAll { it.value.sessionId == sessionId }
            openings.keys.removeAll { it.startsWith(prefix) }
        }
    }

    fun close() {
        val retired=synchronized(this) {
            val retired=pending.values.toList()
            pending.clear(); openings.clear(); indexOf=null; revisionOf=null; synchronize=null; eventScope=null
            retired
        }
        retired.forEach { it.job?.cancel(); it.ready.cancel(); it.done.cancel() }
    }

    private fun dispatchIfReady(entry: Pending) {
        val scope=eventScope ?: return
        synchronized(this) {
            if(pending[key(entry.sessionId,entry.messageId)] !== entry || !entry.armed || !entry.ready.isCompleted || entry.dispatching) return
            if((indexOf?.invoke(entry.sessionId,entry.messageId) ?: -1)<0) return
            entry.dispatching=true
        }
        // The lifetime belongs to the controller, not a Compose effect which can be disposed mid-event.
        entry.job=scope.launch {
            try {
                val index=indexOf?.invoke(entry.sessionId,entry.messageId) ?: -1
                if(index<0 || revisionOf?.invoke(entry.sessionId,entry.messageId)!=(entry.content to entry.swipeIndex)) return@launch
                synchronize?.invoke(entry.sessionId)
                val payload=if(entry.event=="character_message_rendered") JsonArray(listOf(JsonPrimitive(index),JsonPrimitive(entry.type ?: "normal")))
                    else JsonArray(listOf(JsonPrimitive(index)))
                SessionHooks.emit(entry.sessionId,entry.event,payload)
                if(entry.updateAfterRender) {
                    val liveIndex=indexOf?.invoke(entry.sessionId,entry.messageId) ?: -1
                    val stillExpected=synchronized(this@MessageRenders) { pending[key(entry.sessionId,entry.messageId)] === entry }
                    currentCoroutineContext().ensureActive()
                    if(liveIndex>=0 && stillExpected && revisionOf?.invoke(entry.sessionId,entry.messageId)==(entry.content to entry.swipeIndex)) {
                        synchronize?.invoke(entry.sessionId)
                        SessionHooks.emit(entry.sessionId,"message_updated",JsonArray(listOf(JsonPrimitive(liveIndex))))
                    }
                }
                if(entry.type=="first_message") synchronized(this@MessageRenders) { openings[key(entry.sessionId,entry.messageId)]=entry.content to entry.swipeIndex }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { /* Notifications must not break rendering or a saved generation. */ }
            finally {
                synchronized(this@MessageRenders) { if(pending[key(entry.sessionId,entry.messageId)] === entry) pending.remove(key(entry.sessionId,entry.messageId)) }
                entry.done.complete(Unit)
            }
        }
    }

    /** A mixed message is ready only after all of its HTML segments have reported the exact candidate. */
    fun rendered(sessionId: String, messageId: String, content: String, swipeIndex: Int, segments: Set<Int> = emptySet(), segmentIndex: Int? = null) {
        val entry = synchronized(this) {
            val entry = pending[key(sessionId, messageId)] ?: return
            if (entry.content != content || entry.swipeIndex != swipeIndex) return
            if(entry.segments!=segments) { entry.segments=segments; entry.readySegments.clear() }
            if(segmentIndex!=null) entry.readySegments.add(segmentIndex)
            if(segments.isEmpty() || entry.readySegments.containsAll(segments)) entry.ready.complete(Unit)
            entry
        }
        dispatchIfReady(entry)
    }
}

@Composable
fun SessionScriptRuntime(state: AppState, session: ChatSession) {
    if(!state.preferences.allowCardScripts || (session.extra["sessionScriptsEnabled"] as? JsonPrimitive)?.booleanOrNull!=true) {
        SideEffect { SessionHooks.clearButtons(session.id) }
        return
    }
    val presetData=state.library.firstOrNull { it.enabled && it.kind=="preset" && it.id==session.presetId }?.data
    val scripts=remember(state.roles,session.roleIds,session.extra["disabledSessionScriptIds"],session.presetId,presetData) { SessionScripts.collect(state,session) }
    if(scripts.isEmpty()) return
    val context=LocalContext.current
    val engine=remember(context,session.id,scripts) { SessionScriptEngine(context,session.id) }
    DisposableEffect(engine) {
        engine.start(state,session,scripts)
        onDispose { engine.close() }
    }
    SideEffect { engine.update(state,session) }
}

@SuppressLint("SetJavaScriptEnabled")
private class SessionScriptEngine(context: Context, private val id: String) {
    private val view=WebView(context)
    private val main=Handler(Looper.getMainLooper())
    private val ready=CompletableDeferred<Unit>()
    private val requests=mutableMapOf<String,CompletableDeferred<JsonElement>>()
    private val expectedScripts=mutableSetOf<String>()
    // The outer runtime page is trusted so it can forward parent.document panels.
    private val panelToken="xujing-session-host"
    private val initializedScripts=mutableSetOf<String>()
    private var sequence=0L
    private var closed=false
    private var snapshot=EmptyObject

    private fun snapshot(state: AppState, session: ChatSession)=buildJsonObject {
        put("sessionId",session.id); put("variables",session.variables)
        put("initRevision",(session.extra["xujing_init_revision"] as? JsonPrimitive)?.intOrNull ?: 0)
        put("characterId",session.roleIds.firstOrNull().orEmpty()); put("name1",session.userName)
        put("name2",state.roles.firstOrNull { it.id in session.roleIds }?.name.orEmpty())
        put("worldbookNames",JsonArray(CardLibrary.names(state,id).map(::JsonPrimitive)))
        put("presets",JsonArray(CardLibrary.presets(state).map { buildJsonObject { put("id",it.id); put("name",it.name); put("enabled",it.enabled); put("revision",it.data.hashCode()) } }))
        put("activePresetId",session.presetId?.let(::JsonPrimitive) ?: JsonNull)
        put("presetVariables",state.compatibility["presetVariables"] ?: EmptyObject)
        put("chat",JsonArray(session.messages.mapIndexed { index,message -> buildJsonObject {
            put("message_id",index); put("id",message.id); put("role",message.role)
            put("name",message.name.orEmpty()); put("message",message.content); put("content",message.content)
            put("is_user",message.role=="user"); put("extra",message.extra)
            put("swipe_id",message.swipeIndex); put("variables",CandidateSnapshots.variablesFor(message))
        } }))
    }
    private fun safe(value: JsonElement)=value.toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")

    fun start(state: AppState, session: ChatSession, scripts: List<SessionScript>) {
        snapshot=snapshot(state,session)
        expectedScripts.clear(); expectedScripts.addAll(scripts.map { it.id })
        initializedScripts.clear()
        scripts.forEach { CardRuntimeHost.trust(id,it.id) }
        CardRuntimeHost.trust(id,panelToken)
        view.settings.apply { javaScriptEnabled=true; domStorageEnabled=true; allowFileAccess=false; allowContentAccess=false; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW }
        view.addJavascriptInterface(object {
            @JavascriptInterface fun height(value: Int) = Unit
            @JavascriptInterface fun request(runtimeToken: String, requestId: String, method: String, payload: String) {
                main.post {
                    if(closed) return@post
                    if(!CardRuntimeHost.isTrusted(id,runtimeToken)) {
                        val error=buildJsonObject { put("error","会话脚本运行时已失效") }.toString()
                        view.evaluateJavascript("window.__xujingReplyForFrame(${JsonPrimitive(runtimeToken)},${JsonPrimitive(requestId)},$error)",null)
                    } else {
                        val callback=CardRuntimeHost.handler
                        if(callback!=null) callback(id,runtimeToken,method,payload) { result ->
                            main.post { if(!closed) view.evaluateJavascript("window.__xujingReplyForFrame(${JsonPrimitive(runtimeToken)},${JsonPrimitive(requestId)},$result)",null) }
                        } else {
                            val error=buildJsonObject { put("error","卡片宿主尚未就绪") }.toString()
                            view.evaluateJavascript("window.__xujingReplyForFrame(${JsonPrimitive(runtimeToken)},${JsonPrimitive(requestId)},$error)",null)
                        }
                    }
                }
            }
            @JavascriptInterface fun buttons(runtimeToken: String, value: String) {
                val parsed=runCatching { AppJson.parseToJsonElement(value) as? JsonArray ?: JsonArray(emptyList()) }.getOrDefault(JsonArray(emptyList()))
                main.post { if(!closed) SessionHooks.updateButtons(id,runtimeToken,parsed) }
            }
            @JavascriptInterface fun initialized(runtimeToken: String, error: String) { main.post {
                if(closed || runtimeToken !in expectedScripts || !CardRuntimeHost.isTrusted(id,runtimeToken)) return@post
                initializedScripts.add(runtimeToken)
                if(error.isNotBlank()) android.util.Log.w("XujingSession", "脚本 $runtimeToken 加载失败：$error")
                if(initializedScripts.containsAll(expectedScripts)) ready.complete(Unit)
            } }
            @JavascriptInterface fun complete(requestId: String, result: String, error: String) { main.post {
                val task=requests.remove(requestId) ?: return@post
                if(error.isNotBlank()) task.completeExceptionally(IllegalStateException(error))
                else runCatching { AppJson.parseToJsonElement(result) }.fold(task::complete,task::completeExceptionally)
            } }
        },"XujingBridge")
        view.webViewClient=object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest):Boolean =
                request.isForMainFrame || request.url.scheme!="about"
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if(request.url.host!="cards.xujing.invalid") return null
                val path=request.url.path.orEmpty()
                val asset=when(path) {
                    "/runtime/zod.js" -> "card-vendors/zod.js"
                    "/runtime/mvu.js" -> "card-vendors/mvu.js"
                    "/runtime/ejs.js" -> "card-vendors/ejs.js"
                    "/runtime/card-runtime.js" -> "card-runtime.js"
                    "/runtime/template.js" -> "card-template.js"
                    "/runtime/session-script-runtime.js" -> "session-script-runtime.js"
                    "/script.js" -> "card-modules/script.js"
                    else -> if(path in listOf("/scripts/utils.js","/scripts/openai.js","/scripts/world-info.js","/scripts/preset-manager.js")) "card-modules/${path.substringAfterLast('/')}" else null
                } ?: return WebResourceResponse("text/plain","UTF-8",404,"Not found",emptyMap(),"".byteInputStream())
                return WebResourceResponse("application/javascript","UTF-8",view.context.assets.open(asset))
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if(closed || ready.isCompleted) return
                val sources=JsonArray(scripts.map { script -> buildJsonObject {
                    put("id",script.id); put("name",script.name); put("info",script.info); put("content",script.content); put("buttons",script.buttons)
                } })
                view.evaluateJavascript("window.__xujingLoadSessionScripts(${safe(snapshot)},${safe(sources)});",null)
            }
        }
        SessionHooks.owner=this; SessionHooks.sessionId=id; SessionHooks.synchronize=::update
        SessionHooks.configureScripts(id,scripts)
        SessionHooks.cancelEvents={
            requests.values.toList().forEach { it.cancel() }; requests.clear()
        }
        SessionHooks.buttonDispatcher={ buttonSession,scriptId,name ->
            if(buttonSession==id && !closed && ready.isCompleted) {
                view.evaluateJavascript("window.__xujingRunButton("+JsonPrimitive(scriptId)+","+JsonPrimitive(name)+")",null)
            }
        }
        SessionHooks.dispatcher={ event,payload,excludeToken ->
            try { withTimeout(30_000) { ready.await() } }
            catch(e:TimeoutCancellationException) { throw IllegalStateException("角色独立脚本加载超时，请检查网络依赖或关闭对应脚本",e) }
            try { withTimeout(15_000) {
                check(!closed) { "会话脚本已关闭" }
                val key=(++sequence).toString(); val task=CompletableDeferred<JsonElement>()
                requests[key]=task
                try {
                    view.evaluateJavascript("window.__xujingUpdate(${safe(snapshot)}); window.__xujingDispatchSessionEvent(${JsonPrimitive(key)},${JsonPrimitive(event)},${safe(payload)},${JsonPrimitive(excludeToken.orEmpty())})",null)
                    task.await()
                } finally { requests.remove(key) }
            } } catch(e:TimeoutCancellationException) { throw IllegalStateException("角色脚本事件 $event 执行超时",e) }
        }
        val sessionRuntime=contextAssets("session-runtime.js")
        val page="<!doctype html><html><head><meta charset='utf-8'><script>$sessionRuntime</script></head><body></body></html>"
        view.loadDataWithBaseURL("https://cards.xujing.invalid/",page,"text/html","UTF-8",null)
    }
    private fun contextAssets(name: String)=view.context.assets.open(name).bufferedReader().use { it.readText() }
    fun update(state: AppState, session: ChatSession) {
        snapshot=snapshot(state,session)
        if(!closed) view.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate(${safe(snapshot)})",null)
    }
    fun close() {
        closed=true
        ready.cancel(); requests.values.toList().forEach { it.cancel() }; requests.clear()
        CardRuntimeHost.untrust(id,panelToken)
        expectedScripts.forEach { CardRuntimeHost.untrust(id,it) }
        expectedScripts.clear(); initializedScripts.clear()
        if(SessionHooks.owner===this) { SessionHooks.dispatcher=null; SessionHooks.synchronize=null; SessionHooks.cancelEvents=null; SessionHooks.clearButtons(id); SessionHooks.owner=null; SessionHooks.sessionId=null }
        view.evaluateJavascript("window.dispatchEvent(new Event('pagehide'))",null)
        view.stopLoading(); view.removeJavascriptInterface("XujingBridge"); view.destroy()
    }
}
