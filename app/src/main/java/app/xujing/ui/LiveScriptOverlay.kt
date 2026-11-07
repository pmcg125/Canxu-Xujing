package app.xujing.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.xujing.core.*
import app.xujing.runtime.CardRuntimeHost
import app.xujing.runtime.SessionHooks
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minimal live overlay spike.
 *
 * Preset scripts that build their UI through `parent.document` normally run inside a hidden
 * WebView: their floating nodes are serialised into HTML and shown in [ScriptPanelOverlay].
 * When a session opts in with `extra["liveOverlayEnabled"] == true` the first session script is
 * handed to this overlay instead, so the script runs in a visible WebView that can render,
 * drag and click real fixed nodes.
 *
 * Everything here is a plain Compose observable object; [sessionId]/[visible]/[rect] drive the
 * Composable and the script engine writes the script + snapshot. No changes to UiRuntime.
 */
data class LiveOverlayRect(val x: Float, val y: Float, val width: Float, val height: Float)

object LiveOverlayState {
    var sessionId by mutableStateOf<String?>(null)
        private set
    var enabled by mutableStateOf(false)
        private set
    var visible by mutableStateOf(false)
        private set
    var scriptId by mutableStateOf("")
        private set
    var scriptName by mutableStateOf("")
        private set
    var scriptContent by mutableStateOf("")
        private set
    /** CSS-pixel bounds of the union of script fixed nodes. Null until the shell reports one. */
    var rect by mutableStateOf<LiveOverlayRect?>(null)
        private set
    /** Scripts that stayed on the hidden runtime because only the first one goes live. */
    var extraScripts by mutableStateOf(0)
        private set
    /** Bumped when the shell document must be reloaded (new session/script/content). */
    var revision by mutableStateOf(0)
        private set

    internal var snapshot: JsonElement = EmptyObject
        private set
    internal var scriptButtons: JsonElement = JsonArray(emptyList())
        private set
    internal var view: WebView? = null
        private set

    fun disable(session: String) {
        if (sessionId == session) {
            visible = false
            enabled = false
            rect = null
        }
    }

    /** Engine entry point: the session opted in and this script is the live one. */
    fun open(
        session: String,
        id: String,
        name: String,
        content: String,
        buttons: JsonElement,
        data: JsonElement,
        extra: Int,
    ) {
        sessionId = session
        scriptId = id
        scriptName = name
        scriptContent = content
        scriptButtons = buttons
        snapshot = data
        extraScripts = extra
        enabled = true
        visible = content.isNotBlank()
        rect = null
        revision++
    }

    /** User closed the overlay chip. The engine keeps running but stops reporting to a dead view. */
    fun close() {
        visible = false
        rect = null
    }

    fun dismiss(session: String) {
        if (sessionId == session) {
            visible = false
            rect = null
        }
    }

    fun setRect(session: String, x: Float, y: Float, width: Float, height: Float) {
        if (sessionId != session || !visible || scriptContent.isBlank()) return
        if (width < 1f || height < 1f) return
        val next = LiveOverlayRect(x, y, width, height)
        val current = rect
        if (current != null &&
            abs(current.x - next.x) < 1.5f && abs(current.y - next.y) < 1.5f &&
            abs(current.width - next.width) < 1.5f && abs(current.height - next.height) < 1.5f
        ) return
        rect = next
    }

    /** The shell has no drawable nodes: shrink the WebView out of the way but keep JS alive. */
    fun collapse(session: String) {
        if (sessionId == session) rect = null
    }

    fun updateSnapshot(session: String, data: JsonElement) {
        if (sessionId != session) return
        if (snapshot.hashCode() == data.hashCode()) return
        snapshot = data
        view?.evaluateJavascript("window.__xujingLiveUpdate&&window.__xujingLiveUpdate(${safe(data)})", null)
    }

    internal fun attach(target: WebView) {
        view = target
    }

    internal fun detach(target: WebView) {
        if (view === target) view = null
    }

    internal fun dispatchEvent(session: String, event: String, payload: JsonElement, excludeToken: String?) {
        if (sessionId != session || !visible) return
        val target = view ?: return
        target.evaluateJavascript(
            "window.__xujingLiveDispatch&&window.__xujingLiveDispatch(" +
                "${JsonPrimitive(event)},${safe(payload)},${JsonPrimitive(excludeToken.orEmpty())})",
            null,
        )
    }

