package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App-wide top bar: fixed height + tinted background.
 * windowInsets = WindowInsets(0) prevents the bar from adding extra status-bar
 * top-padding internally (Scaffold already handles insets at the outer level),
 * which keeps the title vertically centered at any bar height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = 48.dp,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        scrolledContainerColor = MaterialTheme.colorScheme.primaryContainer,
        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = expandedHeight,
        colors = colors,
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0),
    )
}
