package app.xujing.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Floating host for panels requested by card/preset scripts.
 *
 * Scripts never touch the app DOM: they send HTML through `setScriptPanel`, and this overlay owns
 * the WebView. The panel can close itself through `XujingPanel.close()`; nothing else is bridged.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BoxScope.ScriptPanelOverlay(panel: ScriptPanel, onClose: () -> Unit) {
    var offset by remember(panel.sessionId, panel.sourceId) { mutableStateOf(Offset.Zero) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val anchor = if (panel.position == "top-end") Alignment.TopEnd else Alignment.BottomEnd
    // The panel WebView is transparent over the Compose Card. Use the host surface brightness so
    // author markup without explicit colors stays readable in both light and dark themes.
    val panelDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Card(
        modifier = Modifier
            .align(anchor)
            .padding(12.dp)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(panel.width.dp)
            .height(panel.height.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(panel.sessionId, panel.sourceId) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset += drag
                        }
                    }
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    panel.title.ifBlank { "脚本面板" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, "关闭面板", modifier = Modifier.size(18.dp))
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        addJavascriptInterface(object {
                            @JavascriptInterface fun close() { main.post(onClose) }
                        }, "XujingPanel")
                    }
                },
                update = { view ->
                    val key = panel.html + "|" + panelDark
                    if (view.tag != key) {
                        view.tag = key
                        view.loadDataWithBaseURL(null, panelDocument(panel.html, panelDark), "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}

/**
 * Small floating close affordance shared by script hosts (static panels and the live overlay).
 * Kept separate from the panel header so both hosts can place it independently.
 */
@Composable
internal fun ScriptCloseChip(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(24.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                "关闭脚本界面",
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Keep author markup intact; add only the viewport, palette and a static layout for forwarded fixed panels. */
private fun panelDocument(html: String, dark: Boolean): String {
    if (html.contains("<html", ignoreCase = true)) return html
    val foreground = if (dark) "#e6e1e5" else "#1c1b1f"
    val background = if (dark) "#1c1b1f" else "#fffbfe"
    val compat = """
        <style>
        :root{color-scheme:${if (dark) "dark" else "light"};--SmartThemeBodyColor:$foreground;--SmartThemeEmColor:#cac4d0;--SmartThemeQuoteColor:#8ab4f8;--SmartThemeBlurTintColor:$background;--SmartThemeBorderColor:#49454f;--SmartThemeFontSize:13px}
        html,body{margin:0;padding:0;background:$background;color:$foreground;font:13px/1.5 -apple-system,"Noto Sans SC",sans-serif;overflow:auto}
        /* parent.document panels often use position:fixed/bottom offsets that would land outside this small host. */
        body{display:flex;align-items:stretch}
        body>*{position:static!important;inset:auto!important;left:auto!important;right:auto!important;top:auto!important;bottom:auto!important;width:100%!important;max-width:100%!important;height:auto!important;min-height:100%!important;max-height:none!important;margin:0!important}
        *{box-sizing:border-box;max-width:100%}
        </style>
    """.trimIndent()
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">$compat</head><body>$html</body></html>"
}
