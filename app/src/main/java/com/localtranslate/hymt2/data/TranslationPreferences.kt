package com.localtranslate.hymt2.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.translationSettings by preferencesDataStore(name = "translation-settings")

class TranslationPreferences(context: Context) {
    private val store = context.applicationContext.translationSettings

    val targetLanguage: Flow<String> = store.data.map { preferences ->
        preferences[TARGET_LANGUAGE] ?: DEFAULT_TARGET_LANGUAGE
    }

    val autoBilingualVoiceDirection: Flow<Boolean> = store.data.map { preferences ->
        preferences[AUTO_BILINGUAL_VOICE_DIRECTION] ?: true
    }

    suspend fun setTargetLanguage(language: String) {
        store.edit { preferences -> preferences[TARGET_LANGUAGE] = language }
    }

    suspend fun setAutoBilingualVoiceDirection(enabled: Boolean) {
        store.edit { preferences -> preferences[AUTO_BILINGUAL_VOICE_DIRECTION] = enabled }
    }

    private companion object {
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val AUTO_BILINGUAL_VOICE_DIRECTION = booleanPreferencesKey("auto_bilingual_voice_direction")
        const val DEFAULT_TARGET_LANGUAGE = "中文"
    }
}
