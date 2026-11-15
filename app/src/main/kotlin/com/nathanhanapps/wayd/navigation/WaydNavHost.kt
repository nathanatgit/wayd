package com.nathanhanapps.wayd.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.vectorResource
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

/** Five tabs (D-071, D-077): is it running · what happened · what am I doing about it · what does it mean · tools and settings. */
private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int, val children: List<String> = emptyList()) {
    Record("record", R.string.tab_record, R.drawable.space_dashboard_24),
    Apps("apps", R.string.tab_traffic, R.drawable.sync_alt_24, listOf("apps?", "app/", "messages/", "connection/")),
    Rules("rules", R.string.tab_rules, R.drawable.rule_24, listOf("rules/")),
    Reports("reports", R.string.tab_insights, R.drawable.analytics_24, listOf("report/", "company/")),
    Settings("settings", R.string.settings_title, R.drawable.settings_24, listOf("settings/", "scanner")),
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
                        icon = { Icon(ImageVector.vectorResource(tab.icon), contentDescription = null) },
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
                    onOpenTraffic = {
                        nav.navigate(Tab.Apps.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenRules = {
                        nav.navigate(Tab.Rules.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenInsights = {
                        nav.navigate(Tab.Reports.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Tab.Reports.route) {
                ReportsScreen(
                    onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") },
                    onOpenCompany = { name, win -> nav.navigate("company/${android.net.Uri.encode(name)}/$win") },
                    onOpenScanner = { nav.navigate("scanner") },
                    onOpenSettings = {
                        nav.navigate(Tab.Settings.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
            composable(Tab.Rules.route) {
                com.nathanhanapps.wayd.feature.controls.RulesScreen(
                    onOpenDns = { nav.navigate("rules/dns") },
                    onOpenRequest = { nav.navigate("rules/request") },
                )
            }
            composable("connection/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                com.nathanhanapps.wayd.feature.traffic.ConnectionDetailScreen(onBack = { nav.popBackStack() }, onOpenApp = { pkg, win -> nav.navigate("app/$pkg/$win") })
            }
            composable("rules/dns") {
                com.nathanhanapps.wayd.feature.controls.DnsRulesScreen(onBack = { nav.popBackStack() })
            }
            composable("rules/request") {
                com.nathanhanapps.wayd.feature.controls.RequestRulesScreen(
                    onBack = { nav.popBackStack() },
                    onOpenApp = { pkg -> nav.navigate("app/${android.net.Uri.encode(pkg)}/today") },
                    onOpenRewriteSet = { id -> nav.navigate("rules/rewrite/$id") },
                )
            }
            composable("rules/rewrite/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                com.nathanhanapps.wayd.feature.controls.RewriteSetScreen(
                    onBack = { nav.popBackStack() },
                    onEditRule = { set, rule -> nav.navigate("rules/rewrite/$set/rule?rule=${rule ?: 0}") },
                )
            }
            composable(
                "rules/rewrite/{id}/rule?rule={rule}",
                listOf(navArgument("id") { type = NavType.LongType }, navArgument("rule") { type = NavType.LongType; defaultValue = 0L }),
            ) {
                com.nathanhanapps.wayd.feature.controls.RewriteRuleEditorScreen(onBack = { nav.popBackStack() })
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
                SettingsScreen(onOpenScanner = { nav.navigate("scanner") }, onOpenAi ={ nav.navigate("settings/ai") }, onOpenAccess = { nav.navigate("settings/access") }, onOpenResearch = { nav.navigate("settings/research") })
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
