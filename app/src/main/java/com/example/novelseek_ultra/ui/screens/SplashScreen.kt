package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onReady: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(2200)
        onReady()
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "splash_alpha",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "sapling")
    val sway by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.graphicsLayer(alpha = alpha),
        ) {
            Canvas(modifier = Modifier.size(220.dp)) {
                drawBookWithSapling(sway)
            }
            Text(
                "NovelSeek Ultra",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF3D3D3D),
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp,
            )
            Text(
                "智能小说创作助手",
                fontSize = 13.sp,
                color = Color(0xFF9A9A9A),
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
            )
        }
    }
}

private fun DrawScope.drawBookWithSapling(swayDeg: Float) {
    val cx = size.width / 2f
    val cy = size.height * 0.60f

    // Shadow
    drawOval(
        color = Color(0x18000000),
        topLeft = androidx.compose.ui.geometry.Offset(cx - 75f, cy + 50f),
        size = androidx.compose.ui.geometry.Size(150f, 16f),
    )

    // Left page
    val leftPage = Path().apply {
        moveTo(cx - 5f, cy - 38f)
        cubicTo(cx - 28f, cy - 44f, cx - 68f, cy - 38f, cx - 80f, cy - 18f)
        lineTo(cx - 76f, cy + 48f)
        cubicTo(cx - 64f, cy + 54f, cx - 22f, cy + 48f, cx - 5f, cy + 44f)
        close()
    }
    drawPath(leftPage, Color(0xFFFBF7F0))
    drawPath(leftPage, Color(0xFFCEBFA4), style = Stroke(width = 1.5f))

    // Right page
    val rightPage = Path().apply {
        moveTo(cx + 5f, cy - 38f)
        cubicTo(cx + 28f, cy - 44f, cx + 68f, cy - 38f, cx + 80f, cy - 18f)
        lineTo(cx + 76f, cy + 48f)
        cubicTo(cx + 64f, cy + 54f, cx + 22f, cy + 48f, cx + 5f, cy + 44f)
        close()
    }
    drawPath(rightPage, Color(0xFFFBF7F0))
    drawPath(rightPage, Color(0xFFCEBFA4), style = Stroke(width = 1.5f))

    // Spine
    val spine = Path().apply {
        moveTo(cx - 5f, cy - 38f)
        cubicTo(cx - 1f, cy - 36f, cx + 1f, cy - 36f, cx + 5f, cy - 38f)
        lineTo(cx + 5f, cy + 44f)
        cubicTo(cx + 1f, cy + 46f, cx - 1f, cy + 46f, cx - 5f, cy + 44f)
        close()
    }
    drawPath(spine, Color(0xFFBCA98C))

    // Text lines — left page
    for (i in 0..4) {
        val ly = cy - 20f + i * 13f
        drawLine(
            color = Color(0xFFD8CABB),
            start = androidx.compose.ui.geometry.Offset(cx - 60f + i * 2f, ly),
            end = androidx.compose.ui.geometry.Offset(cx - 14f, ly),
            strokeWidth = 1f,
        )
    }
    // Text lines — right page
    for (i in 0..4) {
        val ly = cy - 20f + i * 13f
        drawLine(
            color = Color(0xFFD8CABB),
            start = androidx.compose.ui.geometry.Offset(cx + 14f, ly),
            end = androidx.compose.ui.geometry.Offset(cx + 60f - i * 2f, ly),
            strokeWidth = 1f,
        )
    }

    // Sapling — pivot at spine top, sways in the breeze
    val pivotX = cx
    val pivotY = cy - 38f
    withTransform({
        rotate(swayDeg, pivot = androidx.compose.ui.geometry.Offset(pivotX, pivotY))
    }) {
        // Trunk
        val trunk = Path().apply {
            moveTo(pivotX - 3.5f, pivotY)
            cubicTo(pivotX - 2f, pivotY - 20f, pivotX + 1f, pivotY - 40f, pivotX, pivotY - 62f)
            cubicTo(pivotX + 1f, pivotY - 40f, pivotX + 3f, pivotY - 20f, pivotX + 3.5f, pivotY)
            close()
        }
        drawPath(trunk, Color(0xFF8D6540))

        // Branch left
        val bleft = Path().apply {
            moveTo(pivotX - 1f, pivotY - 38f)
            cubicTo(pivotX - 10f, pivotY - 44f, pivotX - 24f, pivotY - 42f, pivotX - 28f, pivotY - 52f)
            cubicTo(pivotX - 23f, pivotY - 41f, pivotX - 10f, pivotY - 42f, pivotX - 1f, pivotY - 38f)
        }
        drawPath(bleft, Color(0xFF8D6540))

        // Branch right
        val bright = Path().apply {
            moveTo(pivotX + 1f, pivotY - 28f)
            cubicTo(pivotX + 12f, pivotY - 34f, pivotX + 26f, pivotY - 32f, pivotX + 30f, pivotY - 42f)
            cubicTo(pivotX + 25f, pivotY - 31f, pivotX + 12f, pivotY - 32f, pivotX + 1f, pivotY - 28f)
        }
        drawPath(bright, Color(0xFF8D6540))

        // Leaf cluster — top canopy (layered circles for depth)
        drawCircle(Color(0xFF6DB36A), radius = 24f,
            center = androidx.compose.ui.geometry.Offset(pivotX, pivotY - 84f))
        drawCircle(Color(0xFF82C47E), radius = 17f,
            center = androidx.compose.ui.geometry.Offset(pivotX - 13f, pivotY - 76f))
        drawCircle(Color(0xFF82C47E), radius = 15f,
            center = androidx.compose.ui.geometry.Offset(pivotX + 14f, pivotY - 74f))
        drawCircle(Color(0xFF52A34F), radius = 20f,
            center = androidx.compose.ui.geometry.Offset(pivotX, pivotY - 90f))
        // Bright highlight
        drawCircle(Color(0xFFA0D89D), radius = 8f,
            center = androidx.compose.ui.geometry.Offset(pivotX - 7f, pivotY - 96f))

        // Left branch leaves
        drawCircle(Color(0xFF6DB36A), radius = 14f,
            center = androidx.compose.ui.geometry.Offset(pivotX - 30f, pivotY - 58f))
        drawCircle(Color(0xFF52A34F), radius = 10f,
            center = androidx.compose.ui.geometry.Offset(pivotX - 34f, pivotY - 64f))

        // Right branch leaves
        drawCircle(Color(0xFF6DB36A), radius = 12f,
            center = androidx.compose.ui.geometry.Offset(pivotX + 32f, pivotY - 48f))
        drawCircle(Color(0xFF52A34F), radius = 9f,
            center = androidx.compose.ui.geometry.Offset(pivotX + 36f, pivotY - 54f))
    }
}
