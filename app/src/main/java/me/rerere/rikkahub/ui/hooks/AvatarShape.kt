package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    val avatarStyle = me.rerere.rikkahub.ui.custom.uiComponentStyle("avatar")
    if (avatarStyle?.cornerDp != null) {
        return me.rerere.rikkahub.ui.custom.uiCornerShape(avatarStyle.cornerDp.dp)
    }
    if (me.rerere.rikkahub.ui.custom.isUICutCornerShape()) {
        return androidx.compose.material3.MaterialTheme.shapes.small
    }
    if (!loading || me.rerere.rikkahub.ui.custom.hasUICustomShapes()) {
        return CircleShape
    }
    val infiniteTransition = rememberInfiniteTransition()
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        )
    )
    return MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
}
