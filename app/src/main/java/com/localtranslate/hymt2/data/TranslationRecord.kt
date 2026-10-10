package com.localtranslate.hymt2.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_history",
    indices = [Index(value = ["createdAtMillis"])],
)
data class TranslationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceText: String,
    val targetLanguage: String,
    val translatedText: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
