package com.openzeekr.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide logging with two tiers, both gated by the Settings "Debug logging" toggle:
 *  - **verbose (D)** — the HTTP interceptors, handshake traces, tokens/key material.
 *    Written to logcat AND the on-device ring buffer **only while logging is ON**. With the
 *    toggle OFF it is fully suppressed, so a released/idle app never sprays request bodies or
 *    secrets to logcat.
 *  - **warnings/errors (W/E)** — low-volume, non-bulk (no HTTP bodies; secrets already go
 *    through [preview]). Always emitted to logcat so real failures are still diagnosable, but
 *    only added to the on-device buffer while logging is ON.
 *
 * The ring buffer is a [StateFlow] the UI shows on-device (handy at the car with no debugger).
 * Nothing is persisted, so a restart clears it.
 *
 * Secrets: helper [preview] keeps sensitive values out of the log while still showing enough
 * to debug (length + last 4 chars).
 */
object Logx {
    private const val TAG = "openzeekr"
    private const val MAX = 500

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Master gate, driven by the Settings "Debug logging" toggle; off by default until config
     *  is applied. When OFF: verbose [d] is suppressed from BOTH logcat and the ring buffer, so
     *  no HTTP bodies/tokens/key material reach the log. W/E still hit logcat (minimal, basic). */
    @Volatile private var enabled = false
    fun setEnabled(on: Boolean) { enabled = on; if (!on) _lines.value = emptyList() }
    /** Whether verbose logging is on — lets callers (e.g. the OkHttp interceptor) skip building
     *  expensive/sensitive body strings entirely when logging is off. */
    val isEnabled: Boolean get() = enabled

    /** Verbose. Fully gated — nothing (not even logcat) unless logging is ON. */
    fun d(area: String, msg: String) {
        if (!enabled) return
        emit('D', area, msg); Log.d(TAG, "[$area] $msg")
    }
    /** Warning — always to logcat (low-volume, no HTTP bodies); buffered only when ON. */
    fun w(area: String, msg: String) = emit('W', area, msg).also { Log.w(TAG, "[$area] $msg") }
    /** Error — always to logcat; buffered only when ON. */
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
