package com.aerion.chefslist.ui.util

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Edit-mode affordance: when [active], the element gently **jiggles** (a small continuous
 * wobble) and gains a faint rounded **outline**, and a tap invokes [onClick] to open its
 * editor sheet. When inactive this is a complete no-op, so the read-only view is unchanged.
 *
 * [phase] staggers the wobble between items (pass the item index) so the list looks organic
 * rather than every row rocking in lockstep.
 */
@Composable
fun Modifier.editable(active: Boolean, phase: Int = 0, onClick: () -> Unit): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 160, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset((phase % 6) * 45),
        ),
        label = "angle",
    )
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    return this
        .graphicsLayer { rotationZ = angle }
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, outline, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
}
