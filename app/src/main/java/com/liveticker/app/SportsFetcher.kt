package com.liveticker.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SportItem(val icon: String, val text: String, val live: Boolean)

/** Free scores from ESPN's public scoreboard feeds. No API key, no daily limit. */
object SportsFetcher {

    class Sport(val key: String, val label: String, val icon: String, val path: String)

    val ALL = listOf(
        Sport("cricket", "Cricket (internationals + IPL)", "🏏", ""),
        Sport("epl", "Football: Premier League", "⚽", "soccer/eng.1"),
        Sport("ucl", "Football: Champions League", "⚽", "soccer/uefa.champions"),
        Sport("laliga", "Football: La Liga", "⚽", "soccer/esp.1"),
        Sport("isl", "Football: Indian Super League", "⚽", "soccer/ind.1"),
        Sport("nba", "Basketball: NBA", "🏀", "basketball/nba"),
        Sport("nfl", "American football: NFL", "🏈", "football/nfl"),
        Sport("mlb", "Baseball: MLB", "⚾", "baseball/mlb"),
        Sport("nhl", "Ice hockey: NHL", "🏒", "hockey/nhl")
    )

    private class Ev(
        val state: String,
        val start: Long,
        val text: String,
        val names: List<String>,
        val major: Boolean
    )

    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR
    private val timeFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)

    fun fetch(keys: Set<String>, teams: List<String>): List<SportItem> {
        val out = ArrayList<SportItem>()
        val chosen = ALL.filter { it.key in keys }
        var failures = 0
        for (s in chosen) {
            val events = try {
                if (s.key == "cricket") cricket() else scoreboard(s.path)
            } catch (e: Exception) {
                failures++
                continue
            }
            for (ev in pick(events, teams, s.key == "cricket")) {
                out.add(SportItem(s.icon, ev.text, ev.state == "in"))
            }
        }
        if (chosen.isNotEmpty() && failures == chosen.size) throw IOException("no connection")
        return out
    }

    private fun pick(all: List<Ev>, teams: List<String>, isCricket: Boolean): List<Ev> {
        val now = System.currentTimeMillis()
        var list = all.filter {
            it.state == "in" ||
                (it.state == "pre" && it.start - now in -3 * HOUR..DAY) ||
                (it.state == "post" && now - it.start in 0..DAY)
        }
        if (teams.isNotEmpty()) {
            list = list.filter { ev ->
                teams.any { t -> ev.names.any { n -> n.equals(t, true) || (t.length > 3 && n.contains(t, true)) } }
            }
        } else if (isCricket) {
            val major = list.filter { it.major }
            list = if (major.isNotEmpty()) major else list.filter { it.state == "in" }.take(3)
        }
        val order = mapOf("in" to 0, "pre" to 1, "post" to 2)
        return list.sortedWith(
            compareBy<Ev> { order[it.state] ?: 3 }.thenBy { if (it.state == "post") -it.start else it.start }
        ).take(6)
    }

    private fun cricket(): List<Ev> {
        val root = JSONObject(get(
            "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&tz=Asia/Calcutta"
        ))
        val out = ArrayList<Ev>()
        val sports = root.optJSONArray("sports") ?: return out
        for (i in 0 until sports.length()) {
            val leagues = sports.optJSONObject(i)?.optJSONArray("leagues") ?: continue
            for (j in 0 until leagues.length()) {
                val lg = leagues.optJSONObject(j) ?: continue
                val lgName = lg.optString("name")
                val bigLeague = lgName.contains("Indian Premier League") || lgName.contains("Women's Premier League")
                val evs = lg.optJSONArray("events") ?: continue
                for (k in 0 until evs.length()) {
                    val e = evs.optJSONObject(k) ?: continue
                    val comps = e.optJSONArray("competitors") ?: continue
                    val names = ArrayList<String>()
                    val parts = ArrayList<String>()
                    var national = false
                    for (c in 0 until comps.length()) {
                        val t = comps.optJSONObject(c) ?: continue
                        val abbr = t.optString("abbreviation")
                        names.add(abbr)
                        names.add(t.optString("displayName"))
                        if (t.optBoolean("isNational")) national = true
                        val score = t.optString("score")
                        parts.add(if (score.isEmpty()) abbr else "$abbr $score")
                    }
                    val fs = e.optJSONObject("fullStatus")
                    val state = fs?.optJSONObject("type")?.optString("state") ?: e.optString("status")
                    val summary = fs?.optString("summary").orEmpty().replace("&amp;", "&")
                    val start = parseTime(e.optString("date"))
                    val tail = when {
                        state == "pre" && start > 0 -> " · " + localTime(start)
                        summary.isNotEmpty() -> " · $summary"
                        else -> ""
                    }
                    out.add(Ev(state, start, parts.joinToString(" v ") + tail, names, national || bigLeague))
                }
            }
        }
        return out
    }

    private fun scoreboard(path: String): List<Ev> {
        val root = JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"))
        val out = ArrayList<Ev>()
        val evs = root.optJSONArray("events") ?: return out
        for (k in 0 until evs.length()) {
            val e = evs.optJSONObject(k) ?: continue
            val type = e.optJSONObject("status")?.optJSONObject("type")
            val state = type?.optString("state").orEmpty()
            val detail = type?.optString("shortDetail").orEmpty()
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val comps = comp.optJSONArray("competitors") ?: continue
            val names = ArrayList<String>()
            val abbrs = ArrayList<String>()
            val scores = ArrayList<String>()
            for (c in 0 until comps.length()) {
                val t = comps.optJSONObject(c) ?: continue
                val team = t.optJSONObject("team")
                val abbr = team?.optString("abbreviation").orEmpty()
                names.add(abbr)
                names.add(team?.optString("displayName").orEmpty())
                names.add(team?.optString("shortDisplayName").orEmpty())
                abbrs.add(abbr)
                scores.add(t.optString("score"))
            }
            if (abbrs.size < 2) continue
            val start = parseTime(e.optString("date"))
            val text = if (state == "pre") {
                abbrs[0] + " v " + abbrs[1] + " · " + (if (start > 0) localTime(start) else detail)
            } else {
                abbrs[0] + " " + scores[0] + " – " + scores[1] + " " + abbrs[1] + " · " + detail
            }
            out.add(Ev(state, start, text, names, true))
        }
        return out
    }

    private fun localTime(ms: Long): String =
        timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun parseTime(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) LiveTicker")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
