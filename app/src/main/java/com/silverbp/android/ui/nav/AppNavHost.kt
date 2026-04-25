package com.silverbp.android.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.silverbp.android.ui.capture.CaptureScreen
import com.silverbp.android.ui.confirm.ConfirmReadingScreen
import com.silverbp.android.ui.history.HistoryScreen
import com.silverbp.android.ui.insights.InsightsScreen
import com.silverbp.android.ui.report.ReportScreen
import com.silverbp.android.ui.settings.SettingsScreen
import com.silverbp.android.ui.today.TodayScreen

@Composable
fun AppNavHost() {
    val rootNav = rememberNavController()
    NavHost(navController = rootNav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeWithTabs(rootNav) }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onCaptured = { readingId -> rootNav.navigate(Routes.confirmEdit(readingId)) },
                onManualEntry = { rootNav.navigate(Routes.CONFIRM_NEW) },
                onClose = { rootNav.popBackStack() }
            )
        }
        composable(
            "${Routes.CONFIRM}/{${Routes.ARG_READING_ID}}",
            arguments = listOf(navArgument(Routes.ARG_READING_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_READING_ID)
            ConfirmReadingScreen(
                readingIdArg = id,
                onSaved = {
                    rootNav.popBackStack(Routes.HOME, inclusive = false)
                },
                onCancel = { rootNav.popBackStack() }
            )
        }
    }
}

@Composable
private fun HomeWithTabs(rootNav: NavHostController) {
    val tabsNav = rememberNavController()
    val backstack by tabsNav.currentBackStackEntryAsState()
    val currentRoute = backstack?.destination?.route ?: TabDestination.Today.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TabDestination.all.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            tabsNav.navigate(tab.route) {
                                popUpTo(TabDestination.Today.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = tabsNav, startDestination = TabDestination.Today.route) {
                tabsGraph(rootNav)
            }
        }
    }
}

private fun NavGraphBuilder.tabsGraph(rootNav: NavHostController) {
    composable(TabDestination.Today.route) {
        TodayScreen(
            onCapture = { rootNav.navigate(Routes.CAPTURE) },
            onAddManual = { rootNav.navigate(Routes.CONFIRM_NEW) }
        )
    }
    composable(TabDestination.History.route) {
        HistoryScreen(
            onEdit = { id -> rootNav.navigate(Routes.confirmEdit(id)) }
        )
    }
    composable(TabDestination.Insights.route) { InsightsScreen() }
    composable(TabDestination.Report.route) { ReportScreen() }
    composable(TabDestination.Settings.route) { SettingsScreen() }
}
