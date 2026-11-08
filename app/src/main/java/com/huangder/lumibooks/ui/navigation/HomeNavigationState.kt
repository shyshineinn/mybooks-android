package com.huangder.lumibooks.ui.navigation

import com.huangder.lumibooks.domain.model.Book
import com.huangder.lumibooks.ui.home.DailyReading
import com.huangder.lumibooks.ui.home.HomeUiState

/** Navigation renders summaries. Open-book actions read the current download state on demand. */
internal data class HomeNavigationState(
    val isLoading: Boolean,
    val importMessage: String?,
    val tagMessage: String?,
    val error: String?,
    val lastReadBook: Book?,
    val authorizedBookDirectories: List<String>,
    val importBooksLayoutMode: Int,
    val folderNames: Map<String, String>,
    val todayReadingTime: Long,
    val dailyGoal: Int,
    val weeklyData: List<DailyReading>,
    val streakDays: Int
)

internal fun HomeUiState.forNavigation() = HomeNavigationState(
    isLoading, importMessage, tagMessage, error,
    books.maxByOrNull { it.lastReadTime }, authorizedBookDirectories, importBooksLayoutMode,
    folders.associate { it.id to it.name }, todayReadingTime, dailyGoal, weeklyData, streakDays
)
