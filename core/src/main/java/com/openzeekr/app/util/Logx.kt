package com.openzeekr.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide verbose logging. Every message goes to logcat (tag "openzeekr") AND
 * to an in-memory ring buffer exposed as a [StateFlow] so the UI can show the
 * live log on-device (handy when no debugger is attached, e.g. testing at the
 * car). Nothing here is persisted, so a restart clears it.
 *
 * Secrets: helpers [redact] / [preview] keep sensitive values out of the log
 * while still showing enough to debug (length + last 4 chars).
 */
object Logx {
    private const val TAG = "openzeekr"
    private const val MAX = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** On-device ring-buffer collection gate (logcat always fires). Driven by the
     *  Settings "Debug logging" toggle; off by default until config is applied. */
    @Volatile private var enabled = false
    fun setEnabled(on: Boolean) { enabled = on; if (!on) _lines.value = emptyList() }

    fun d(area: String, msg: String) = emit('D', area, msg).also { Log.d(TAG, "[$area] $msg") }
    fun w(area: String, msg: String) = emit('W', area, msg).also { Log.w(TAG, "[$area] $msg") }
    fun e(area: String, msg: String, t: Throwable? = null) {
        emit('E', area, msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        Log.e(TAG, "[$area] $msg", t)
    }

    fun clear() { _lines.value = emptyList() }

    /** Full log as a single copy-pasteable string. */
    fun dump(): String = _lines.value.joinToString("\n")

    private fun emit(level: Char, area: String, msg: String) {
        if (!enabled) return
        val line = "${clock.format(Date())} $level/$area  $msg"
        val cur = _lines.value
        _lines.value = (if (cur.size >= MAX) cur.drop(cur.size - MAX + 1) else cur) + line
    }

    /** "<len> chars …abcd" — never the full secret. Blank stays "(blank)". */
    fun preview(secret: String?): String = when {
        secret.isNullOrEmpty() -> "(blank)"
        secret.length <= 4 -> "${secret.length} chars"
        else -> "${secret.length} chars …${secret.takeLast(4)}"
    }
}
