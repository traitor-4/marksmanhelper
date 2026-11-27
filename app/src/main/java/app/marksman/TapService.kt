package app.marksman

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Android cancels any running gesture when a new one is dispatched, so the attack tap and the
 * joystick drag are always sent together inside ONE gesture, chained back to back.
 */
class TapService : AccessibilityService() {
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Synchronized
    private fun pump() {
        if (busy) return
        val strokes = ArrayList<GestureDescription.StrokeDescription>()
        if (wantAttack) {
            strokes.add(GestureDescription.StrokeDescription(Path().apply { moveTo(ax, ay) }, 0, 40))
        }
        val moving = stickOn
        val tx = sx; val ty = sy
        var stick: GestureDescription.StrokeDescription? = null
        val prev = cur
        if (moving) {
            val p = Path()
            if (prev == null) { p.moveTo(jx, jy) } else { p.moveTo(px, py) }
            p.lineTo(tx, ty)
            stick = if (prev == null) GestureDescription.StrokeDescription(p, 0, 140, true)
                    else prev.continueStroke(p, 0, 140, true)
        } else if (prev != null) {
            val p = Path().apply { moveTo(px, py) }                 // let go of the joystick
            stick = prev.continueStroke(p, 0, 30, false)
        }
        if (stick != null) strokes.add(stick)
        if (strokes.isEmpty()) return
        val b = GestureDescription.Builder()
        for (s in strokes) b.addStroke(s)
        busy = true
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (stick != null) {
                    if (moving) { cur = stick; px = tx; py = ty } else cur = null
                }
                busy = false
                h.postDelayed({ pump() }, if (stick != null && moving) 0L else 70L)
            }
            override fun onCancelled(g: GestureDescription?) { cur = null; busy = false }
        }, null)
        if (!ok) busy = false
    }

    companion object {
        @Volatile var inst: TapService? = null
        @Volatile var wantAttack = false
        @Volatile var stickOn = false
        @Volatile var ax = 0f; @Volatile var ay = 0f      // attack button
        @Volatile var jx = 0f; @Volatile var jy = 0f      // joystick centre
        @Volatile var sx = 0f; @Volatile var sy = 0f      // where to drag the joystick to
        private var cur: GestureDescription.StrokeDescription? = null
        private var px = 0f
        private var py = 0f
        @Volatile private var busy = false
        private val h = Handler(Looper.getMainLooper())

        fun kick() { inst?.pump() }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
            val s = inst ?: return false
            val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
            return s.dispatchGesture(g, null, null)
        }
    }
}
