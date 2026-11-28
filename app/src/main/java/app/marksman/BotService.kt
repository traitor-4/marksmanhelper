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
        setLabel((if (mode == 2) "AUTO\n" else "BOT\n") + pct + (if (mode == 2) (if (minion) "\nMIN" else "\nno min") else ""), 0xFF4CAF50.toInt())
        lastOwnTap = now
        drive(true, mv)
    }

    /** Minion bar colours: enemy = red, ally = green. */
    private fun barCol(b: ByteBuffer, s: Int, x: Int, y: Int, ally: Boolean): Boolean {
        val p = px(b, s, x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        return if (ally) g > 140 && r < 130 && bl < 110 && g - r > 50
        else r > 150 && g in 16..74 && bl in 11..69 && r - g > 100
    }
    private fun blk(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) < 14 && ((p shr 8) and 255) < 14 && (p and 255) < 14
    }

    /** Small minion HP bars (coloured fill + black empty part). Adds (centreX, y) to out. */
    private fun scanBars(b: ByteBuffer, s: Int, ally: Boolean, out: ArrayList<FloatArray>) {
        val sc = H / 1080f
        val lo = (50 * sc).toInt(); val hi = (66 * sc).toInt()
        val step = max(1, (2 * sc).toInt())
        var y = (H * 0.09f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - hi - 2) {
                val skip = (x < 480 * sc && y < 380 * sc) || (x > W - 528 * sc && y > 486 * sc)
                if (!skip && barCol(b, s, x, y, ally) && !barCol(b, s, x - 1, y, ally)) {
                    var last = x; var k = x + 1
                    while (k < x + hi && k - last <= 6) { if (barCol(b, s, k, y, ally)) last = k; k++ }
                    val rr = last - x + 1
                    var t = last + 1
                    while (t < last + 6 && !blk(b, s, t, y)) t++
                    val t0 = t
                    var tl = 0
                    while (t < x + hi && blk(b, s, t, y)) { t++; tl++ }
                    val tot = (t0 - last - 1) + rr + tl
                    if (rr >= 3 && tot >= lo && tot <= hi && (tl >= 3 || rr >= lo)) {
                        var u = y; while (u > 0 && barCol(b, s, x + 1, u - 1, ally)) u--
                        var v = y; while (v < H - 1 && barCol(b, s, x + 1, v + 1, ally)) v++
                        val fh = v - u + 1
                        if (fh >= 2 * sc && fh <= 10 * sc) {
                            out.add(floatArrayOf(x + tot / 2f, y.toFloat()))
                            x += tot
                        }
                    }
                }
                x++
            }
            y += step
        }
        scanTracks(b, s, ally, out)
    }

    /** Nearly empty minion bars: a pure-black strip right next to a little red/green fill. */
    private fun scanTracks(b: ByteBuffer, s: Int, ally: Boolean, out: ArrayList<FloatArray>) {
        val sc = H / 1080f
        val tot = (61 * sc).toInt()
        val step = max(1, (2 * sc).toInt())
        var y = (H * 0.09f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - 2) {
                if (blk(b, s, x, y) && !blk(b, s, x - 1, y)) {
                    var e = x
                    while (e < W - 1 && blk(b, s, e, y)) e++
                    val len = e - x
                    val skip = (x < 480 * sc && y < 380 * sc) || (x > W - 528 * sc && y > 486 * sc)
                    if (!skip && len >= 12 * sc && len <= 64 * sc) {
                        var left = false
                        for (xx in max(0, x - 6) until x) if (fillLoose(b, s, xx, y, ally)) left = true
                        if (left) {
                            val xm = (x + e) / 2
                            var u = y; while (u > 0 && blk(b, s, xm, u - 1)) u--
                            var v = y; while (v < H - 1 && blk(b, s, xm, v + 1)) v++
                            val h = v - u + 1
                            if (h >= 3 * sc && h <= 10 * sc) out.add(floatArrayOf((e - tot / 2).toFloat(), y.toFloat()))
                        }
                    }
                    x = e
                }
                x++
            }
            y += step
        }
    }

    private fun fillLoose(b: ByteBuffer, s: Int, x: Int, y: Int, ally: Boolean): Boolean {
        val p = px(b, s, x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        return if (ally) g > 100 && g - r > 30 else r > 90 && r - g > 50 && r - bl > 50
    }


    /** Is the green-ringed hero icon on the minimap standing on a lane? 1 lane, 0 jungle, -1 unknown. */
    private fun heroOnLane(b: ByteBuffer, s: Int): Int {
        val mx = (0.120f * H).toInt(); val my = (0.0056f * H).toInt(); val ms = (0.311f * H).toInt()
        val cell = max(2, (6 * H / 1080f).toInt())
        val n = ms / cell + 1
        val cnt = IntArray(n * n)
        fun green(x: Int, y: Int): Boolean {
            val p = px(b, s, x, y); val r = (p shr 16) and 255; val g = (p shr 8) and 255
            return g > 150 && r < 130 && (p and 255) < 110 && g - r > 60
        }
        for (y in my until my + ms) for (x in mx until mx + ms)
            if (green(x, y)) cnt[((y - my) / cell) * n + (x - mx) / cell]++
        var best = 0; var bi = -1
        for (cy in 1 until n - 1) for (cx in 1 until n - 1) {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += cnt[(cy + dy) * n + cx + dx]
            if (sum > best) { best = sum; bi = cy * n + cx }
        }
        if (bi < 0 || best < 25) return -1
        val wx = mx + (bi % n) * cell + cell / 2
        val wy = my + (bi / n) * cell + cell / 2
        val rad = (24 * H / 1080f).toInt()
        var sx = 0L; var sy = 0L; var c = 0
        for (y in max(my, wy - rad)..min(my + ms - 1, wy + rad))
            for (x in max(mx, wx - rad)..min(mx + ms - 1, wx + rad))
                if (green(x, y)) { sx += x; sy += y; c++ }
        if (c < 25) return -1
        val u = (sx / c - mx) / ms.toFloat()
        val v = (sy / c - my) / ms.toFloat()
        val lane = u < 0.12f || u > 0.88f || v < 0.12f || v > 0.88f || abs(u + v - 1f) < 0.08f
        return if (lane) 1 else 0
    }

    /**
     * Nearest enemy minion to walk to, only when it is lane traffic: either the minimap says your
     * hero is on a lane, or ally minion bars are right next to the enemy (a wave fight).
     * A jungle monster in the jungle has neither. Sets minX/minY.
     */
    private fun findMinion(b: ByteBuffer, s: Int): Boolean {
        val enemies = ArrayList<FloatArray>()
        scanBars(b, s, false, enemies)
        if (enemies.isEmpty()) return false
        val inLane = heroOnLane(b, s) == 1
        val allies = ArrayList<FloatArray>()
        if (!inLane) scanBars(b, s, true, allies)
        val sc = H / 1080f
        var bestD = Float.MAX_VALUE
        var found = false
        for (e in enemies) {
            val ok = inLane || allies.any { hypot(it[0] - e[0], it[1] - e[1]) < 350 * sc }
            if (!ok) continue
            val d = hypot(e[0] - W * 0.5f, e[1] + 45 * sc - H * 0.52f)
            if (d < bestD) { bestD = d; minX = e[0]; minY = e[1]; found = true }
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
            // the bot's own gestures also look like "outside touches", so only count touches
            // that arrive while the bot has been idle for a moment
            val t = SystemClock.uptimeMillis()
            if (e.action == MotionEvent.ACTION_OUTSIDE && t - TapService.lastGesture > 600)
                pausedUntil = t + 1000
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
