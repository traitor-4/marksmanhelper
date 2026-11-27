package app.marksman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class BotService : Service() {
    private val STOP = 0.20f            // stop attacking at or below this HP fraction
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var watch: View
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var W = 0
    private var H = 0
    @Volatile private var mode = 0                 // 0 off, 1 attack only, 2 attack + auto-move
    private val active get() = mode > 0
    @Volatile private var pausedUntil = 0L
    @Volatile private var lastOwnTap = 0L
    private var lastLabel = ""
    private val ht = HandlerThread("bot").apply { start() }
    private val h = Handler(ht.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("p", MODE_PRIVATE) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val code = i?.getIntExtra("code", 0) ?: 0
        val data = i?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bot", "Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot").setContentTitle("Marksman Helper running")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        W = m.widthPixels; H = m.heightPixels

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data)
        proj!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        proj!!.createVirtualDisplay("cap", W, H, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)

        addBubble()
        addTouchWatcher()
        h.post(tick)
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Exception) { }
            h.postDelayed(this, 110)
        }
    }

    private var holdUntil = 0L
    private var holding = false
    @Volatile private var targetX = -1f

    private val RANGE = 330f        // screen px (at 1080 height) at which the hero starts attacking minions
    @Volatile private var minX = 0f
    @Volatile private var minY = 0f

    private fun drive(attack: Boolean, mv: FloatArray?) {
        TapService.ax = prefs.getFloat("ax", W * 0.833f); TapService.ay = prefs.getFloat("ay", H * 0.856f)
        TapService.jx = prefs.getFloat("jx", W * 0.18f); TapService.jy = prefs.getFloat("jy", H * 0.74f)
        TapService.wantAttack = attack
        if (mv != null) { TapService.sx = mv[0]; TapService.sy = mv[1]; TapService.stickOn = true }
        else TapService.stickOn = false
        TapService.kick()
    }

    private fun step() {
        if (!active) { setLabel("OFF", Color.GRAY); drive(false, null); return }
        val now = SystemClock.uptimeMillis()
        if (now < pausedUntil) { setLabel("YOU", 0xFF2196F3.toInt()); drive(false, null); return }
        val img = reader?.acquireLatestImage() ?: return
        var ratio: Float? = null
        var blank = false
        var minion = false
        try {
            val pl = img.planes[0]
            blank = isBlank(pl.buffer, pl.rowStride)
            if (!blank) {
                ratio = findTarget(pl.buffer, pl.rowStride)
                if (mode == 2 && ratio == null) minion = findMinion(pl.buffer, pl.rowStride)
            }
        } finally { img.close() }
        if (blank) { setLabel("BLACK\nframe", Color.RED); drive(false, null); return }   // game blocks screen capture
        val pct = if (ratio != null) (ratio * 100).toInt().toString() + "%" else "--"
        val low = ratio != null && ratio <= STOP
        if (low) holdUntil = now + 500
        if (low || (ratio == null && now < holdUntil)) {
            setLabel("HOLD\n" + pct, 0xFFFF9800.toInt())
            drive(false, null)
            if (low && !holding) { holding = true; lastOwnTap = now; nudge() }   // joystick nudge cancels the attack
            return
        }
        holding = false
        // auto-move: walk toward the nearest enemy minion until it is in range
        var mv: FloatArray? = null
        if (minion) {
            val sc = H / 1080f
            val dx = minX - W * 0.5f
            val dy = minY + 45 * sc - H * 0.52f
            val d = hypot(dx, dy)
            if (d > RANGE * sc) {
                val r = H * 0.08f
                mv = floatArrayOf(TapService.jx + dx / d * r, TapService.jy + dy / d * r)
            }
        }
        setLabel((if (mode == 2) "AUTO\n" else "BOT\n") + pct, 0xFF4CAF50.toInt())
        lastOwnTap = now
        drive(true, mv)
    }

    private fun mred(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        return r > 150 && g in 16..74 && bl in 11..69 && r - g > 100
    }
    private fun blk(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) < 14 && ((p shr 8) and 255) < 14 && (p and 255) < 14
    }

    /** Nearest enemy minion HP bar (small red bar with black empty part). Sets minX/minY. */
    private fun findMinion(b: ByteBuffer, s: Int): Boolean {
        val sc = H / 1080f
        val lo = (50 * sc).toInt(); val hi = (66 * sc).toInt()
        val step = max(1, (2 * sc).toInt())
        var bestD = Float.MAX_VALUE
        var found = false
        var y = (H * 0.13f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - hi - 2) {
                val skip = (x < 480 * sc && y < 380 * sc) || (x > W - 528 * sc && y > 486 * sc)
                if (!skip && mred(b, s, x, y) && !mred(b, s, x - 1, y)) {
                    var last = x; var k = x + 1
                    while (k < x + hi && k - last <= 6) { if (mred(b, s, k, y)) last = k; k++ }
                    val rr = last - x + 1
                    var t = last + 1
                    while (t < last + 6 && !blk(b, s, t, y)) t++
                    val t0 = t
                    var tl = 0
                    while (t < x + hi && blk(b, s, t, y)) { t++; tl++ }
                    val tot = (t0 - last - 1) + rr + tl
                    if (rr >= 3 && tot >= lo && tot <= hi && (tl >= 3 || rr >= lo)) {
                        var u = y; while (u > 0 && mred(b, s, x + 1, u - 1)) u--
                        var v = y; while (v < H - 1 && mred(b, s, x + 1, v + 1)) v++
                        val fh = v - u + 1
                        if (fh >= 3 * sc && fh <= 10 * sc) {
                            val cx = x + tot / 2f
                            val d = hypot(cx - W * 0.5f, y + 45 * sc - H * 0.52f)
                            if (d < bestD) { bestD = d; minX = cx; minY = y.toFloat(); found = true }
                            x += tot
                        }
                    }
                }
                x++
            }
            y += step
        }
        return found
    }

    private fun nudge() {
        val jx = prefs.getFloat("jx", W * 0.18f)
        val jy = prefs.getFloat("jy", H * 0.74f)
        val dir = if (targetX > W * 0.5f) -1f else 1f      // step away from the enemy
        TapService.swipe(jx, jy, jx + dir * H * 0.05f, jy, 180)
    }

    private fun isBlank(b: ByteBuffer, s: Int): Boolean {
        for (i in 1..60) if (px(b, s, W * i / 61, H * i / 61) != 0) return false
        return true
    }

    // ---------- HP bar detection (tuned on 2400x1080 screenshots) ----------
    private fun px(b: ByteBuffer, s: Int, x: Int, y: Int): Int {
        val i = y * s + x * 4
        return ((b.get(i).toInt() and 255) shl 16) or ((b.get(i + 1).toInt() and 255) shl 8) or (b.get(i + 2).toInt() and 255)
    }
    private fun red(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) > 120 && ((p shr 8) and 255) < 80 && (p and 255) < 80
    }
    private fun yellow(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); val r = (p shr 16) and 255
        return r > 110 && ((p shr 8) and 255) > 100 && (p and 255) < 90 && r - (p and 255) > 60
    }
    private fun dark(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) < 70 && ((p shr 8) and 255) < 70 && (p and 255) < 80
    }

    private class Cand(val ratio: Float, val outlined: Boolean, val dist: Float, val cx: Float)

    /** HP fraction of the targeted enemy hero bar, or null if none found. */
    private fun findTarget(b: ByteBuffer, s: Int): Float? {
        val sc = H / 1080f                       // game HUD scales with screen height
        val barW = (186 * sc).toInt()
        val step = max(1, (3 * sc).toInt())
        val list = ArrayList<Cand>()
        var y = (H * 0.13f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - barW - 2) {
                val skip = (x < 480 * sc && y < 380 * sc) || (x > W - 528 * sc && y > 486 * sc)
                if (!skip && red(b, s, x, y) && !red(b, s, x - 1, y)) {
                    var last = x
                    var k = x + 1
                    while (k < x + barW && k - last <= 8) { if (red(b, s, k, y)) last = k; k++ }
                    val end = x + barW
                    var dk = 0; var tot = 0; var d = last + 1
                    while (d < end) { if (dark(b, s, d, y)) dk++; tot++; d += 2 }
                    if (last - x >= 2 && tot > 4 && dk >= 0.8f * tot) {
                        val col = end - (6 * sc).toInt()
                        var top = y; while (top > 0 && dark(b, s, col, top - 1)) top--
                        var bot = y; while (bot < H - 1 && dark(b, s, col, bot + 1)) bot++
                        val th = bot - top + 1
                        var u = y; while (u > 0 && red(b, s, x + 1, u - 1)) u--
                        var v = y; while (v < H - 1 && red(b, s, x + 1, v + 1)) v++
                        val fh = v - u + 1       // red outline edges are taller than the bar track
                        if (th >= 10 * sc && th <= 40 * sc && fh <= th) {
                            var out = false
                            for (o in 1..(8 * sc).toInt()) {
                                val yy = top - o
                                if (yy >= 0 && (red(b, s, col, yy) || yellow(b, s, col, yy))) out = true
                            }
                            val ratio = min(1f, (last - x + 1) / barW.toFloat())
                            list.add(Cand(ratio, out, hypot(x + barW / 2f - W * 0.5f, y - H * 0.52f), x + barW / 2f))
                            x = end
                        }
                    }
                }
                x++
            }
            y += step
        }
        if (list.isEmpty()) return if (emptyBar(b, s, sc, barW, step)) 0f else null
        val pool = list.filter { it.outlined }.ifEmpty { list }   // outlined bar = your target
        val best = pool.minByOrNull { it.dist }
        targetX = best?.cx ?: -1f
        return best?.ratio
    }

    /** Outlined bar with no red fill left (HP under ~2%): counts as 0%. */
    private fun emptyBar(b: ByteBuffer, s: Int, sc: Float, barW: Int, step: Int): Boolean {
        var y = (H * 0.13f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - 2) {
                if (dark(b, s, x, y) && !dark(b, s, x - 1, y)) {
                    var e = x
                    while (e < W - 1 && dark(b, s, e, y)) e++
                    val skip = (x < 480 * sc && y < 380 * sc) || (e > W - 528 * sc && y > 486 * sc)
                    if (!skip && e - x >= 0.85f * barW) {
                        val col = e - barW / 2
                        var top = y; while (top > 0 && dark(b, s, col, top - 1)) top--
                        var bot = y; while (bot < H - 1 && dark(b, s, col, bot + 1)) bot++
                        val th = bot - top + 1
                        if (th >= 10 * sc && th <= 40 * sc) {
                            for (o in 1..(8 * sc).toInt()) {
                                val yy = top - o
                                if (yy >= 0 && (red(b, s, col, yy) || yellow(b, s, col, yy))) { targetX = col.toFloat(); return true }
                            }
                        }
                    }
                    x = e
                }
                x++
            }
            y += step
        }
        return false
    }

    // ---------- bubble, calibration, touch watcher ----------
    private fun setLabel(t: String, c: Int) {
        if (t == lastLabel) return
        lastLabel = t
        ui.post { bubble.text = t; (bubble.background as GradientDrawable).setColor(c) }
    }

    private fun ovParams(w: Int, hh: Int, flags: Int) = WindowManager.LayoutParams(
        w, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    private fun addBubble() {
        bubble = TextView(this).apply {
            text = "OFF"; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        val lp = ovParams(160, 160, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 30; lp.y = 400
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var t0 = 0L; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; t0 = SystemClock.uptimeMillis(); moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 12 || abs(e.rawY - sy) > 12) moved = true
                    if (moved) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(bubble, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (SystemClock.uptimeMillis() - t0 > 600) calibrate() else mode = (mode + 1) % 3
                }
            }
            true
        }
        wm.addView(bubble, lp)
    }

    private fun calibrate(step: Int = 0) {
        mode = 0
        val v = TextView(this).apply {
            text = if (step == 0) "Tap your ATTACK button" else "Tap the CENTER of your JOYSTICK"
            setTextColor(Color.WHITE); textSize = 22f
            gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt())
        }
        val p = ovParams(-1, -1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val kx = if (step == 0) "ax" else "jx"
                val ky = if (step == 0) "ay" else "jy"
                prefs.edit().putFloat(kx, e.rawX).putFloat(ky, e.rawY).apply()
                wm.removeView(v)
                if (step == 0) ui.post { calibrate(1) }
            }
            true
        }
        wm.addView(v, p)
    }

    // pauses the bot for ~1s whenever YOU touch the screen
    private fun addTouchWatcher() {
        watch = View(this)
        watch.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE && SystemClock.uptimeMillis() - lastOwnTap > 300)
                pausedUntil = SystemClock.uptimeMillis() + 1000
            false
        }
        val p = ovParams(1, 1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        p.gravity = Gravity.TOP or Gravity.START
        wm.addView(watch, p)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { wm.removeView(bubble) } catch (e: Exception) { }
        try { wm.removeView(watch) } catch (e: Exception) { }
        reader?.close(); proj?.stop(); ht.quitSafely()
        super.onDestroy()
    }
}
