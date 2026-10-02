package com.liveticker.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class NewsItem(val title: String, val source: String)

/** Free local headlines from Google News RSS. No API key, no daily limit. */
object NewsFetcher {

    fun fetch(city: String, max: Int = 12): List<NewsItem> {
        val fresh = fetchQuery("\"$city\" when:1d", max)
        return if (fresh.isNotEmpty()) fresh else fetchQuery("\"$city\"", max)
    }

    private fun fetchQuery(query: String, max: Int): List<NewsItem> {
        val url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8") +
            "&hl=en-IN&gl=IN&ceid=IN:en"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) LiveTicker")
        val xml = try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parse(xml, max)
    }

    private fun parse(xml: String, max: Int): List<NewsItem> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val out = ArrayList<NewsItem>()
        val seen = HashSet<String>()
        var inItem = false
        var title = ""
        var source = ""
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && out.size < max) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { inItem = true; title = ""; source = "" }
                    "title" -> if (inItem) title = p.nextText().trim()
                    "source" -> if (inItem) source = p.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && p.name == "item" && inItem) {
                inItem = false
                var t = title
                if (source.isNotEmpty()) t = t.removeSuffix(" - $source")
                if (t.isNotEmpty() && seen.add(t.lowercase())) out.add(NewsItem(t, source))
            }
            event = p.next()
        }
        return out
    }
}
