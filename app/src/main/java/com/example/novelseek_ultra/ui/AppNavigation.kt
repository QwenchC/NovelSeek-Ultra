package com.example.novelseek_ultra.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.novelseek_ultra.ui.theme.NovelSeekTheme
import com.example.novelseek_ultra.util.tx
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.novelseek_ultra.ui.screens.CharactersScreen
import com.example.novelseek_ultra.ui.screens.ContainerScreen
import com.example.novelseek_ultra.ui.screens.CultivationScreen
import com.example.novelseek_ultra.ui.screens.EditorScreen
import com.example.novelseek_ultra.ui.screens.ExportScreen
import com.example.novelseek_ultra.ui.screens.AgentScreen
import com.example.novelseek_ultra.ui.screens.HomeScreen
import com.example.novelseek_ultra.ui.screens.ListenScreen
import com.example.novelseek_ultra.ui.screens.LongNovelScreen
import com.example.novelseek_ultra.ui.screens.LongNovelsHomeScreen
import com.example.novelseek_ultra.ui.screens.OutlineScreen
import com.example.novelseek_ultra.ui.screens.ProjectScreen
import com.example.novelseek_ultra.ui.screens.NovelQaScreen
import com.example.novelseek_ultra.ui.screens.SettingsScreen
import com.example.novelseek_ultra.ui.screens.VersionHistoryScreen

private sealed class Tab(val route: String, val zh: String, val en: String, val icon: ImageVector) {
    data object Home : Tab("home", "短篇", "Short", Icons.Outlined.Book)
    data object LongHome : Tab("long_home", "长篇", "Long", Icons.Outlined.AutoStories)
    data object Listen : Tab("listen", "听书", "Listen", Icons.Outlined.Headphones)
    data object Settings : Tab("settings", "设置", "Settings", Icons.Outlined.Settings)
}

private val TABS = listOf(Tab.Home, Tab.LongHome, Tab.Listen, Tab.Settings)

object Routes {
    const val PROJECT = "project/{id}"
    const val OUTLINE = "outline/{id}"
    const val CHARACTERS = "characters/{id}"
    const val EXPORT = "export/{id}"
    const val EDITOR = "editor/{projectId}/{chapterId}"
    const val LONG_PROJECT = "long_project/{id}"
    const val LONG_OUTLINE = "long_outline/{id}"
    const val CULTIVATION = "cultivation/{id}"
    const val VERSION_HISTORY = "version_history/{id}"
    const val NOVEL_QA = "novel_qa/{id}"
    const val AGENT = "agent"
    const val CONTAINER = "container/{id}"

    fun project(id: String) = "project/$id"
    fun outline(id: String) = "outline/$id"
    fun characters(id: String) = "characters/$id"
    fun export(id: String) = "export/$id"
    fun editor(pid: String, cid: String) = "editor/$pid/$cid"
    fun longProject(id: String) = "long_project/$id"
    fun longOutline(id: String) = "long_outline/$id"
    fun cultivation(id: String) = "cultivation/$id"
    fun versionHistory(id: String) = "version_history/$id"
    fun novelQa(id: String) = "novel_qa/$id"
    fun container(id: String) = "container/$id"
}

@Composable
fun AppRoot() {
    val vm: AppViewModel = viewModel()
    val theme by vm.theme.collectAsState()
    NovelSeekTheme(darkTheme = theme == "dark") {
        Surface(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            AppRootBody(vm)
        }
    }
}

