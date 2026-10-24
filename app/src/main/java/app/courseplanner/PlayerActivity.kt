package app.courseplanner

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** پلیر داخلی: ویدیو را پخش می‌کند و هر چند ثانیه «زمان توقف» را ذخیره می‌کند. */
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var cid = ""
    private var tail = ""
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            savePosition(false)
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val uriStr = intent.getStringExtra("uri")
        val c = intent.getStringExtra("cid")
        val t = intent.getStringExtra("tail")
        if (uriStr == null || c == null || t == null) {
            finish()
            return
        }
        cid = c
        tail = t
        title = intent.getStringExtra("title") ?: ""
        val startMs = intent.getLongExtra("start", 0L)

        val view = PlayerView(this)
        view.setBackgroundColor(Color.BLACK)
        setContentView(view)

        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        p.setMediaItem(MediaItem.fromUri(Uri.parse(uriStr)))
        if (startMs > 0) p.seekTo(startMs)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) savePosition(true)
            }
        })
        p.prepare()
        p.playWhenReady = true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun savePosition(atEnd: Boolean) {
        val p = player ?: return
        if (cid.isEmpty()) return
        val durMs = p.duration
        val dur: Double? = if (durMs > 0) durMs / 1000.0 else null
        val pos = if (atEnd && dur != null) dur else p.currentPosition / 1000.0
        if (!atEnd && pos < 2.0) return        // ثانیه‌های اول ثبت نمی‌شود
        Store.savePosition(cid, tail, pos, dur)
    }

    override fun onResume() {
        super.onResume()
        if (cid.isNotEmpty()) Store.active.add(cid + "|" + tail)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 4000)
    }

    override fun onPause() {
        // قبل از این‌که صفحه‌ی اصلی دوباره دیده شود، موقعیت ذخیره و «در حال پخش» پاک می‌شود
        Store.active.remove(cid + "|" + tail)
        handler.removeCallbacks(ticker)
        savePosition(false)
        super.onPause()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
