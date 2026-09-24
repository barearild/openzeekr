package com.openzeekr.app.config

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStoreSecurityTest {

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) staged[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) target.clear()
            removed.forEach { target.remove(it) }
            staged.forEach { (k, v) -> target[k] = v }
        }
    }

    @Test
    fun testExportJsonScrubsPassword() {
        val prefs = FakeSharedPreferences()
        val store = ConfigStore(prefs)

        store.update { it.copy(email = "user@test.com", password = "super_secret_password") }
        assertEquals("super_secret_password", store.current().password)

        val json = store.exportJson()
        assertFalse("Exported JSON must not contain raw password", json.contains("super_secret_password"))
        assertTrue("Exported JSON must include empty password field", json.contains("\"password\": \"\""))
    }

    @Test
    fun testLoadSanitizesLegacyPasswordWhenAccessTokenPresent() {
        val prefs = FakeSharedPreferences()
        val legacyJson = Json { encodeDefaults = true }.encodeToString(
            SecretsConfig.serializer(),
            SecretsConfig(email = "user@test.com", password = "legacy_password", accessToken = "bearer_token_123")
        )
        prefs.map["config_json"] = legacyJson

        val store = ConfigStore(prefs)
        assertEquals("", store.current().password)
        assertEquals("bearer_token_123", store.current().accessToken)
    }
}
