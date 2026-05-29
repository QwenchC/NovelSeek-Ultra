package com.example.novelseek_ultra.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.example.novelseek_ultra.ui.theme.NovelSeekTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.novelseek_ultra.ui.screens.CharactersScreen
import com.example.novelseek_ultra.ui.screens.CultivationScreen
import com.example.novelseek_ultra.ui.screens.EditorScreen
import com.example.novelseek_ultra.ui.screens.ExportScreen
import com.example.novelseek_ultra.ui.screens.HomeScreen
import com.example.novelseek_ultra.ui.screens.LongNovelScreen
import com.example.novelseek_ultra.ui.screens.LongNovelsHomeScreen
import com.example.novelseek_ultra.ui.screens.OutlineScreen
import com.example.novelseek_ultra.ui.screens.ProjectScreen
import com.example.novelseek_ultra.ui.screens.SettingsScreen

private sealed class Tab(val route: String, val zh: String, val en: String, val icon: ImageVector) {
    data object Home : Tab("home", "短篇", "Short", Icons.Outlined.Book)
    data object LongHome : Tab("long_home", "长篇", "Long", Icons.Outlined.AutoStories)
    data object Settings : Tab("settings", "设置", "Settings", Icons.Outlined.Settings)
}

private val TABS = listOf(Tab.Home, Tab.LongHome, Tab.Settings)

object Routes {
    const val PROJECT = "project/{id}"
    const val OUTLINE = "outline/{id}"
    const val CHARACTERS = "characters/{id}"
    const val EXPORT = "export/{id}"
    const val EDITOR = "editor/{projectId}/{chapterId}"
    const val LONG_PROJECT = "long_project/{id}"
    const val LONG_OUTLINE = "long_outline/{id}"
    const val CULTIVATION = "cultivation/{id}"

    fun project(id: String) = "project/$id"
    fun outline(id: String) = "outline/$id"
    fun characters(id: String) = "characters/$id"
    fun export(id: String) = "export/$id"
    fun editor(pid: String, cid: String) = "editor/$pid/$cid"
    fun longProject(id: String) = "long_project/$id"
    fun longOutline(id: String) = "long_outline/$id"
    fun cultivation(id: String) = "cultivation/$id"
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
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                                || currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(if (lang == "en") tab.en else tab.zh) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Home.route,
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
}
