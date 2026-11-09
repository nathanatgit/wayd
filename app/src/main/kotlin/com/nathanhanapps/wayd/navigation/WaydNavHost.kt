package com.nathanhanapps.wayd.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.apps.AppDetailScreen
import com.nathanhanapps.wayd.feature.apps.AppsScreen
import com.nathanhanapps.wayd.feature.record.RecordScreen
import com.nathanhanapps.wayd.feature.reports.ReportDetailScreen
import com.nathanhanapps.wayd.feature.reports.ReportsScreen
import com.nathanhanapps.wayd.feature.settings.SettingsScreen

private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector, val children: List<String> = emptyList()) {
    Record("record", R.string.tab_record, Icons.Filled.PlayArrow),
    Reports("reports", R.string.tab_reports, Icons.Filled.DateRange, listOf("report/")),
    Apps("apps", R.string.tab_apps, Icons.AutoMirrored.Filled.List, listOf("app/")),
    Settings("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun WaydNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { d -> d.route == tab.route || tab.children.any { c -> d.route?.startsWith(c) == true } } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Record.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Record.route) { RecordScreen() }
            composable(Tab.Reports.route) { ReportsScreen(onOpenDay = { nav.navigate("report/$it") }) }
            composable("report/{win}", listOf(navArgument("win") { type = NavType.StringType })) {
                ReportDetailScreen(onBack = { nav.popBackStack() }, onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") })
            }
            composable(Tab.Apps.route) { AppsScreen(onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") }) }
            composable("app/{pkg}/{win}", listOf(navArgument("pkg") { type = NavType.StringType }, navArgument("win") { type = NavType.StringType })) {
                AppDetailScreen(onBack = { nav.popBackStack() })
            }
            composable(Tab.Settings.route) { SettingsScreen() }
        }
    }
}
