package org.updater.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Decorative shapes share one treatment across setup, results, and the browser. */
@Composable
internal fun ExpressiveBadge(
    icon: Int,
    shape: Shape,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier.size(size).clip(shape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Connected groups keep generous outside corners and small seams between rows. */
internal fun groupShape(index: Int, count: Int): Shape =
    RoundedCornerShape(
        topStart = if (index == 0) 28.dp else 6.dp,
        topEnd = if (index == 0) 28.dp else 6.dp,
        bottomStart = if (index == count - 1) 28.dp else 6.dp,
        bottomEnd = if (index == count - 1) 28.dp else 6.dp,
    )
