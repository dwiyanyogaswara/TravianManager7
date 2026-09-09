package com.example.travianfarmassistant

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : Activity() {
    private val logFileName = "farm_assistant.log"
    private val logMaxAgeMs = 12 * 60 * 60 * 1000L
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Log Aktivitas"

        pruneLogs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "LOG AKTIVITAS (12 JAM TERAKHIR)"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val refresh = Button(this).apply {
            text = "REFRESH"
            setOnClickListener { showLogs() }
        }
        header.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val clear = Button(this).apply {
            text = "HAPUS LOG"
            setOnClickListener {
                try {
                    getFileStreamPath(logFileName).delete()
                } catch (_: Exception) {
                    // Ignore logging UI cleanup errors.
                }
                showLogs()
            }
        }
        val clearParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        clearParams.marginStart = 8
        header.addView(clear, clearParams)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
        }
        val logView = TextView(this).apply {
            id = android.R.id.text1
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
            setTextIsSelectable(true)
        }
        scroll.addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showLogs()
    }

    private fun showLogs() {
        val logView = findViewById<TextView>(android.R.id.text1)
        pruneLogs()
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) {
                logView.text = "Belum ada log."
                return
            }
            val lines = file.readLines()
            logView.text = if (lines.isEmpty()) "Belum ada log." else lines.joinToString("\n")
        } catch (_: Exception) {
            logView.text = "Gagal membaca log."
        }
    }

    private fun pruneLogs() {
        try {
            val file = getFileStreamPath(logFileName)
            if (!file.exists()) return
            val cutoff = System.currentTimeMillis() - logMaxAgeMs
            val kept = file.readLines().filter { line ->
                try {
                    val stamp = line.substringBefore(" | ")
                    val time = logTimeFormat.parse(stamp)?.time ?: return@filter false
                    time >= cutoff
                } catch (_: Exception) {
                    false
                }
            }
            file.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        } catch (_: Exception) {
            // Never interrupt the main automation because of logging.
        }
    }
}
