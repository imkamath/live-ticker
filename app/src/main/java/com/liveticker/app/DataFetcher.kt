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

data class Match(
    val id: String,
    val name: String,
    val status: String,
    val ended: Boolean,
    val scoreText: String
)

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

    /** All current matches from CricketData.org. Throws with a readable message on failure. */
    fun fetchCurrentMatches(apiKey: String): List<Match> {
        if (apiKey.isBlank()) throw IOException("add your CricketData API key")
        val root = JSONObject(
            get("https://api.cricapi.com/v1/currentMatches?apikey=" +
                URLEncoder.encode(apiKey, "UTF-8") + "&offset=0")
        )
        if (root.optString("status") != "success") {
            throw IOException(root.optString("reason", "cricket API error"))
        }
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Match>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            out.add(parseMatch(m))
        }
        return out
    }

    private fun parseMatch(m: JSONObject): Match {
        val shortNames = HashMap<String, String>()
        m.optJSONArray("teamInfo")?.let { t ->
            for (i in 0 until t.length()) {
                val o = t.optJSONObject(i) ?: continue
                val n = o.optString("name")
                val s = o.optString("shortname")
                if (n.isNotEmpty()) shortNames[n.lowercase()] = if (s.isNotEmpty()) s else n
            }
        }
        val parts = ArrayList<String>()
        m.optJSONArray("score")?.let { s ->
            for (i in 0 until s.length()) {
                val o = s.optJSONObject(i) ?: continue
                val inning = o.optString("inning")
                val team = inning.substringBefore(" Inning").substringBefore(" inning").trim()
                val short = shortNames[team.lowercase()] ?: team
                val overs = o.optDouble("o", 0.0)
                val ov = if (overs % 1.0 == 0.0) overs.toInt().toString() else overs.toString()
                parts.add("$short ${o.optInt("r")}/${o.optInt("w")} ($ov)")
            }
        }
        val name = m.optString("name").replace('\t', ' ')
        val score = if (parts.isNotEmpty()) parts.joinToString("  ·  ") else name.substringBefore(",")
        return Match(m.optString("id"), name, m.optString("status"), m.optBoolean("matchEnded"), score)
    }
}
