package com.liveticker.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val STYLE_TOP = "top"
    const val STYLE_FLOATING = "floating"
    const val STYLE_BOTTOM = "bottom"

    const val KEY_SYMBOLS = "symbols"
    const val KEY_STYLE = "style"
    const val KEY_TEXT_SIZE = "text_size"
    const val KEY_SPEED = "speed"
    const val KEY_STOCK_SEC = "stock_sec"
    const val KEY_SPORTS = "sports"
    const val KEY_TEAMS = "teams"
    const val KEY_SPORTS_SEC = "sports_sec"
    const val KEY_NEWS_ON = "news_on"
    const val KEY_NEWS_CITY = "news_city"
    const val KEY_NEWS_MIN = "news_min"

    const val DEFAULT_SYMBOLS = "^NSEI, ^BSESN, RELIANCE.NS, TCS.NS, AAPL, ^GSPC"

    fun of(c: Context): SharedPreferences =
        c.getSharedPreferences("ticker_prefs", Context.MODE_PRIVATE)

    fun symbols(c: Context): List<String> =
        (of(c).getString(KEY_SYMBOLS, DEFAULT_SYMBOLS) ?: "")
            .split(',', '\n', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun style(c: Context): String = of(c).getString(KEY_STYLE, STYLE_TOP) ?: STYLE_TOP
    fun textSize(c: Context): Int = of(c).getInt(KEY_TEXT_SIZE, 14)
    fun speed(c: Context): Int = of(c).getInt(KEY_SPEED, 60)
    fun stockSec(c: Context): Int = of(c).getInt(KEY_STOCK_SEC, 30).coerceAtLeast(10)
    fun sports(c: Context): Set<String> = of(c).getStringSet(KEY_SPORTS, setOf("cricket")) ?: emptySet()
    fun teams(c: Context): List<String> = (of(c).getString(KEY_TEAMS, "") ?: "")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun sportsSec(c: Context): Int = of(c).getInt(KEY_SPORTS_SEC, 120).coerceAtLeast(30)
    fun newsOn(c: Context): Boolean = of(c).getBoolean(KEY_NEWS_ON, true)
    fun newsCity(c: Context): String = (of(c).getString(KEY_NEWS_CITY, "") ?: "").trim()
    fun newsMin(c: Context): Int = of(c).getInt(KEY_NEWS_MIN, 10).coerceAtLeast(5)
}
