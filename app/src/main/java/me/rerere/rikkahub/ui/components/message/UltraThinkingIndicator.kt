package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun UltraThinkingIndicator(loading: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val brush = remember(primary, tertiary) { Brush.linearGradient(listOf(primary, tertiary)) }
    if (!loading) {
        Box(modifier.size(10.dp).background(brush, CircleShape))
        return
    }
    val transition = rememberInfiniteTransition(label = "ultra-thinking")
    val alpha = transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ultra-alpha"
    )
    Box(
        modifier.size(10.dp).graphicsLayer { this.alpha = alpha.value }
            .background(brush, CircleShape)
    )
}
