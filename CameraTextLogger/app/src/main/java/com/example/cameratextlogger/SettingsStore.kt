package com.example.cameratextlogger

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("general_settings", Context.MODE_PRIVATE)

    fun load(): GeneralSettings {
        val defaults = GeneralSettings()
        return GeneralSettings(
            minCharacters = prefs.getInt("minCharacters", defaults.minCharacters),
            ignoreSymbolsOnly = prefs.getBoolean("ignoreSymbolsOnly", defaults.ignoreSymbolsOnly),
        )
    }

    fun save(settings: GeneralSettings) {
        prefs.edit()
            .putInt("minCharacters", settings.minCharacters)
            .putBoolean("ignoreSymbolsOnly", settings.ignoreSymbolsOnly)
            .apply()
    }
}
