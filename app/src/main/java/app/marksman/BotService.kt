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
    @Volatile private var active = false
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

    private fun step() {
        if (!active) { setLabel("OFF", Color.GRAY); return }
        if (SystemClock.uptimeMillis() < pausedUntil) { setLabel("YOU", 0xFF2196F3.toInt()); return }
        val img = reader?.acquireLatestImage() ?: return
        var ratio: Float? = null
        try {
            val pl = img.planes[0]
            ratio = findTarget(pl.buffer, pl.rowStride)
        } finally { img.close() }
        if (ratio != null && ratio <= STOP) { setLabel("HOLD", 0xFFFF9800.toInt()); return }
        setLabel("BOT", 0xFF4CAF50.toInt())
        lastOwnTap = SystemClock.uptimeMillis()
        TapService.tap(prefs.getFloat("ax", W * 0.833f), prefs.getFloat("ay", H * 0.856f))
    }

    // ---------- HP bar detection (tuned on 2400x1080 screenshots) ----------
    private fun px(b: ByteBuffer, s: Int, x: Int, y: Int): Int {
        val i = y * s + x * 4
        return ((b.get(i).toInt() and 255) shl 16) or ((b.get(i + 1).toInt() and 255) shl 8) or (b.get(i + 2).toInt() and 255)
    }
    private fun red(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) > 170 && ((p shr 8) and 255) < 80 && (p and 255) < 80
    }
    private fun yellow(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) > 200 && ((p shr 8) and 255) > 170 && (p and 255) < 90
    }
    private fun dark(b: ByteBuffer, s: Int, x: Int, y: Int): Boolean {
        val p = px(b, s, x, y); return ((p shr 16) and 255) < 70 && ((p shr 8) and 255) < 70 && (p and 255) < 80
    }

    private class Cand(val ratio: Float, val outlined: Boolean, val dist: Float)

    /** HP fraction of the targeted enemy hero bar, or null if none found. */
    private fun findTarget(b: ByteBuffer, s: Int): Float? {
        val sc = W / 2400f
        val barW = (186 * sc).toInt()
        val step = max(1, (3 * sc).toInt())
        val list = ArrayList<Cand>()
        var y = (H * 0.13f).toInt()
        val yEnd = (H * 0.95f).toInt()
        while (y < yEnd) {
            var x = 1
            while (x < W - barW - 2) {
                val skip = (x < 0.2f * W && y < 0.35f * H) || (x + barW > 0.78f * W && y > 0.45f * H)
                if (!skip && red(b, s, x, y) && !red(b, s, x - 1, y)) {
                    var last = x
                    var k = x + 1
                    while (k < x + barW && k - last <= 4) { if (red(b, s, k, y)) last = k; k++ }
                    val end = x + barW
                    var dk = 0; var tot = 0; var d = last + 1
                    while (d < end) { if (dark(b, s, d, y)) dk++; tot++; d += 2 }
                    if (last - x >= 2 && tot > 4 && dk >= 0.8f * tot) {
                        val col = end - (6 * sc).toInt()
                        var top = y; while (top > 0 && dark(b, s, col, top - 1)) top--
                        var bot = y; while (bot < H - 1 && dark(b, s, col, bot + 1)) bot++
                        val th = bot - top + 1
                        if (th >= 10 * sc && th <= 40 * sc) {
                            var out = false
                            for (o in 1..(8 * sc).toInt()) {
                                val yy = top - o
                                if (yy >= 0 && (red(b, s, col, yy) || yellow(b, s, col, yy))) out = true
                            }
                            val ratio = min(1f, (last - x + 1) / barW.toFloat())
                            list.add(Cand(ratio, out, hypot(x + barW / 2f - W * 0.5f, y - H * 0.52f)))
                        }
                    }
                    x = end
                }
                x++
            }
            y += step
        }
        if (list.isEmpty()) return null
        val pool = list.filter { it.outlined }.ifEmpty { list }   // outlined bar = your target
        return pool.minByOrNull { it.dist }?.ratio
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
            text = "OFF"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) }
        }
        val lp = ovParams(130, 130, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
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
                    if (SystemClock.uptimeMillis() - t0 > 600) calibrate() else active = !active
                }
            }
            true
        }
        wm.addView(bubble, lp)
    }

    private fun calibrate() {
        active = false
        val v = TextView(this).apply {
            text = "Tap your ATTACK button"; setTextColor(Color.WHITE); textSize = 22f
            gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt())
        }
        val p = ovParams(-1, -1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                prefs.edit().putFloat("ax", e.rawX).putFloat("ay", e.rawY).apply()
                wm.removeView(v)
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