    internal fun runButton(session: String, script: String, name: String) {
        if (sessionId != session || !visible) return
        val target = view ?: return
        target.evaluateJavascript(
            "window.__xujingLiveButton&&window.__xujingLiveButton(" +
                "${JsonPrimitive(script)},${JsonPrimitive(name)})",
            null,
        )
    }

    private fun safe(value: JsonElement) = value.toString()
        .replace("<", "\\u003c")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}

private const val LIVE_BASE_URL = "https://cards.xujing.invalid/"

/**
 * Positions a transparent WebView over the union of the script's floating nodes.
 *
 * The shell page is screen-sized even when the Android WebView is tiny: fixed nodes are laid out
 * against the shell's transformed body, so the union can be measured in a collapsed viewport and
 * then only that region is covered, leaving the chat touchable everywhere else.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiveScriptOverlay(sessionId: String) {
    if (LiveOverlayState.sessionId != sessionId || !LiveOverlayState.visible) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val theme = remember(scheme.surface, scheme.onSurface, scheme.primary, scheme.surfaceVariant, scheme.outline, dark) {
        buildJsonObject {
            put("dark", dark)
            put("background", hexColor(scheme.surface))
            put("foreground", hexColor(scheme.onSurface))
            put("muted", hexColor(scheme.onSurfaceVariant))
            put("accent", hexColor(scheme.primary))
            put("border", hexColor(scheme.outline))
            put("surface", hexColor(scheme.surfaceVariant))
        }
    }
    val shellSource = remember(context) {
        context.assets.open("overlay-shell.js").bufferedReader().use { it.readText() }
    }
    val main = remember { Handler(Looper.getMainLooper()) }
    val holder = remember { arrayOfNulls<WebView>(1) }
    val themeHolder = remember { arrayOfNulls<String>(1) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        // No rect yet: stay at 1x1 so loading scripts never eat the chat. The shell still measures
        // because its body is laid out at the full screen size.
        val rect = LiveOverlayState.rect ?: LiveOverlayRect(0f, 0f, 1f, 1f)
        val boot = buildJsonObject {
            put("sessionId", sessionId)
            put("scriptId", LiveOverlayState.scriptId)
            put("scriptName", LiveOverlayState.scriptName)
            put("scriptContent", LiveOverlayState.scriptContent)
            put("scriptButtons", LiveOverlayState.scriptButtons)
            put("snapshot", LiveOverlayState.snapshot)
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("theme", theme)
        }
        val viewKey = LiveOverlayState.revision.toString() + ":" + screenWidth + "x" + screenHeight + ":" + dark
        AndroidView(
            modifier = Modifier
                .offset {
                    IntOffset(rect.x.roundToInt(), rect.y.roundToInt())
                }
                .size(
                    with(density) { rect.width.coerceAtLeast(1f).toDp() },
                    with(density) { rect.height.coerceAtLeast(1f).toDp() },
                ),
            factory = { ctx ->
                val web = WebView(ctx)
                web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                }
                web.addJavascriptInterface(LiveSurfaceBridge(sessionId, main), "XujingLive")
                web.addJavascriptInterface(LiveScriptBridge(sessionId, main) { web }, "XujingBridge")
                web.webViewClient = liveClient()
                web.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        Log.w("XujingLive", "console ${message.message()} @${message.lineNumber()}")
                        return true
                    }
                }
                web.tag = ""
                holder[0] = web
                LiveOverlayState.attach(web)
                web
            },
            update = { web ->
                val themeJson = theme.toString()
                if (web.tag != viewKey) {
                    web.tag = viewKey
                    themeHolder[0] = themeJson
                    web.loadDataWithBaseURL(
                        LIVE_BASE_URL,
                        shellDocument(shellSource, boot),
                        "text/html",
                        "utf-8",
                        null,
                    )
                } else if (themeHolder[0] != themeJson) {
                    themeHolder[0] = themeJson
                    web.evaluateJavascript(
                        "window.__xujingLiveTheme&&window.__xujingLiveTheme($themeJson)",
                        null,
                    )
                }
            },
        )
        if (LiveOverlayState.rect != null) {
            val chipSize = with(density) { 24.dp.toPx() }
            val chipX = (rect.x + rect.width - chipSize).coerceIn(0f, (screenWidth - chipSize).coerceAtLeast(0f))
            val chipY = (rect.y - chipSize - 8f).coerceIn(0f, (screenHeight - chipSize).coerceAtLeast(0f))
            ScriptCloseChip(
                onClose = { LiveOverlayState.close() },
                modifier = Modifier.offset {
                    IntOffset(chipX.roundToInt(), chipY.roundToInt())
                },
            )
        }
    }

    DisposableEffect(sessionId) {
        onDispose {
            holder[0]?.let { LiveOverlayState.detach(it) }
        }
    }
}

private fun hexColor(value: androidx.compose.ui.graphics.Color): String =
    "#" + Integer.toHexString(value.toArgb() and 0xFFFFFF).padStart(6, '0')

private fun shellDocument(shell: String, boot: JsonObject): String {
    val json = boot.toString()
        .replace("<", "\\u003c")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">" +
        "<script>window.__XUJING_LIVE_BOOT__=$json;</script></head>" +
        "<body><script>$shell</script></body></html>"
}

private fun liveClient() = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame || request.url.scheme != "about"

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != "cards.xujing.invalid") return null
        val path = request.url.path.orEmpty()
        val asset = when (path) {
            "/runtime/zod.js" -> "card-vendors/zod.js"
            "/runtime/mvu.js" -> "card-vendors/mvu.js"
            "/runtime/ejs.js" -> "card-vendors/ejs.js"
            "/runtime/card-runtime.js" -> "card-runtime.js"
            "/runtime/template.js" -> "card-template.js"
            "/runtime/session-script-runtime.js" -> "session-script-runtime.js"
            "/script.js" -> "card-modules/script.js"
            else -> if (path in setOf("/scripts/utils.js", "/scripts/openai.js", "/scripts/world-info.js", "/scripts/preset-manager.js"))
                "card-modules/${path.substringAfterLast('/')}" else null
        } ?: return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), "".byteInputStream())
        return WebResourceResponse("application/javascript", "UTF-8", view.context.assets.open(asset))
    }
}

/** Shell -> host geometry/close bridge (`XujingLive`). */
internal class LiveSurfaceBridge(private val sessionId: String, private val main: Handler) {
    @JavascriptInterface
    fun rect(x: Double, y: Double, width: Double, height: Double) {
        main.post { LiveOverlayState.setRect(sessionId, x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat()) }
    }

