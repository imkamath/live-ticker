package com.liveticker.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val STYLE_TOP = "top"
    const val STYLE_FLOATING = "floating"
    const val STYLE_BOTTOM = "bottom"

    const val KEY_SYMBOLS = "symbols"
    const val KEY_API_KEY = "cricket_api_key"
    const val KEY_MATCHES = "matches"          // set of "id<TAB>name"
    const val KEY_STYLE = "style"
    const val KEY_TEXT_SIZE = "text_size"
    const val KEY_SPEED = "speed"
    const val KEY_STOCK_SEC = "stock_sec"
    const val KEY_CRICKET_SEC = "cricket_sec"

    const val DEFAULT_SYMBOLS = "^NSEI, ^BSESN, RELIANCE.NS, TCS.NS, AAPL, ^GSPC"

    fun of(c: Context): SharedPreferences =
        c.getSharedPreferences("ticker_prefs", Context.MODE_PRIVATE)

    fun symbols(c: Context): List<String> =
        (of(c).getString(KEY_SYMBOLS, DEFAULT_SYMBOLS) ?: "")
            .split(',', '\n', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun apiKey(c: Context): String = (of(c).getString(KEY_API_KEY, "") ?: "").trim()

    fun matchEntries(c: Context): Map<String, String> =
        (of(c).getStringSet(KEY_MATCHES, emptySet()) ?: emptySet())
            .mapNotNull {
                val p = it.split('\t', limit = 2)
                if (p.size == 2) p[0] to p[1] else null
            }.toMap()

    fun style(c: Context): String = of(c).getString(KEY_STYLE, STYLE_TOP) ?: STYLE_TOP
    fun textSize(c: Context): Int = of(c).getInt(KEY_TEXT_SIZE, 14)
    fun speed(c: Context): Int = of(c).getInt(KEY_SPEED, 60)
    fun stockSec(c: Context): Int = of(c).getInt(KEY_STOCK_SEC, 30).coerceAtLeast(10)
    fun cricketSec(c: Context): Int = of(c).getInt(KEY_CRICKET_SEC, 120).coerceAtLeast(30)
}
