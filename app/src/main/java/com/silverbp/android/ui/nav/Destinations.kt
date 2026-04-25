package com.silverbp.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.silverbp.android.R

sealed class TabDestination(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Today    : TabDestination("today",    R.string.tab_today,    Icons.Filled.Home)
    data object History  : TabDestination("history",  R.string.tab_history,  Icons.Filled.History)
    data object Insights : TabDestination("insights", R.string.tab_insights, Icons.Filled.Assessment)
    data object Report   : TabDestination("report",   R.string.tab_report,   Icons.Filled.Description)
    data object Settings : TabDestination("settings", R.string.tab_settings, Icons.Filled.Settings)

    companion object {
        // Lazy because Kotlin initialises the companion object before the
        // sealed class's data-object children, so eager listOf(...) here
        // would capture nulls and crash NavigationBar at first render.
        val all: List<TabDestination> by lazy {
            listOf(Today, History, Insights, Report, Settings)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val CONFIRM = "confirm"           // confirm/{readingId?}
    const val CONFIRM_NEW = "confirm/new"   // brand-new manual entry
    fun confirmEdit(id: String) = "confirm/$id"
    const val ARG_READING_ID = "readingId"
}
