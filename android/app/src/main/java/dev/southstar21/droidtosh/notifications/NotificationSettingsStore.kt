package dev.southstar21.droidtosh.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notificationSettings by preferencesDataStore(name = "notification-settings")

class NotificationSettingsStore(private val context: Context) {
    val mutedPackages: Flow<Set<String>> = context.notificationSettings.data.map { preferences ->
        preferences[MUTED_KEY]?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    suspend fun setMutedPackages(packages: Set<String>) {
        context.notificationSettings.edit { preferences ->
            preferences[MUTED_KEY] = packages.joinToString("\n")
        }
    }

    private companion object {
        val MUTED_KEY = stringPreferencesKey("muted-packages")
    }
}
