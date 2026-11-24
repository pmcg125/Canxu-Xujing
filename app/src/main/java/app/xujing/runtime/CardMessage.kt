package app.xujing.runtime

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xujing.core.*
import app.xujing.core.compat.CardLibrary
import app.xujing.core.engine.CandidateSnapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private fun cardSnapshot(state:AppState, session:ChatSession, message:ChatMessage, endpointToken:String, theme:JsonObject=EmptyObject):String = buildJsonObject {
    put("sessionId",session.id); put("messageId",message.id); put("runtimeToken",endpointToken); put("variables",session.variables)
    put("presetVariables",state.compatibility["presetVariables"] ?: EmptyObject)
    put("globalVariables",state.compatibility["globalVariables"] ?: EmptyObject)
    put("theme",theme)
    put("initRevision",(session.extra["xujing_init_revision"] as? JsonPrimitive)?.intOrNull ?: 0)
    put("characterId",message.speakerId ?: session.roleIds.firstOrNull().orEmpty())
    put("worldbookNames",JsonArray(CardLibrary.names(state,session.id).map(::JsonPrimitive)))
    put("presets",JsonArray(CardLibrary.presets(state).map { buildJsonObject { put("id",it.id); put("name",it.name); put("enabled",it.enabled); put("revision",it.data.hashCode()) } }))
    put("activePresetId",session.presetId?.let(::JsonPrimitive) ?: JsonNull)
    put("chat",AppJson.encodeToJsonElement(session.messages.mapIndexed { index,m -> buildJsonObject {
        put("message_id",index); put("id",m.id); put("role",m.role); put("name",m.name.orEmpty()); put("message",m.content); put("content",m.content); put("is_user",m.role=="user"); put("extra",m.extra)
        put("variables",CandidateSnapshots.variablesFor(m)); put("swipe_id",m.swipeIndex)
    } })); put("name1",session.userName); put("name2",message.name.orEmpty())
}.toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")

object CardRuntimeHost {
    private data class Endpoint(
        val sessionId:String,
        val token:String,
        val messageIndex:Int,
        val segmentIndex:Int,
        val synchronize:(AppState,ChatSession)->Unit,
        val dispatch:suspend (String,JsonElement)->JsonElement,
    )
    var handler:((sessionId:String,runtimeToken:String,method:String,payload:String,reply:(String)->Unit)->Unit)?=null
    private val endpoints=linkedMapOf<String,Endpoint>()
    private val trustedTokens=linkedMapOf<String,MutableSet<String>>()
    private fun key(sessionId:String,token:String)=sessionId+":"+token
    @Synchronized fun trust(sessionId:String,token:String) {
        if(token.isNotBlank()) trustedTokens.getOrPut(sessionId) { linkedSetOf() }.add(token)
    }
    @Synchronized fun untrust(sessionId:String,token:String) {
        trustedTokens[sessionId]?.let { tokens -> tokens.remove(token); if(tokens.isEmpty()) trustedTokens.remove(sessionId) }
    }
    @Synchronized fun isTrusted(sessionId:String,token:String)=token.isNotBlank() && token in trustedTokens[sessionId].orEmpty()
    @Synchronized fun register(sessionId:String,token:String,messageIndex:Int,segmentIndex:Int,synchronize:(AppState,ChatSession)->Unit,dispatch:suspend (String,JsonElement)->JsonElement) {
        endpoints[key(sessionId,token)]=Endpoint(sessionId,token,messageIndex,segmentIndex,synchronize,dispatch)
        trust(sessionId,token)
    }
    @Synchronized fun unregister(sessionId:String,token:String) {
        endpoints.remove(key(sessionId,token))
        untrust(sessionId,token)
    }
    @Synchronized private fun snapshot(sessionId:String,excludeToken:String?):List<Endpoint> =
        endpoints.values.filter { it.sessionId==sessionId && it.token!=excludeToken }
            .sortedWith(compareBy<Endpoint> { it.messageIndex }.thenBy { it.segmentIndex })
    fun sync(state:AppState,session:ChatSession) {
        snapshot(session.id,null).forEach { it.synchronize(state,session) }
    }
    suspend fun emit(sessionId:String,event:String,payload:JsonElement,excludeToken:String?=null):JsonElement {
        var current=payload
        for(endpoint in snapshot(sessionId,excludeToken)) {
            current=try { withTimeout(15_000) { endpoint.dispatch(event,current) } }
            catch(e:TimeoutCancellationException) { current }
            // A card disposed mid-dispatch cancels its pending event; that must not abort the generation.
            catch(e:CancellationException) { if(currentCoroutineContext().isActive) current else throw e }
            catch(e:Exception) { current }
        }
        return current
    }
}

