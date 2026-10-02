package com.liveticker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class TickerService : Service() {

    companion object {
        const val ACTION_STOP = "com.liveticker.app.STOP"
        private const val CHANNEL_ID = "ticker"
        private const val NOTIF_ID = 1
        private const val LONG_PRESS_MS = 600L
        private val UP = Color.parseColor("#6EE07A")
        private val DOWN = Color.parseColor("#FF7B7B")
        private val DIM = Color.parseColor("#BDBDBD")
        private val BG = Color.parseColor("#E6121212")
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    @Volatile private var generation = 0

    private var overlay: View? = null
    private var marquee: MarqueeView? = null
    private var card: TextView? = null
    private var style = Prefs.STYLE_TOP

    private val quoteCache = HashMap<String, Quote>()
    @Volatile private var quotes: List<Quote> = emptyList()
    @Volatile private var matches: List<Match> = emptyList()
    @Volatile private var stockError: String? = null
    @Volatile private var cricketError: String? = null
    @Volatile private var stocksLoaded = false
    @Volatile private var cricketLoaded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        worker = HandlerThread("ticker-net").apply { start() }
        bg = Handler(worker.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live ticker", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startInForeground() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val gen = ++generation
        stocksLoaded = false
        cricketLoaded = false
        showOverlay()
        render()
        bg.post { stockLoop(gen) }
        bg.post { cricketLoop(gen) }
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        bg.removeCallbacksAndMessages(null)
        worker.quitSafely()
        main.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // ---------- data loops (background thread) ----------

    private fun stockLoop(gen: Int) {
        if (gen != generation) return
        val symbols = Prefs.symbols(this)
        val list = ArrayList<Quote>()
        for (s in symbols) {
            if (gen != generation) return
            val q = DataFetcher.fetchQuote(s)
            if (q != null) {
                quoteCache[s] = q
                list.add(q)
            } else {
                quoteCache[s]?.let { list.add(it) }   // keep last known price
            }
        }
        quotes = list
        stockError = if (symbols.isNotEmpty() && list.isEmpty())
            "Stocks: no data, check internet or symbols" else null
        stocksLoaded = true
        main.post { render() }
        if (gen == generation) bg.postDelayed({ stockLoop(gen) }, Prefs.stockSec(this) * 1000L)
    }

    private fun cricketLoop(gen: Int) {
        if (gen != generation) return
        val selected = Prefs.matchEntries(this)
        val key = Prefs.apiKey(this)
        if (selected.isEmpty() || key.isEmpty()) {
            matches = emptyList()
            cricketError = null
        } else {
            try {
                val found = DataFetcher.fetchCurrentMatches(key).filter { it.id in selected.keys }
                matches = found
                cricketError = if (found.isEmpty()) "Selected match isn't live right now" else null
            } catch (e: Exception) {
                cricketError = "Cricket: ${e.message ?: "update failed"}"
            }
        }
        cricketLoaded = true
        main.post { render() }
        if (gen == generation) bg.postDelayed({ cricketLoop(gen) }, Prefs.cricketSec(this) * 1000L)
    }

    // ---------- overlay ----------

    private fun showOverlay() {
        removeOverlay()
        style = Prefs.style(this)
        val textSp = Prefs.textSize(this).toFloat()
        val dm = resources.displayMetrics
        val prefs = Prefs.of(this)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val view: View
        if (style == Prefs.STYLE_FLOATING) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setLineSpacing(0f, 1.15f)
                maxWidth = (dm.widthPixels * 0.75).toInt()
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(BG)
                    cornerRadius = dp(14).toFloat()
                }
            }
            card = tv
            view = tv
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = prefs.getInt("pos_x_floating", dm.widthPixels / 4)
            lp.y = prefs.getInt("pos_y_floating", dp(140))
        } else {
            val mv = MarqueeView(this).apply {
                setTextSizeSp(textSp)
                speedDpPerSec = Prefs.speed(this@TickerService).toFloat()
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(BG)
            }
            marquee = mv
            view = mv
            lp.gravity = (if (style == Prefs.STYLE_BOTTOM) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            lp.x = 0
            lp.y = prefs.getInt("pos_y_$style", 0)
        }

        makeDraggable(view, lp)
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            marquee = null
            card = null
            stopSelf()
        }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        marquee = null
        card = null
    }

    /** Drag to move. Long-press to open settings. */
    private fun makeDraggable(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        if (style == Prefs.STYLE_FLOATING) lp.x = (startX + dx).toInt().coerceAtLeast(0)
                        val ny = if (style == Prefs.STYLE_BOTTOM) startY - dy else startY + dy
                        lp.y = ny.toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.of(this).edit()
                            .putInt("pos_x_$style", lp.x)
                            .putInt("pos_y_$style", lp.y)
                            .apply()
                    } else if (e.eventTime - e.downTime >= LONG_PRESS_MS) {
                        v.performClick()
                        openSettings()
                    }
                }
            }
            true
        }
    }

    private fun openSettings() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {}
    }

    // ---------- text ----------

    private fun render() {
        val floating = style == Prefs.STYLE_FLOATING
        val sb = SpannableStringBuilder()
        val sep = if (floating) "\n" else "      •      "
        fun next() { if (sb.isNotEmpty()) sb.append(sep) }
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        for (m in matches) {
            next()
            val s = sb.length
            sb.append("🏏 ").append(m.scoreText)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, flag)
            if (m.status.isNotBlank()) {
                sb.append(if (floating) "\n      " else "  —  ")
                val st = sb.length
                sb.append(m.status)
                sb.setSpan(ForegroundColorSpan(DIM), st, sb.length, flag)
            }
        }
        cricketError?.let { next(); sb.append("🏏 ").append(it) }

        for (q in quotes) {
            next()
            val s = sb.length
            sb.append(q.label)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, flag)
            sb.append("  ").append(q.priceText())
            val up = q.changePct >= 0
            val cs = sb.length
            sb.append(String.format(Locale.US, "  %s %.2f%%", if (up) "▲" else "▼", abs(q.changePct)))
            sb.setSpan(ForegroundColorSpan(if (up) UP else DOWN), cs, sb.length, flag)
        }
        stockError?.let { next(); sb.append(it) }

        if (sb.isEmpty()) {
            sb.append(
                if (stocksLoaded && cricketLoaded) "Long-press here to add stocks or a match"
                else "Loading live prices and scores…"
            )
        }
        marquee?.setText(sb)
        card?.text = sb
    }

    // ---------- foreground notification ----------

    private fun startInForeground(): Boolean {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TickerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Live Ticker is running")
            .setContentText("Long-press the ticker to open settings")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop
                ).build()
            )
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
