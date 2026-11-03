package app.xujing.runtime

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xujing.core.*
import app.xujing.core.compat.CardLibrary
import app.xujing.core.engine.CandidateSnapshots
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object CardRuntimeHost {
    var handler:((sessionId:String,method:String,payload:String,reply:(String)->Unit)->Unit)?=null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardMessage(content:String,session:ChatSession,message:ChatMessage,state:AppState,allowScripts:Boolean,onVariables:(JsonObject)->Unit,onSend:(String)->Unit) {
    var height by remember(message.id) { mutableIntStateOf(180) }
    val currentSession by rememberUpdatedState(session)
    val scriptsAllowed by rememberUpdatedState(allowScripts)
    val variablesCallback by rememberUpdatedState(onVariables)
    val sendCallback by rememberUpdatedState(onSend)
    var webView by remember { mutableStateOf<WebView?>(null) }
    val main=remember { Handler(Looper.getMainLooper()) }
    val body=remember(content) { Regex("```(?:html)?\\s*\\n([\\s\\S]*?)```",RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: content }
    val snapshot=buildJsonObject {
        put("sessionId",session.id); put("messageId",message.id); put("variables",session.variables)
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
                @JavascriptInterface fun height(value:Int) { main.post { height=value.coerceIn(64,2400) } }
                @JavascriptInterface fun request(id:String,method:String,payload:String) {
                    if(!scriptsAllowed) return
                    main.post {
                        fun reply(value:String) { main.post { evaluateJavascript("window.__xujingReply(${JsonPrimitive(id)},$value);",null) } }
                        val callback=CardRuntimeHost.handler
                        if(callback!=null) callback(currentSession.id,method,payload,::reply)
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
                    if(scriptsAllowed) view.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate($currentSnapshot);",null)
                }
            }
        }
    },update={ view ->
        view.settings.javaScriptEnabled=allowScripts
        val key="${message.id}:${body.hashCode()}:$allowScripts"
        if(view.tag!=key) {
            view.tag=key
            val runtime=view.context.assets.open("card-runtime.js").bufferedReader().use { it.readText() }
            val script=if(allowScripts) "<script>window.__XUJING_CONTEXT__=$snapshot;</script><script src=\"/runtime/zod.js\"></script><script src=\"/runtime/ejs.js\"></script><script>$runtime</script><script src=\"/runtime/mvu.js\"></script><script src=\"/runtime/template.js\"></script>" else ""
            val encodedBody=JsonPrimitive(body).toString().replace("<","\\u003c").replace("\u2028","\\u2028").replace("\u2029","\\u2029")
            val rendered=if(allowScripts) "<script>window.__xujingRenderCard($encodedBody).catch(e=>{document.body.textContent='角色卡执行失败：'+e.message;console.error(e);});</script>" else body
            val page="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;max-width:100%;overflow-wrap:anywhere}img,video,canvas{max-width:100%}</style>$script</head><body>$rendered</body></html>"
            view.loadDataWithBaseURL("https://cards.xujing.invalid/",page,"text/html","UTF-8",null)
        } else if(allowScripts) view.evaluateJavascript("window.__xujingUpdate&&window.__xujingUpdate($snapshot);",null)
    })
    DisposableEffect(Unit) { onDispose { webView?.apply { stopLoading(); removeJavascriptInterface("XujingBridge"); destroy() }; webView=null } }
}
