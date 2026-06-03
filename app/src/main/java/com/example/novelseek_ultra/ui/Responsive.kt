package com.example.novelseek_ultra.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Whether the screen is currently in landscape orientation.
 *
 * Used purely to opt screens into a wider, two-pane / multi-column layout. Portrait keeps its
 * original single-column layout untouched — every caller branches on this and leaves the
 * portrait code path exactly as it was.
 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
