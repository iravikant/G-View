package com.acoder.gallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acoder.gallery.domain.model.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("gallery_preferences")

@Singleton
class PreferencesRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val sortKey = stringPreferencesKey("sort")
    private val themeKey = stringPreferencesKey("theme")
    private val gridKey = intPreferencesKey("grid")
    private val autoplayKey = booleanPreferencesKey("autoplay")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")

    val sortOrder: Flow<SortOrder> = context.store.data.map {
        runCatching { SortOrder.valueOf(it[sortKey] ?: SortOrder.NEWEST.name) }.getOrDefault(SortOrder.NEWEST)
    }
    val themeMode: Flow<String> = context.store.data.map { it[themeKey] ?: "SYSTEM" }
    val gridSize: Flow<Int> = context.store.data.map { it[gridKey] ?: 4 }
    val autoPlay: Flow<Boolean> = context.store.data.map { it[autoplayKey] ?: true }
    val dynamicColor: Flow<Boolean> = context.store.data.map { it[dynamicKey] ?: true }

    suspend fun setSort(v: SortOrder) { context.store.edit { it[sortKey] = v.name } }
    suspend fun setTheme(v: String) { context.store.edit { it[themeKey] = v } }
    suspend fun setGrid(v: Int) { context.store.edit { it[gridKey] = v } }
    suspend fun setAutoPlay(v: Boolean) { context.store.edit { it[autoplayKey] = v } }
    suspend fun setDynamicColor(v: Boolean) { context.store.edit { it[dynamicKey] = v } }
}
