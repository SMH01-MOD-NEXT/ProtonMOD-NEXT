package com.protonvpn.android

import android.content.Context
import androidx.core.content.edit

class ProxyPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("protonmod_prefs", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    companion object {
        private const val KEY_ENABLED = "proxy_enabled"
    }
}