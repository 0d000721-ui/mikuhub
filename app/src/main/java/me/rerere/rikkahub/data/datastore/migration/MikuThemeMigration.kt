package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.theme.presets.MIKU_THEME_ID

/** Apply the new default once, including upgrades which had saved the old default. */
class MikuThemeMigration : DataMigration<Preferences> {
    private val migrated = booleanPreferencesKey("miku_default_applied")

    override suspend fun shouldMigrate(currentData: Preferences) = currentData[migrated] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val themeId = prefs[SettingsStore.THEME_ID]
        if (themeId == null || themeId == "sakura") {
            prefs[SettingsStore.THEME_ID] = MIKU_THEME_ID
            prefs[SettingsStore.DYNAMIC_COLOR] = false
        }
        prefs[migrated] = true
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
