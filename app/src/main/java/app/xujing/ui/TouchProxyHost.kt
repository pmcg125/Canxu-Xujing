package app.xujing.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Render-only WebView for the live overlay.
 *
 * The WebView itself never consumes touch input: the host installs a transparent proxy view over
 * each script-declared interaction region and forwards the gesture here. Everything outside a
 * proxy falls through to the Compose chat underneath the overlay.
 */
internal class TouchThroughWebView(context: Context) : WebView(context) {
    var forwarding = false

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (forwarding) super.onTouchEvent(event) else false
}

/** A script interaction region in full-screen overlay pixels, relative to the shell's top-left. */
internal data class LiveTouchRect(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Owns one transparent [View] per [LiveTouchRect].
 *
 * Proxies are added after the WebView, so they sit on top of it and win hit-testing. A proxy only
 * consumes gestures that start inside it; the rest of the full-screen overlay stays transparent to
 * input. The touched proxy forwards the whole gesture (down/move/up/cancel) to the WebView after
 * translating coordinates into WebView space.
 */
@SuppressLint("ViewConstructor")
internal class TouchProxyHost(private val web: TouchThroughWebView) {
    private val proxies = mutableListOf<View>()
    private var container: ViewGroup? = null
    private var rects: List<LiveTouchRect> = emptyList()
    private var gestureActive = false
    private var rebuildPending = false

    fun attachTo(parent: ViewGroup) {
        if (container === parent) return
        detach()
        container = parent
        rebuild()
    }

    fun detach() {
        proxies.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        proxies.clear()
        container = null
        gestureActive = false
        rebuildPending = false
    }

    fun update(next: List<LiveTouchRect>) {
        if (same(rects, next)) return
        rects = next
        if (gestureActive) {
            // Rebuilding mid-gesture would remove the proxy currently owning the touch stream.
            // Keep forwarding to the WebView and apply the new geometry when the gesture ends.
            rebuildPending = true
            return
        }
        rebuild()
    }

    fun clear() = update(emptyList())

    private fun same(a: List<LiveTouchRect>, b: List<LiveTouchRect>): Boolean {
        if (a.size != b.size) return false
        for (index in a.indices) {
            val left = a[index]
            val right = b[index]
            if (left.id != right.id) return false
            if (abs(left.x - right.x) > 0.5f || abs(left.y - right.y) > 0.5f ||
                abs(left.width - right.width) > 0.5f || abs(left.height - right.height) > 0.5f
            ) return false
        }
        return true
    }

    private fun rebuild() {
        val parent = container ?: return
        proxies.forEach { parent.removeView(it) }
        proxies.clear()
        for (rect in rects) {
            if (rect.width < 1f || rect.height < 1f) continue
            val width = rect.width.roundToInt().coerceAtLeast(1)
            val height = rect.height.roundToInt().coerceAtLeast(1)
            val proxy = View(parent.context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnTouchListener { view, event -> forward(view, event) }
            }
            val params = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = rect.x.roundToInt().coerceAtLeast(0)
                topMargin = rect.y.roundToInt().coerceAtLeast(0)
            }
            // Later rects are added later and therefore sit above earlier ones (last wins).
            parent.addView(proxy, params)
            proxies += proxy
        }
    }

    private fun forward(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            gestureActive = true
            web.forwarding = true
        }
        val copy = MotionEvent.obtain(event)
        val proxyLocation = IntArray(2)
        val webLocation = IntArray(2)
        view.getLocationOnScreen(proxyLocation)
        web.getLocationOnScreen(webLocation)
        copy.offsetLocation(
            (proxyLocation[0] - webLocation[0]).toFloat(),
            (proxyLocation[1] - webLocation[1]).toFloat(),
        )
        try {
            web.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                web.forwarding = false
                gestureActive = false
                if (rebuildPending) {
                    rebuildPending = false
                    web.post { rebuild() }
                }
            }
        }
        return true
    }
}
