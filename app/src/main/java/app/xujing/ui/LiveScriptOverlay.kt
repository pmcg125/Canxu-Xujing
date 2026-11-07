package app.xujing.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.xujing.core.*
import app.xujing.runtime.CardRuntimeHost
import app.xujing.runtime.SessionHooks
import kotlinx.serialization.json.*

/**
 * Live overlay state (B1).
 *
 * The script still runs in the overlay shell, but the WebView is now a full-screen render surface.
 * Scripts report the regions they want to receive touches in through `XujingLive.touchRects(...)`;
 * the native host covers only those regions with transparent proxies. The rest of the overlay is
 * transparent to input, so scrolling/typing in the Compose chat keeps working.
 *
 * Everything here is a plain Compose observable object; [sessionId]/[visible]/[revision] drive the
 * Composable and the script engine writes the script + snapshot. No changes to UiRuntime.
 */
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
    internal var proxyHost: TouchProxyHost? = null
        private set

    /** Interaction regions last reported by the shell, kept so a late attach can replay them. */
    private var regions: List<LiveTouchRect> = emptyList()

    fun disable(session: String) {
        if (sessionId == session) {
            visible = false
            enabled = false
            clearRegions()
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
        clearRegions()
        revision++
    }

    /** User closed the overlay. The engine keeps running but stops reporting to a dead view. */
    fun close() {
        visible = false
        clearRegions()
    }

    fun dismiss(session: String) {
        if (sessionId == session) {
            visible = false
            clearRegions()
        }
    }

    /** Shell -> host interaction regions. Replaces the whole set on every call. */
    fun touchRects(session: String, json: String) {
        if (sessionId != session || !visible || scriptContent.isBlank()) return
        val next = parseTouchRects(json)
        regions = next
        proxyHost?.update(next)
    }

    private fun clearRegions() {
        if (regions.isEmpty()) {
            proxyHost?.clear()
            return
        }
        regions = emptyList()
        proxyHost?.clear()
    }

    fun updateSnapshot(session: String, data: JsonElement) {
        if (sessionId != session) return
        if (snapshot.hashCode() == data.hashCode()) return
        snapshot = data
        view?.evaluateJavascript("window.__xujingLiveUpdate&&window.__xujingLiveUpdate(${safe(data)})", null)
    }

    internal fun attach(target: WebView, proxies: TouchProxyHost?) {
        view = target
        proxyHost = proxies
        proxies?.update(regions)
    }

    internal fun detach(target: WebView) {
        if (view === target) {
            view = null
            proxyHost = null
        }
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

private fun parseTouchRects(value: String): List<LiveTouchRect> {
    val array = runCatching { AppJson.parseToJsonElement(value) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val x = obj["x"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val y = obj["y"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val width = obj["w"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val height = obj["h"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        if (width < 1.0 || height < 1.0) return@mapNotNull null
        LiveTouchRect(
            id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
        )
    }
}

private const val LIVE_BASE_URL = "https://cards.xujing.invalid/"

/**
 * Hosts the live script in a full-screen transparent WebView added directly to the Activity's
 * `android.R.id.content` root. The WebView renders only; [TouchProxyHost] decides which touches
 * reach it, every other touch falls through to the Compose chat below.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
internal class LiveOverlayHost(
    private val context: Context,
    private val sessionId: String,
    private val main: Handler,
    private val shellSource: String,
) {
    private val root = FrameLayout(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        clipChildren = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        visibility = View.GONE
    }
    private var web: TouchThroughWebView? = null
    private var proxies: TouchProxyHost? = null
    private var loadedKey: String? = null
    private var loadedTheme: String? = null

    /** Adds the overlay above Compose. Returns false when no Activity/content view is reachable. */
    fun attach(): Boolean {
        if (root.parent != null) return true
        val activity = context.findActivity() ?: return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        content.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return true
    }

    fun setVisible(visible: Boolean) {
        root.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            web?.forwarding = false
            proxies?.clear()
        }
    }

    /** Loads/reloads the shell for [key]; theme-only changes are pushed without a reload. */
    fun update(boot: JsonObject, key: String, theme: String) {
        val target = ensureWeb()
        if (loadedKey != key) {
            loadedKey = key
            loadedTheme = theme
            target.loadDataWithBaseURL(
                LIVE_BASE_URL,
                shellDocument(shellSource, boot),
                "text/html",
                "utf-8",
                null,
            )
        } else if (loadedTheme != theme) {
            loadedTheme = theme
            target.evaluateJavascript(
                "window.__xujingLiveTheme&&window.__xujingLiveTheme($theme)",
                null,
            )
        }
    }

    private fun ensureWeb(): TouchThroughWebView {
        web?.let { return it }
        val created = TouchThroughWebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isFocusable = false
            isClickable = false
            isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            addJavascriptInterface(LiveSurfaceBridge(sessionId, main), "XujingLive")
            addJavascriptInterface(LiveScriptBridge(sessionId, main) { this@LiveOverlayHost.web }, "XujingBridge")
            webViewClient = liveClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    Log.w("XujingLive", "console ${message.message()} @${message.lineNumber()}")
                    return true
                }
            }
        }
        root.addView(
            created,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val touch = TouchProxyHost(created)
        touch.attachTo(root)
        web = created
        proxies = touch
        LiveOverlayState.attach(created, touch)
        return created
    }

    /** Removes the overlay from the root and releases the WebView; the host can be re-attached. */
    fun remove() {
        val target = web
        if (target != null) {
            LiveOverlayState.detach(target)
            target.forwarding = false
        }
        proxies?.detach()
        proxies = null
        root.removeAllViews()
        if (target != null) {
            target.stopLoading()
            target.removeJavascriptInterface("XujingLive")
            target.removeJavascriptInterface("XujingBridge")
            target.destroy()
        }
        web = null
        loadedKey = null
        loadedTheme = null
        (root.parent as? ViewGroup)?.removeView(root)
    }
}

/**
 * Mounts the live overlay while the session has a visible live script.
 *
 * A native full-screen [FrameLayout] is added to the Activity root so it sits above Compose. The
 * WebView is render-only; [TouchProxyHost] supplies the interaction regions. The system back key
 * closes the overlay (no floating close button).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiveScriptOverlay(sessionId: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val composeView = LocalView.current
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
    val host = remember(sessionId, context) {
        LiveOverlayHost(context, sessionId, main, shellSource)
    }
    val active = LiveOverlayState.sessionId == sessionId &&
        LiveOverlayState.visible &&
        LiveOverlayState.scriptContent.isNotBlank()

    // Shell/CSS pixels, not device pixels: the WebView uses meta viewport width=device-width.
    // The host converts each touchRects entry to device pixels when creating FrameLayout proxies.
    val configuredWidth = configuration.screenWidthDp.toFloat()
    val configuredHeight = configuration.screenHeightDp.toFloat()
    val screenWidth = if (composeView.width > 0) composeView.width / density.density else configuredWidth
    val screenHeight = if (composeView.height > 0) composeView.height / density.density else configuredHeight

    DisposableEffect(sessionId, active) {
        if (active) {
            host.attach()
            host.setVisible(true)
            onDispose {
                host.setVisible(false)
                host.remove()
            }
        } else {
            host.remove()
            onDispose { }
        }
    }

    if (active) {
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
        val bootKey = LiveOverlayState.revision.toString() + ":" +
            screenWidth.toInt() + "x" + screenHeight.toInt() + ":" + dark
        val themeJson = theme.toString()
        LaunchedEffect(bootKey, themeJson) {
            host.update(boot, bootKey, themeJson)
        }
    }

    BackHandler(enabled = active) { LiveOverlayState.close() }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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

/** Shell -> host interaction/close bridge (`XujingLive`). */
internal class LiveSurfaceBridge(private val sessionId: String, private val main: Handler) {
    @JavascriptInterface
    fun touchRects(value: String) {
        main.post { LiveOverlayState.touchRects(sessionId, value) }
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
