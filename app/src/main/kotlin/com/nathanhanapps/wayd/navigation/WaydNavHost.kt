package com.nathanhanapps.wayd.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
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
import com.nathanhanapps.wayd.feature.inspector.InspectorScreen
import com.nathanhanapps.wayd.feature.record.RecordScreen
import com.nathanhanapps.wayd.feature.reports.CompanyScreen
import com.nathanhanapps.wayd.feature.reports.ReportDetailScreen
import com.nathanhanapps.wayd.feature.reports.ReportsScreen
import com.nathanhanapps.wayd.feature.settings.SettingsScreen
import com.nathanhanapps.wayd.feature.ai.ExternalAiGateDialog

private enum class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector, val children: List<String> = emptyList()) {
    Record("record", R.string.tab_record, Icons.Filled.Home),
    Apps("apps", R.string.tab_traffic, Icons.AutoMirrored.Filled.List, listOf("apps?", "app/", "messages/", "connection/")),
    Reports("reports", R.string.tab_insights, Icons.Filled.DateRange, listOf("report/", "company/")),
    Tools("tools", R.string.tab_tools, Icons.Filled.Build, listOf("controls", "scanner", "settings")),
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
        ExternalAiGateDialog()
        NavHost(nav, startDestination = Tab.Record.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Record.route) {
                RecordScreen(
                    onOpenControls = { nav.navigate("controls") },
                    onOpenReports = { nav.navigate(Tab.Reports.route) { launchSingleTop = true } },
                    onOpenConnections = { nav.navigate("apps?view=connections") { popUpTo(nav.graph.findStartDestination().id); launchSingleTop = true } },
                )
            }
            composable(Tab.Reports.route) {
                ReportsScreen(
                    onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") },
                    onOpenCompany = { name, win -> nav.navigate("company/${android.net.Uri.encode(name)}/$win") },
                )
            }
            composable("report/{win}", listOf(navArgument("win") { type = NavType.StringType })) {
                ReportDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") },
                    onOpenCompany = { name, win -> nav.navigate("company/${android.net.Uri.encode(name)}/$win") },
                )
            }
            composable("company/{name}/{win}", listOf(navArgument("name") { type = NavType.StringType }, navArgument("win") { type = NavType.StringType })) {
                CompanyScreen(onBack = { nav.popBackStack() }, onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") })
            }
            composable("apps?view={view}", listOf(navArgument("view") { type = NavType.StringType; defaultValue = "apps" })) { entry ->
                com.nathanhanapps.wayd.feature.traffic.TrafficScreen(entry.arguments?.getString("view").orEmpty(), onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") }, onOpenConnection = { id -> nav.navigate("connection/$id") })
            }
            composable(Tab.Tools.route) {
                com.nathanhanapps.wayd.feature.tools.ToolsScreen(
                    onOpenRules = { nav.navigate("controls") },
                    onOpenScanner = { nav.navigate("scanner") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("connection/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                com.nathanhanapps.wayd.feature.traffic.ConnectionDetailScreen(onBack = { nav.popBackStack() }, onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") })
            }
            composable("controls") {
                com.nathanhanapps.wayd.feature.controls.ControlsScreen(onOpenApp = { pkg -> nav.navigate("app/${android.net.Uri.encode(pkg)}/today") })
            }
            composable("scanner?pkg={pkg}", listOf(navArgument("pkg") { type = NavType.StringType; defaultValue = "" })) {
                com.nathanhanapps.wayd.feature.apps.ApkScanScreen(onBack = { nav.popBackStack() })
            }
            composable("app/{pkg}/{win}", listOf(navArgument("pkg") { type = NavType.StringType }, navArgument("win") { type = NavType.StringType })) {
                AppDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenMessages = { pkg, win -> nav.navigate("messages/$pkg/$win") },
                    onOpenScanner = { nav.navigate("scanner?pkg=${android.net.Uri.encode(it)}") },
                    onSelectWindow = { win ->
                        val pkg = nav.currentBackStackEntry?.arguments?.getString("pkg").orEmpty()
                        nav.navigate("app/${android.net.Uri.encode(pkg)}/$win") {
                            popUpTo("app/{pkg}/{win}") { inclusive = true }
                        }
                    },
                )
            }
            composable("messages/{pkg}/{win}", listOf(navArgument("pkg") { type = NavType.StringType }, navArgument("win") { type = NavType.StringType })) {
                InspectorScreen(onBack = { nav.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(onOpenAi = { nav.navigate("settings/ai") }, onOpenAccess = { nav.navigate("settings/access") }, onOpenResearch = { nav.navigate("settings/research") })
            }
            composable("settings/ai") {
                com.nathanhanapps.wayd.feature.ai.AiSettingsScreen(onBack = { nav.popBackStack() }, onOpenExternal = { nav.navigate("settings/ai/external") })
            }
            composable("settings/ai/external") { com.nathanhanapps.wayd.feature.ai.ExternalAiScreen(onBack = { nav.popBackStack() }) }
            composable("settings/access") { com.nathanhanapps.wayd.feature.settings.AccessSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings/research") { com.nathanhanapps.wayd.feature.settings.ResearchSettingsScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
