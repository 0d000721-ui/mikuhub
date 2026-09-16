package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test

class MikuThemeMigrationTest {
    @Test fun freshInstallGetsMikuWithoutWallpaperColors() = runBlocking {
        val migration = MikuThemeMigration()
        val result = migration.migrate(emptyPreferences())
        assertEquals("miku", result[SettingsStore.THEME_ID])
        assertEquals(false, result[SettingsStore.DYNAMIC_COLOR])
        assertFalse(migration.shouldMigrate(result))
    }

    @Test fun savedOldDefaultChangesOnUpgradeAndKeepsOtherPreferences() = runBlocking {
        val result = MikuThemeMigration().migrate(preferencesOf(
            SettingsStore.THEME_ID to "sakura",
            SettingsStore.DYNAMIC_COLOR to true,
            SettingsStore.LAUNCH_COUNT to 42,
        ))
        assertEquals("miku", result[SettingsStore.THEME_ID])
        assertEquals(false, result[SettingsStore.DYNAMIC_COLOR])
        assertEquals(42, result[SettingsStore.LAUNCH_COUNT])
    }

    @Test fun explicitOtherThemeIsPreserved() = runBlocking {
        val result = MikuThemeMigration().migrate(preferencesOf(
            SettingsStore.THEME_ID to "my-custom-theme",
            SettingsStore.DYNAMIC_COLOR to true,
        ))
        assertEquals("my-custom-theme", result[SettingsStore.THEME_ID])
        assertEquals(true, result[SettingsStore.DYNAMIC_COLOR])
    }

    @Test fun userCanSelectOldThemeAgainAfterMigration() = runBlocking {
        val migration = MikuThemeMigration()
        val changed = migration.migrate(emptyPreferences()).toMutablePreferences().apply {
            this[SettingsStore.THEME_ID] = "sakura"
            this[SettingsStore.DYNAMIC_COLOR] = true
        }
        assertFalse(migration.shouldMigrate(changed))
    }
}
