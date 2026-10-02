package com.liveticker.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

data class Quote(
    val symbol: String,
    val label: String,
    val price: Double,
    val changePct: Double,
    val currency: String
) {
    fun priceText(): String {
        val locale = if (currency == "INR") Locale("en", "IN") else Locale.US
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val prefix = if (symbol.startsWith("^")) "" else when (currency) {
            "INR" -> "₹"
            "USD" -> "$"
            "" -> ""
            else -> "$currency "
        }
        return prefix + nf.format(price)
    }
}

object DataFetcher {

    private val INDEX_NAMES = mapOf(
        "^NSEI" to "NIFTY 50", "^BSESN" to "SENSEX", "^NSEBANK" to "BANK NIFTY",
        "^CNXIT" to "NIFTY IT", "^GSPC" to "S&P 500", "^IXIC" to "NASDAQ",
        "^DJI" to "DOW", "^NDX" to "NASDAQ 100"
    )

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        )
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Stock / index price from Yahoo Finance (works for NSE, BSE, US). Returns null on failure. */
    fun fetchQuote(symbol: String): Quote? = try {
        val body = get(
            "https://query1.finance.yahoo.com/v8/finance/chart/" +
                URLEncoder.encode(symbol, "UTF-8") + "?interval=1d&range=1d"
        )
        val meta = JSONObject(body).getJSONObject("chart")
            .getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        var prev = meta.optDouble("previousClose")
        if (prev.isNaN() || prev == 0.0) prev = meta.optDouble("chartPreviousClose")
        val pct = if (prev.isNaN() || prev == 0.0) 0.0 else (price - prev) / prev * 100.0
        val label = INDEX_NAMES[symbol]
            ?: symbol.removePrefix("^").removeSuffix(".NS").removeSuffix(".BO")
        Quote(symbol, label, price, pct, meta.optString("currency", ""))
    } catch (e: Exception) {
        null
    }
}