@Composable
private fun AppRootBody(vm: AppViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val lang by vm.uiLanguage.collectAsState()
    val currentRoute = backStack?.destination?.route

    val isTabRoot = TABS.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (isTabRoot) {
                AgentBottomBar(
                    currentRoute = currentRoute,
                    lang = lang,
                    onTab = { route ->
                        if (currentRoute != route) {
                            nav.navigate(route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onAgent = { nav.navigate(Routes.AGENT) },
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Tab.LongHome.route,   // default to the long-novel list (short novels rarely used now)
            modifier = Modifier.padding(innerPadding),
        ) {
            tabRoutes(nav, vm)
            detailRoutes(nav, vm)
        }
    }
}

private fun NavGraphBuilder.tabRoutes(nav: NavHostController, vm: AppViewModel) {
    composable(Tab.Home.route) {
        HomeScreen(vm, onOpen = { id -> nav.navigate(Routes.project(id)) })
    }
    composable(Tab.LongHome.route) {
        LongNovelsHomeScreen(vm, onOpen = { id -> nav.navigate(Routes.longProject(id)) })
    }
    composable(Tab.Listen.route) { ListenScreen(vm) }
    composable(Tab.Settings.route) { SettingsScreen(vm) }
}

private fun NavGraphBuilder.detailRoutes(nav: NavHostController, vm: AppViewModel) {
    composable(Routes.PROJECT) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        ProjectScreen(
            vm = vm, projectId = id,
            onBack = { nav.popBackStack() },
            onOpenChapter = { cid -> nav.navigate(Routes.editor(id, cid)) },
            onOpenOutline = { nav.navigate(Routes.outline(id)) },
            onOpenCharacters = { nav.navigate(Routes.characters(id)) },
            onOpenExport = { nav.navigate(Routes.export(id)) },
            onOpenHistory = { nav.navigate(Routes.versionHistory(id)) },
        )
    }
    composable(Routes.LONG_PROJECT) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        LongNovelScreen(
            vm = vm, projectId = id,
            onBack = { nav.popBackStack() },
            onOpenChapter = { cid -> nav.navigate(Routes.editor(id, cid)) },
            onOpenOutline = { nav.navigate(Routes.longOutline(id)) },
            onOpenCharacters = { nav.navigate(Routes.characters(id)) },
            onOpenExport = { nav.navigate(Routes.export(id)) },
            onOpenCultivation = { nav.navigate(Routes.cultivation(id)) },
            onOpenHistory = { nav.navigate(Routes.versionHistory(id)) },
            onOpenQa = { nav.navigate(Routes.novelQa(id)) },
            onOpenContainer = { nav.navigate(Routes.container(id)) },
        )
    }
    composable(Routes.OUTLINE) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        OutlineScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.LONG_OUTLINE) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        OutlineScreen(vm = vm, projectId = id, isLong = true, onBack = { nav.popBackStack() })
    }
    composable(Routes.CHARACTERS) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        CharactersScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.EXPORT) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        ExportScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.EDITOR) { entry ->
        val pid = entry.arguments?.getString("projectId") ?: return@composable
        val cid = entry.arguments?.getString("chapterId") ?: return@composable
        EditorScreen(
            vm = vm, projectId = pid, chapterId = cid,
            onBack = { nav.popBackStack() },
            onOpenQa = { nav.navigate(Routes.novelQa(pid)) },
            onOpenContainer = { nav.navigate(Routes.container(pid)) },
            onNavigateToChapter = { targetCid ->
                // Replace current editor entry so the back stack doesn't accumulate one entry per
                // chapter when the user pages through prev/next.
                nav.navigate(Routes.editor(pid, targetCid)) {
                    popUpTo(Routes.EDITOR) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(Routes.CULTIVATION) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        CultivationScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.VERSION_HISTORY) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        VersionHistoryScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.NOVEL_QA) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        NovelQaScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.CONTAINER) { entry ->
        val id = entry.arguments?.getString("id") ?: return@composable
        ContainerScreen(vm = vm, projectId = id, onBack = { nav.popBackStack() })
    }
    composable(Routes.AGENT) {
        AgentScreen(vm = vm, onBack = { nav.popBackStack() })
    }
}

/** Bottom bar with the four tabs split 2 + 2 around a prominent central agent button. */
@Composable
private fun AgentBottomBar(
    currentRoute: String?,
    lang: String,
    onTab: (String) -> Unit,
    onAgent: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        // Pad above the phone's system navigation/gesture bar so this row isn't covered by it
        // (the stock NavigationBar did this automatically; our custom bar must do it explicitly).
        Box(Modifier.fillMaxWidth().navigationBarsPadding().height(70.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(70.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BarTab(TABS[0], currentRoute, lang, onTab)
                    BarTab(TABS[1], currentRoute, lang, onTab)
                }
                Spacer(Modifier.width(64.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    BarTab(TABS[2], currentRoute, lang, onTab)
                    BarTab(TABS[3], currentRoute, lang, onTab)
                }
            }
            FloatingActionButton(
                onClick = onAgent,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.Center).size(58.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = tx(lang, "智能体", "Agent"), modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun RowScope.BarTab(tab: Tab, currentRoute: String?, lang: String, onTab: (String) -> Unit) {
    val selected = currentRoute == tab.route
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.clickable { onTab(tab.route) }.padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(if (lang == "en") tab.en else tab.zh, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
