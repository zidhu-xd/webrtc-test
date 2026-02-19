package com.chatapp.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chatapp.data.model.User
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent user preferences stored in DataStore (free, replaces SharedPreferences).
 * Stores the JWT token and current user data between app sessions.
 */
@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson
) {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
        private val USER_KEY = stringPreferencesKey("current_user")
    }

    val authToken: Flow<String?> = dataStore.data.map { it[TOKEN_KEY] }
    val currentUser: Flow<User?> = dataStore.data.map { prefs ->
        prefs[USER_KEY]?.let { gson.fromJson(it, User::class.java) }
    }

    suspend fun saveAuthData(token: String, user: User) {
        dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = token
            prefs[USER_KEY] = gson.toJson(user)
        }
    }

    suspend fun clearAuthData() {
        dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs.remove(USER_KEY)
        }
    }

    fun bearerToken(token: String) = "Bearer $token"
}
