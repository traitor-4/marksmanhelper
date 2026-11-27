package app.marksman

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun btn(t: String, f: () -> Unit) {
            l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        l.addView(TextView(this).apply {
            text = "Do 1 and 2 once. Then press 3 and open the game. " +
                "Bubble: tap = bot on/off, long-press = set attack button position, drag = move."
            textSize = 15f
        })
        btn("1. Allow overlay") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
        }
        btn("2. Open accessibility settings (turn on Marksman Helper)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3. Start") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Do step 1 first", Toast.LENGTH_SHORT).show()
            } else {
                val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(m.createScreenCaptureIntent(), 1)
            }
        }
        btn("Stop") { stopService(Intent(this, BotService::class.java)) }
        setContentView(l)
    }

    override fun onActivityResult(rc: Int, res: Int, d: Intent?) {
        if (rc == 1 && res == RESULT_OK && d != null) {
            startForegroundService(
                Intent(this, BotService::class.java).putExtra("code", res).putExtra("data", d)
            )
        }
    }
}
