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
    private lateinit var etTeams: EditText
    private lateinit var etSportsSec: EditText
    private lateinit var rgStyle: RadioGroup
    private lateinit var sbTextSize: SeekBar
    private lateinit var tvTextSize: TextView
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var cbNews: CheckBox
    private lateinit var etCity: EditText
    private lateinit var etNewsMin: EditText

    private val sportBoxes = LinkedHashMap<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSymbols = findViewById(R.id.etSymbols)
        etStockSec = findViewById(R.id.etStockSec)
        etTeams = findViewById(R.id.etTeams)
        etSportsSec = findViewById(R.id.etSportsSec)
        rgStyle = findViewById(R.id.rgStyle)
        sbTextSize = findViewById(R.id.sbTextSize)
        tvTextSize = findViewById(R.id.tvTextSize)
        sbSpeed = findViewById(R.id.sbSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        cbNews = findViewById(R.id.cbNews)
        etCity = findViewById(R.id.etCity)
        etNewsMin = findViewById(R.id.etNewsMin)

        val p = Prefs.of(this)
        etSymbols.setText(p.getString(Prefs.KEY_SYMBOLS, Prefs.DEFAULT_SYMBOLS))
        etStockSec.setText(Prefs.stockSec(this).toString())
        etTeams.setText(p.getString(Prefs.KEY_TEAMS, ""))
        etSportsSec.setText(Prefs.sportsSec(this).toString())
        cbNews.isChecked = Prefs.newsOn(this)
        etCity.setText(Prefs.newsCity(this))
        etNewsMin.setText(Prefs.newsMin(this).toString())
        setupSports()

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

    private fun setupSports() {
        val box = findViewById<LinearLayout>(R.id.sportsList)
        val chosen = Prefs.sports(this)
        for (s in SportsFetcher.ALL) {
            val cb = CheckBox(this)
            cb.text = s.icon + "  " + s.label
            cb.isChecked = s.key in chosen
            box.addView(cb)
            sportBoxes[s.key] = cb
        }
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
            .putStringSet(Prefs.KEY_SPORTS, sportBoxes.filter { it.value.isChecked }.keys.toSet())
            .putString(Prefs.KEY_TEAMS, etTeams.text.toString().trim())
            .putInt(Prefs.KEY_SPORTS_SEC, etSportsSec.text.toString().toIntOrNull()?.coerceAtLeast(30) ?: 120)
            .putString(Prefs.KEY_STYLE, style)
            .putInt(Prefs.KEY_TEXT_SIZE, sbTextSize.progress)
            .putInt(Prefs.KEY_SPEED, sbSpeed.progress)
            .putBoolean(Prefs.KEY_NEWS_ON, cbNews.isChecked)
            .putString(Prefs.KEY_NEWS_CITY, etCity.text.toString().trim())
            .putInt(Prefs.KEY_NEWS_MIN, etNewsMin.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 10)
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