    @JavascriptInterface
    fun empty() {
        main.post { LiveOverlayState.collapse(sessionId) }
    }

    @JavascriptInterface
    fun close() {
        main.post { LiveOverlayState.close() }
    }
}

/** Same `XujingBridge` surface the hidden runtime exposes, so card-runtime.js is unchanged. */
internal class LiveScriptBridge(
    private val sessionId: String,
    private val main: Handler,
    private val target: () -> WebView?,
) {
    @JavascriptInterface
    fun height(value: Int) = Unit

    @JavascriptInterface
    fun request(runtimeToken: String, requestId: String, method: String, payload: String) {
        main.post {
            val web = target() ?: return@post
            if (!CardRuntimeHost.isTrusted(sessionId, runtimeToken)) {
                reply(web, runtimeToken, requestId, """{"error":"会话脚本运行时已失效"}""")
                return@post
            }
            val callback = CardRuntimeHost.handler
            if (callback == null) {
                reply(web, runtimeToken, requestId, """{"error":"卡片宿主尚未就绪"}""")
            } else {
                callback(sessionId, runtimeToken, method, payload) { result ->
                    main.post { reply(web, runtimeToken, requestId, result) }
                }
            }
        }
    }

    @JavascriptInterface
    fun buttons(runtimeToken: String, value: String) {
        val parsed = runCatching {
            AppJson.parseToJsonElement(value) as? JsonArray ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
        main.post { SessionHooks.updateButtons(sessionId, runtimeToken, parsed) }
    }

    @JavascriptInterface
    fun initialized(runtimeToken: String, error: String) {
        main.post {
            if (error.isNotBlank()) Log.w("XujingLive", "脚本 $runtimeToken 加载失败：$error")
        }
    }

    @JavascriptInterface
    fun complete(requestId: String, result: String, error: String) = Unit

    private fun reply(web: WebView, runtimeToken: String, requestId: String, result: String) {
        web.evaluateJavascript(
            "window.__xujingReplyForFrame(${JsonPrimitive(runtimeToken)},${JsonPrimitive(requestId)},$result)",
            null,
        )
    }
}
