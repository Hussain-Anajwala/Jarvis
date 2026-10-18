package com.jarvis.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the app picks light or dark: follow Android (default) or force one. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    /** Whether this mode means dark given Android's current setting. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun parse(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** The saved theme choice. Settings writes it and the activity reads it, so changing it restyles the whole app at once. */
object ThemePreference {
    private const val PREFS = "ui"
    private const val KEY = "theme"
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _mode.value = ThemeMode.parse(prefs(context).getString(KEY, null))
    }

    fun set(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
