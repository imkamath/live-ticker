package com.liveticker.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var etSymbols: EditText
    private lateinit var etStockSec: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etCricketSec: EditText
    private lateinit var matchList: LinearLayout
    private lateinit var tvMatchStatus: TextView
    private lateinit var rgStyle: RadioGroup
    private lateinit var sbTextSize: SeekBar
    private lateinit var tvTextSize: TextView
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView

    private val selected = LinkedHashMap<String, String>()   // match id -> name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSymbols = findViewById(R.id.etSymbols)
        etStockSec = findViewById(R.id.etStockSec)
        etApiKey = findViewById(R.id.etApiKey)
        etCricketSec = findViewById(R.id.etCricketSec)
        matchList = findViewById(R.id.matchList)
        tvMatchStatus = findViewById(R.id.tvMatchStatus)
        rgStyle = findViewById(R.id.rgStyle)
        sbTextSize = findViewById(R.id.sbTextSize)
        tvTextSize = findViewById(R.id.tvTextSize)
        sbSpeed = findViewById(R.id.sbSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)

        val p = Prefs.of(this)
        etSymbols.setText(p.getString(Prefs.KEY_SYMBOLS, Prefs.DEFAULT_SYMBOLS))
        etStockSec.setText(Prefs.stockSec(this).toString())
        etApiKey.setText(Prefs.apiKey(this))
        etCricketSec.setText(Prefs.cricketSec(this).toString())
        selected.putAll(Prefs.matchEntries(this))
        showSavedMatches()

        rgStyle.check(
            when (Prefs.style(this)) {
                Prefs.STYLE_FLOATING -> R.id.rbFloating
                Prefs.STYLE_BOTTOM -> R.id.rbBottom
                else -> R.id.rbTop
            }
        )

        sbTextSize.progress = Prefs.textSize(this)
        sbSpeed.progress = Prefs.speed(this)
        updateLabels()
        val labelUpdater = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sbTextSize.setOnSeekBarChangeListener(labelUpdater)
        sbSpeed.setOnSeekBarChangeListener(labelUpdater)

        setupQuickAdd()

        findViewById<Button>(R.id.btnLoadMatches).setOnClickListener { loadMatches() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startTicker() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, TickerService::class.java))
            Toast.makeText(this, "Ticker stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun updateLabels() {
        tvTextSize.text = "Text size: ${sbTextSize.progress}"
        tvSpeed.text = "Scroll speed: ${sbSpeed.progress}"
    }

    private fun setupQuickAdd() {
        val box = findViewById<LinearLayout>(R.id.quickAdd)
        listOf(
            "+ Nifty 50" to "^NSEI", "+ Sensex" to "^BSESN", "+ Bank Nifty" to "^NSEBANK",
            "+ S&P 500" to "^GSPC", "+ Nasdaq" to "^IXIC", "+ Dow" to "^DJI"
        ).forEach { (label, sym) ->
            box.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setOnClickListener {
                    val cur = etSymbols.text.toString().split(',')
                        .map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    if (sym !in cur) etSymbols.setText((cur + sym).joinToString(", "))
                }
            })
        }
    }

    // ---------- cricket matches ----------

    private fun loadMatches() {
        val key = etApiKey.text.toString().trim()
        Prefs.of(this).edit().putString(Prefs.KEY_API_KEY, key).apply()
        if (key.isEmpty()) {
            tvMatchStatus.text = "Enter your free CricketData API key first."
            return
        }
        tvMatchStatus.text = "Loading matches…"
        Thread {
            try {
                val list = DataFetcher.fetchCurrentMatches(key).sortedBy { it.ended }
                runOnUiThread { showMatches(list) }
            } catch (e: Exception) {
                runOnUiThread { tvMatchStatus.text = "Couldn't load matches: ${e.message}" }
            }
        }.start()
    }

    private fun showMatches(list: List<Match>) {
        matchList.removeAllViews()
        selected.keys.retainAll(list.map { it.id }.toSet())
        if (list.isEmpty()) {
            tvMatchStatus.text = "No current matches right now."
            return
        }
        tvMatchStatus.text = "Tick the matches to show on the ticker:"
        for (m in list) {
            val extra = if (m.ended) "${m.status} (finished)" else m.status
            addMatchBox(m.id, m.name, extra)
        }
    }

    private fun showSavedMatches() {
        matchList.removeAllViews()
        if (selected.isEmpty()) {
            tvMatchStatus.text = "Tap \"Load current matches\" to pick a match."
            return
        }
        tvMatchStatus.text = "Selected matches:"
        for ((id, name) in selected) addMatchBox(id, name, "")
    }

    private fun addMatchBox(id: String, name: String, status: String) {
        val cb = CheckBox(this)
        cb.text = if (status.isBlank()) name else "$name\n$status"
        cb.isChecked = id in selected
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected[id] = name else selected.remove(id)
        }
        matchList.addView(cb)
    }

    // ---------- save / start ----------

    private fun save() {
        val style = when (rgStyle.checkedRadioButtonId) {
            R.id.rbFloating -> Prefs.STYLE_FLOATING
            R.id.rbBottom -> Prefs.STYLE_BOTTOM
            else -> Prefs.STYLE_TOP
        }
        Prefs.of(this).edit()
            .putString(Prefs.KEY_SYMBOLS, etSymbols.text.toString())
            .putInt(Prefs.KEY_STOCK_SEC, etStockSec.text.toString().toIntOrNull()?.coerceAtLeast(10) ?: 30)
            .putString(Prefs.KEY_API_KEY, etApiKey.text.toString().trim())
            .putInt(Prefs.KEY_CRICKET_SEC, etCricketSec.text.toString().toIntOrNull()?.coerceAtLeast(30) ?: 120)
            .putStringSet(Prefs.KEY_MATCHES, selected.map { "${it.key}\t${it.value}" }.toSet())
            .putString(Prefs.KEY_STYLE, style)
            .putInt(Prefs.KEY_TEXT_SIZE, sbTextSize.progress)
            .putInt(Prefs.KEY_SPEED, sbSpeed.progress)
            .apply()
    }

    private fun startTicker() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Turn on \"Display over other apps\" for Live Ticker, then come back and tap Start.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, TickerService::class.java))
        Toast.makeText(this, "Ticker started. Long-press it to come back here.", Toast.LENGTH_LONG).show()
    }
}
