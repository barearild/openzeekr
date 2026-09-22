package com.openzeekr.app.net

import com.openzeekr.app.util.Logx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A published GitHub release of OpenZeekr. */
data class ReleaseInfo(val version: String, val tag: String, val url: String, val notes: String)

/**
 * Checks the GitHub Releases API for a newer OpenZeekr build. The repo is public, so no auth is
 * needed (the unauthenticated GitHub API allows 60 requests/hour, plenty for an occasional check).
 * Uses the JSON tree API (no serializer), so it needs no extra R8 keep rules.
 */
object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/borconi/openzeekr/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/borconi/openzeekr/releases/latest"
    private val client by lazy { OkHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetch the latest published release, or null on any error / non-2xx. */
    fun fetchLatest(): ReleaseInfo? = runCatching {
        val req = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "openzeekr-app")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Logx.d("update", "GitHub releases HTTP ${resp.code}"); return null }
            val o = json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            ReleaseInfo(
                version = tag.trimStart('v', 'V'),
                tag = tag,
                url = o["html_url"]?.jsonPrimitive?.contentOrNull ?: RELEASES_PAGE,
                notes = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
    }.getOrElse { Logx.d("update", "update check failed: ${it.message}"); null }

    /**
     * True if [latest] is a strictly higher semantic version than [installed] (both like "0.1.4").
     * Ignores a "v" prefix and any pre-release / build tail (e.g. "0.2.0-wearos" -> 0.2.0).
     */
    fun isNewer(installed: String, latest: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('-', '+')[0].split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(installed); val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }
}