/** Colours and type scale the shell mirrors onto `.mes_text`, so card scripts can read them. */
private fun cssColor(value:androidx.compose.ui.graphics.Color):String {
    val argb=value.toArgb()
    val r=android.graphics.Color.red(argb); val g=android.graphics.Color.green(argb); val b=android.graphics.Color.blue(argb)
    val a=android.graphics.Color.alpha(argb)/255f
    return "rgba($r, $g, $b, $a)"
}

/** JSON string literal that is safe inside an inline <script> block. */
private fun jsLiteral(value:String):String = JsonPrimitive(value).toString()
    .replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")

private const val SHELL_STYLE =
    "html,body{margin:0;padding:0;background:transparent;overflow-wrap:anywhere}" +
    ".mes_text{margin:0;padding:0;display:block;width:100%;max-width:100%;overflow-wrap:anywhere}" +
    ".mes_text iframe{display:block;width:100%;min-height:24px;border:0;padding:0;margin:0;background:transparent;overflow:hidden;color:inherit;font:inherit}" +
    "#send_textarea{position:fixed;left:-10000px;top:0;width:1px;height:1px;opacity:0;border:0;padding:0;margin:0;resize:none;pointer-events:none}"

/**
 * Outer page for a scripted card. The author HTML runs inside a same-origin frame, so preset
 * scripts can reach `window.parent.document`, `window.parent.triggerSlash` and `frameElement`.
 */
