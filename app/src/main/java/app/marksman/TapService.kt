package app.marksman

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TapService : AccessibilityService() {
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile var inst: TapService? = null
        fun tap(x: Float, y: Float): Boolean {
            val s = inst ?: return false
            val p = Path().apply { moveTo(x, y) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 40)).build()
            return s.dispatchGesture(g, null, null)
        }
    }
}
