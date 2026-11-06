package app.xujing.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
                    if (view.tag != panel.html) {
                        view.tag = panel.html
                        view.loadDataWithBaseURL(null, panelDocument(panel.html), "text/html", "utf-8", null)
                    }
                },
            )
        }
    }
}

/** Keep author markup intact; add only the viewport and the small ST variable palette. */
private fun panelDocument(html: String): String {
    if (html.contains("<html", ignoreCase = true)) return html
    val compat = """
        <style>
        :root{--SmartThemeBodyColor:#e6e1e5;--SmartThemeEmColor:#cac4d0;--SmartThemeQuoteColor:#8ab4f8;--SmartThemeBlurTintColor:#1c1b1f;--SmartThemeBorderColor:#49454f;--SmartThemeFontSize:13px}
        html,body{margin:0;padding:0;background:transparent;color:#e6e1e5;font:13px/1.5 -apple-system,"Noto Sans SC",sans-serif;overflow:auto}
        *{box-sizing:border-box;max-width:100%}
        </style>
    """.trimIndent()
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">$compat</head><body>$html</body></html>"
}