private fun messageShellPage(snapshot:String,theme:JsonObject,frameDocument:String,shellRuntime:String):String =
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta charset=\"utf-8\"><style>"+SHELL_STYLE+"</style></head><body>"+
    "<div class=\"mes_text\"><iframe id=\"xujing-message-frame\" title=\"角色卡消息\" scrolling=\"no\"></iframe></div>"+
    "<textarea id=\"send_textarea\" aria-hidden=\"true\" tabindex=\"-1\"></textarea>"+
    "<script>window.__XUJING_CONTEXT__="+snapshot+";window.__XUJING_THEME__="+theme+";window.__XUJING_MESSAGE_HTML__="+jsLiteral(frameDocument)+";</script>"+
    "<script>"+shellRuntime+"</script></body></html>"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardMessage(content:String,session:ChatSession,message:ChatMessage,state:AppState,allowScripts:Boolean,onVariables:(JsonObject)->Unit,onSend:(String)->Unit,documentMode:Boolean=false,runtimeToken:String="",segmentIndex:Int=0,renderSegments:Set<Int> = setOf(segmentIndex)) {
    var height by remember(message.id) { mutableIntStateOf(180) }
    val currentSession by rememberUpdatedState(session)
    val scriptsAllowed by rememberUpdatedState(allowScripts)
    val variablesCallback by rememberUpdatedState(onVariables)
    val sendCallback by rememberUpdatedState(onSend)
    var webView by remember { mutableStateOf<WebView?>(null) }
    val main=remember { Handler(Looper.getMainLooper()) }
    val body=content
    val endpointToken=remember(runtimeToken,body,message.swipeIndex) { runtimeToken.ifBlank { message.id+":"+message.swipeIndex }+":"+java.util.UUID.randomUUID() }
    val messageIndex=session.messages.indexOfFirst { it.id==message.id }
    val pageReady=remember { mutableStateOf(false) }
    val renderedRevision=remember { mutableStateOf(message.content to message.swipeIndex) }
    val eventPending=remember { mutableMapOf<String,kotlinx.coroutines.CompletableDeferred<JsonElement>>() }
    val eventSequence=remember { java.util.concurrent.atomic.AtomicLong() }
    val colors=MaterialTheme.colorScheme
    val bodyFontSize=MaterialTheme.typography.bodyMedium.fontSize.value
    val theme=remember(colors.onSurface,colors.surface,colors.onSurfaceVariant,colors.primary,bodyFontSize) { buildJsonObject {
        put("textColor",cssColor(colors.onSurface))
        put("backgroundColor",cssColor(colors.surface))
        put("secondaryTextColor",cssColor(colors.onSurfaceVariant))
        put("accentColor",cssColor(colors.primary))
        put("fontSize",bodyFontSize)
        put("dark",colors.surface.luminance()<0.5f)
    } }
    val currentTheme by rememberUpdatedState(theme)
    val snapshot=cardSnapshot(state,session,message,endpointToken,theme)
    val currentSnapshot by rememberUpdatedState(snapshot)
    AndroidView(modifier=Modifier.fillMaxWidth().height(height.dp),factory={ context ->
        WebView(context).apply {
            webView=this
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled=allowScripts
            settings.domStorageEnabled=allowScripts
            settings.allowFileAccess=false; settings.allowContentAccess=false
            settings.javaScriptCanOpenWindowsAutomatically=false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(object {
                @JavascriptInterface fun height(value:Int) { main.post { height=value.coerceIn(28,12000) } }
                @JavascriptInterface fun request(token:String,id:String,method:String,payload:String) {
                    if(!scriptsAllowed) return
                    main.post {
                        fun reply(value:String) { main.post { evaluateJavascript("window.__xujingReply(${JsonPrimitive(id)},$value);",null) } }
                        if(token!=endpointToken || !CardRuntimeHost.isTrusted(currentSession.id,token)) {
                            reply(buildJsonObject { put("error","卡片运行时已失效") }.toString())
                            return@post
                        }
                        val callback=CardRuntimeHost.handler
                        if(callback!=null) callback(currentSession.id,token,method,payload,::reply)
                        else try {
                            val data=AppJson.parseToJsonElement(payload)
                            when(method) {
                                "getVariables" -> reply(currentSession.variables.toString())
                                "getContext" -> reply(currentSnapshot)
                                "replaceVariables", "setVariables" -> { val obj=data.jsonObject; val vars=obj["variables"] as? JsonObject ?: obj; variablesCallback(vars); reply(vars.toString()) }
                                "send", "sendMessage" -> { sendCallback(data.jsonObject.text("text")); reply("true") }
                                else -> reply(buildJsonObject { put("error","尚未实现的卡片接口：$method") }.toString())
                            }
                        } catch(e:Exception) { reply(buildJsonObject { put("error",e.message.orEmpty()) }.toString()) }
                    }
                }
                @JavascriptInterface fun eventComplete(id:String,result:String,error:String) {
                    main.post {
                        val task=eventPending.remove(id) ?: return@post
                        if(error.isNotBlank()) task.completeExceptionally(IllegalStateException(error))
                        else runCatching { AppJson.parseToJsonElement(result) }.fold(task::complete,task::completeExceptionally)
                    }
                }
                @JavascriptInterface fun ready(token:String) { main.post { if(scriptsAllowed && token==endpointToken) pageReady.value=true } }
            },"XujingBridge")
            webViewClient=object:WebViewClient() {
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=request.isForMainFrame
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    val path=request.url.path.orEmpty()
                    if(request.url.host=="cards.xujing.invalid" && path.startsWith("/runtime/")) {
                        val asset=when(path) {
                            "/runtime/zod.js" -> "card-vendors/zod.js"
                            "/runtime/ejs.js" -> "card-vendors/ejs.js"
                            "/runtime/mvu.js" -> "card-vendors/mvu.js"
                            "/runtime/template.js" -> "card-template.js"
                            else -> null
                        }
                        if(asset!=null) return WebResourceResponse("application/javascript","UTF-8",view.context.assets.open(asset))
                    }
                    if(request.url.host=="cards.xujing.invalid" && path=="/script.js")
                        return WebResourceResponse("application/javascript","UTF-8",view.context.assets.open("card-modules/script.js"))
                    if(request.url.host=="cards.xujing.invalid" && path.startsWith("/scripts/")) {
                        val retained=when(path.substringAfterLast('/')) {
                            "utils.js" -> "card-modules/utils.js"
                            "openai.js" -> "card-modules/openai.js"
                            "world-info.js" -> "card-modules/world-info.js"
                            "preset-manager.js" -> "card-modules/preset-manager.js"
                            else -> null
                        }
                        if(retained!=null) return WebResourceResponse("application/javascript","UTF-8",view.context.assets.open(retained))
                        val module=when(path.substringAfterLast('/')) {
                            else -> "export const getContext=()=>TavernHelper.getContext();"
                        }
                        return WebResourceResponse("application/javascript","UTF-8",module.byteInputStream())
                    }
                    return super.shouldInterceptRequest(view,request)
                }
                override fun onPageFinished(view:WebView,url:String?) {
                    if(scriptsAllowed) {
                        view.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate($currentSnapshot);",null)
                    } else pageReady.value=true
                }
            }
        }
    },update={ view ->
        view.settings.javaScriptEnabled=allowScripts
        if(!allowScripts) CardRuntimeHost.unregister(session.id,endpointToken)
        else CardRuntimeHost.register(session.id,endpointToken,messageIndex,segmentIndex,synchronize={ latestState,latestSession ->
            val latestMessage=latestSession.messages.firstOrNull { it.id==message.id } ?: message
            val latestSnapshot=cardSnapshot(latestState,latestSession,latestMessage,endpointToken,currentTheme)
            main.post { webView?.let { target -> if(pageReady.value && target.isAttachedToWindow) runCatching { target.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate($latestSnapshot);",null) } } }
        }) { event,payload ->
            if(!pageReady.value) return@register payload
            val targetView=webView ?: return@register payload
            if(!targetView.isAttachedToWindow) return@register payload
            val requestId=endpointToken+":"+eventSequence.incrementAndGet()
            val task=kotlinx.coroutines.CompletableDeferred<JsonElement>()
            eventPending[requestId]=task
            val encoded=payload.toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")
            main.post { runCatching { targetView.evaluateJavascript("window.__xujingSessionEvent("+JsonPrimitive(requestId)+","+JsonPrimitive(event)+","+encoded+")",null) }
                .onFailure { eventPending.remove(requestId); task.complete(payload) } }
            try { withTimeout(15_000) { task.await() } }
            catch(e:TimeoutCancellationException) { eventPending.remove(requestId); payload }
            catch(e:CancellationException) { eventPending.remove(requestId); throw e }
            catch(e:Exception) { eventPending.remove(requestId); payload }
        }
        val key="${message.id}:${message.swipeIndex}:${runtimeToken}:${body.hashCode()}:$allowScripts:$documentMode"
        if(view.tag!=key) {
            view.tag=key
            pageReady.value=false
            renderedRevision.value=message.content to message.swipeIndex
            val runtime=view.context.assets.open("card-runtime.js").bufferedReader().use { it.readText() }
            val shellRuntime=view.context.assets.open("message-shell.js").bufferedReader().use { it.readText() }
            val script=if(allowScripts) "<script>window.__XUJING_CONTEXT__=$snapshot;</script><script src=\"/runtime/zod.js\"></script><script src=\"/runtime/ejs.js\"></script><script>$runtime</script><script src=\"/runtime/mvu.js\"></script><script src=\"/runtime/template.js\"></script>" else ""
            val encodedBody=JsonPrimitive(body).toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")
            val rendered=when {
                !allowScripts && documentMode -> body
                allowScripts && documentMode -> "<script>(async()=>{try{const parsed=new DOMParser().parseFromString($encodedBody,'text/html');const sourceScripts=[...parsed.querySelectorAll('script')].filter(node=>!node.closest('template')).map(node=>({node,head:node.parentElement===parsed.head}));sourceScripts.forEach(item=>item.node.remove());for(const attr of [...document.documentElement.attributes])document.documentElement.removeAttribute(attr.name);for(const attr of [...parsed.documentElement.attributes])document.documentElement.setAttribute(attr.name,attr.value);for(const attr of [...parsed.body.attributes])document.body.setAttribute(attr.name,attr.value);for(const node of [...parsed.head.childNodes])document.head.appendChild(document.importNode(node,true));document.body.innerHTML=parsed.body.innerHTML;for(const item of sourceScripts){const live=document.createElement('script');for(const attr of [...item.node.attributes])live.setAttribute(attr.name,attr.value);live.textContent=item.node.textContent||'';const parent=item.head?document.head:document.body;await new Promise(resolve=>{if(live.src){live.onload=resolve;live.onerror=resolve;}parent.appendChild(live);if(!live.src)resolve();});}await window.__xujingCardReady();}catch(e){document.body.textContent='HTML 文档加载失败：'+e.message;console.error(e);if(window.__xujingCardReady) await window.__xujingCardReady().catch(()=>{});}})();</script>"
                allowScripts -> "<script>window.__xujingRenderCard($encodedBody).catch(e=>{document.body.textContent='角色卡执行失败：'+e.message;console.error(e);return window.__xujingCardReady();});</script>"
                else -> body
            }
            val page=when {
                documentMode && !allowScripts -> body
                // A scripted card runs inside a same-origin frame of the shell page, so preset
                // HTML/JS can reach window.parent.document and #send_textarea.
                allowScripts -> messageShellPage(snapshot,theme,
                    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><base href=\"https://cards.xujing.invalid/\"><script>try{window.XujingBridge=parent.XujingBridge||window.XujingBridge}catch(e){}</script>$script<style>html,body{margin:0;padding:0;max-width:100%;overflow-wrap:anywhere;background:transparent;color:inherit}img,video,canvas{max-width:100%}</style></head><body>$rendered</body></html>",
                    shellRuntime)
                else -> "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;max-width:100%;overflow-wrap:anywhere}img,video,canvas{max-width:100%}</style>$script</head><body>$rendered</body></html>"
            }
            view.loadDataWithBaseURL("https://cards.xujing.invalid/",page,"text/html","UTF-8",null)
        } else {
            if(allowScripts) view.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate($snapshot);",null)
            if(pageReady.value) renderedRevision.value=message.content to message.swipeIndex
        }
    })
    // Each segment acknowledges the loaded candidate; the tracker waits for the whole message.
    LaunchedEffect(pageReady.value, renderedRevision.value, renderSegments) {
        if(pageReady.value) {
            withFrameNanos { }
            MessageRenders.rendered(session.id, message.id, renderedRevision.value.first, renderedRevision.value.second, renderSegments, segmentIndex)
        }
    }
    DisposableEffect(endpointToken) { onDispose { CardRuntimeHost.unregister(session.id,endpointToken); eventPending.values.forEach { it.cancel() }; eventPending.clear(); webView?.apply { stopLoading(); removeJavascriptInterface("XujingBridge"); destroy() }; webView=null } }
}
