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
import android.widget.LinearLayout
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
        private val DIVIDER = Color.parseColor("#33FFFFFF")
        private val NEWS_SRC = Color.parseColor("#9FB4C8")
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var bg: Handler
    @Volatile private var generation = 0

    private var overlay: View? = null
    private var marquee: MarqueeView? = null
    private var card: TextView? = null
    private var newsView: MarqueeView? = null
    private var sportsView: MarqueeView? = null
    private var style = Prefs.STYLE_TOP

    private val quoteCache = HashMap<String, Quote>()
    @Volatile private var quotes: List<Quote> = emptyList()
    @Volatile private var sports: List<SportItem> = emptyList()
    @Volatile private var stockError: String? = null
    @Volatile private var sportsError: String? = null
    @Volatile private var stocksLoaded = false
    @Volatile private var sportsLoaded = false
    @Volatile private var news: List<NewsItem> = emptyList()
    @Volatile private var newsError: String? = null
    @Volatile private var newsLoaded = false

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
        sportsLoaded = false
        newsLoaded = false
        showOverlay()
        render()
        bg.post { stockLoop(gen) }
        bg.post { sportsLoop(gen) }
        bg.post { newsLoop(gen) }
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
        val symbols = if (Prefs.stocksOn(this)) Prefs.symbols(this) else emptyList()
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

    private fun sportsLoop(gen: Int) {
        if (gen != generation) return
        val keys = if (Prefs.sportsOn(this)) Prefs.sports(this) else emptySet()
        if (keys.isEmpty()) {
            sports = emptyList()
            sportsError = null
        } else {
            try {
                sports = SportsFetcher.fetch(keys, Prefs.teams(this))
                sportsError = null
            } catch (e: Exception) {
                if (sports.isEmpty()) sportsError = "Scores: couldn't load, will retry"
            }
        }
        sportsLoaded = true
        main.post { render() }
        if (gen == generation) bg.postDelayed({ sportsLoop(gen) }, Prefs.sportsSec(this) * 1000L)
    }

    private fun newsLoop(gen: Int) {
        if (gen != generation) return
        val city = Prefs.newsCity(this)
        if (Prefs.newsOn(this) && city.isNotEmpty()) {
            try {
                val items = NewsFetcher.fetch(city)
                if (items.isNotEmpty() || news.isEmpty()) news = items
                newsError = null
            } catch (e: Exception) {
                if (news.isEmpty()) newsError = "News: couldn't load, will retry"
            }
        } else {
            news = emptyList()
        }
        newsLoaded = true
        main.post { render() }
        if (gen == generation) bg.postDelayed({ newsLoop(gen) }, Prefs.newsMin(this) * 60_000L)
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

        val showNews = Prefs.newsOn(this) && Prefs.newsCity(this).isNotEmpty()
        val showSports = Prefs.sportsOn(this) && Prefs.sports(this).isNotEmpty()
        val showStocks = Prefs.stocksOn(this)
        val speed = Prefs.speed(this).toFloat()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (style == Prefs.STYLE_FLOATING) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setLineSpacing(0f, 1.15f)
                maxWidth = (dm.widthPixels * 0.75).toInt()
            }
            if (showStocks) {
                card = tv
                box.addView(tv)
            }
            box.setPadding(dp(12), dp(10), dp(12), dp(10))
            box.background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(14).toFloat()
            }
            lp.width = if (showNews || showSports) (dm.widthPixels * 0.75).toInt() + dp(24)
                       else WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = prefs.getInt("pos_x_floating", dm.widthPixels / 8)
            lp.y = prefs.getInt("pos_y_floating", dp(140))
        } else {
            val mv = MarqueeView(this).apply {
                setTextSizeSp(textSp)
                speedDpPerSec = speed
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            if (showStocks) {
                marquee = mv
                box.addView(mv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            box.setBackgroundColor(BG)
            lp.gravity = (if (style == Prefs.STYLE_BOTTOM) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            lp.x = 0
            lp.y = prefs.getInt("pos_y_$style", 0)
        }

        if (showSports) sportsView = addLine(box, textSp, speed)
        if (showNews) newsView = addLine(box, (textSp - 1f).coerceAtLeast(10f), speed)
        if (box.childCount == 0) {
            stopSelf()
            return
        }
        val view: View = box

        makeDraggable(view, lp)
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (e: Exception) {
            marquee = null
            card = null
            newsView = null
            sportsView = null
            stopSelf()
        }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        marquee = null
        card = null
        newsView = null
        sportsView = null
    }

    private fun addLine(box: LinearLayout, textSp: Float, speed: Float): MarqueeView {
        val floating = style == Prefs.STYLE_FLOATING
        val first = box.childCount == 0
        if (!first) {
            box.addView(View(this).apply { setBackgroundColor(DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = if (floating) dp(8) else 0
                })
        }
        val mv = MarqueeView(this).apply {
            setTextSizeSp(textSp)
            speedDpPerSec = speed
            if (floating) setPadding(0, if (first) 0 else dp(6), 0, 0)
            else setPadding(dp(8), if (first) dp(6) else dp(5), dp(8), dp(6))
        }
        box.addView(mv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return mv
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
                if (stocksLoaded) "Long-press here to add stocks"
                else "Loading live prices…"
            )
        }
        marquee?.setText(sb)
        card?.text = sb
        renderSports()
        renderNews()
    }

    private fun renderSports() {
        val sv = sportsView ?: return
        val sb = SpannableStringBuilder()
        if (sports.isEmpty()) {
            sb.append("🏆 ")
            sb.append(sportsError ?: if (sportsLoaded) "No matches today for your sports or teams"
                                     else "Loading scores…")
        } else {
            for ((i, item) in sports.withIndex()) {
                if (i > 0) sb.append("      •      ")
                sb.append(item.icon).append(" ")
                if (item.live) {
                    val st = sb.length
                    sb.append("LIVE ")
                    sb.setSpan(ForegroundColorSpan(DOWN), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                sb.append(item.text)
            }
        }
        sv.setText(sb)
    }

    private fun renderNews() {
        val nv = newsView ?: return
        val sb = SpannableStringBuilder()
        if (news.isEmpty()) {
            sb.append("📰 ")
            sb.append(newsError ?: if (newsLoaded) "No fresh local news for ${Prefs.newsCity(this)} yet"
                                   else "Loading local news…")
        } else {
            for ((i, n) in news.withIndex()) {
                if (i > 0) sb.append("      •      ")
                sb.append("📰 ").append(n.title)
                if (n.source.isNotEmpty()) {
                    sb.append("  ")
                    val s = sb.length
                    sb.append(n.source)
                    sb.setSpan(ForegroundColorSpan(NEWS_SRC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        nv.setText(sb)
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
