package com.thumbshade.app.overlay

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import com.thumbshade.app.data.ShadeAnim

/**
 * Open/close animations shared by the shade and the floating button. Every style is a function
 * of one progress value, so the same list works for both.
 */
object Anims {
    private val bouncy = setOf(ShadeAnim.BOUNCE, ShadeAnim.POP, ShadeAnim.ELASTIC, ShadeAnim.WOBBLE, ShadeAnim.JELLY, ShadeAnim.SLINGSHOT)

    /** 0 = hidden, 1 = shown; springy styles overshoot past 1 on the way in. */
    @Composable
    fun AnimatedVisibilityScope.progress(anim: ShadeAnim): State<Float> = transition.animateFloat(
        transitionSpec = {
            val entering = targetState == EnterExitState.Visible
            when {
                anim == ShadeAnim.NONE -> snap()
                entering && anim in bouncy -> spring(
                    dampingRatio = if (anim == ShadeAnim.WOBBLE || anim == ShadeAnim.ELASTIC) 0.3f else 0.5f,
                    stiffness = Spring.StiffnessMediumLow,
                )
                entering -> tween(320, easing = FastOutSlowInEasing)
                else -> tween(240, easing = FastOutSlowInEasing)
            }
        },
        label = "anim",
    ) { if (it == EnterExitState.Visible) 1f else 0f }

    /**
     * Applies [anim] at progress [p]. [side] is where the thing travels from: 0 from below
     * (the shade), -1 from the left edge, 1 from the right edge (the button).
     */
    fun GraphicsLayerScope.apply(anim: ShadeAnim, p: Float, side: Int) {
        val q = 1f - p
        val w = size.width
        val h = size.height
        val fade = p.coerceIn(0f, 1f)
        fun slide(amount: Float) {
            if (side == 0) translationY = amount * h else translationX = side * amount * w * 1.2f
        }
        when (anim) {
            ShadeAnim.NONE -> Unit
            ShadeAnim.SLIDE, ShadeAnim.BOUNCE -> { slide(q); alpha = (p * 2f).coerceIn(0f, 1f) }
            ShadeAnim.FADE -> alpha = fade
            ShadeAnim.SCALE -> { transformOrigin = TransformOrigin(0.5f, 1f); scaleX = 0.8f + 0.2f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.EXPAND -> { transformOrigin = TransformOrigin(0.5f, 1f); scaleY = p.coerceAtLeast(0f); alpha = fade }
            ShadeAnim.DROP -> { translationY = -q * h / 3f; alpha = fade }
            ShadeAnim.ZOOM -> { transformOrigin = TransformOrigin(0.5f, 1f); scaleX = 0.3f + 0.7f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.POP -> { scaleX = 0.6f + 0.4f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.ELASTIC -> { slide(q / 2f); alpha = fade }
            ShadeAnim.GLIDE -> { translationX = q * w; alpha = fade }
            ShadeAnim.GLIDE_LEFT -> { translationX = -q * w; alpha = fade }
            ShadeAnim.RISE -> { slide(q * 0.25f); alpha = fade }
            ShadeAnim.FALL -> { translationY = -q * h; alpha = fade }
            ShadeAnim.FLIP -> { transformOrigin = TransformOrigin(0.5f, 1f); rotationX = -90f * q; alpha = fade }
            ShadeAnim.FLIP_SIDE -> { rotationY = 90f * q; alpha = fade }
            ShadeAnim.PAPER -> { transformOrigin = TransformOrigin(0.5f, 0f); rotationX = 90f * q; alpha = fade }
            ShadeAnim.DOOR -> { transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -90f * q; alpha = fade }
            ShadeAnim.DOOR_RIGHT -> { transformOrigin = TransformOrigin(1f, 0.5f); rotationY = 90f * q; alpha = fade }
            ShadeAnim.SWING -> { transformOrigin = TransformOrigin(0f, 1f); rotationZ = -30f * q; alpha = fade }
            ShadeAnim.SWING_RIGHT -> { transformOrigin = TransformOrigin(1f, 1f); rotationZ = 30f * q; alpha = fade }
            ShadeAnim.SPIN -> { rotationZ = 360f * q; scaleX = fade; scaleY = fade; alpha = fade }
            ShadeAnim.TWIRL -> { rotationZ = 90f * q; scaleX = 0.5f + 0.5f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.TUMBLE -> { rotationZ = 180f * q; slide(q); alpha = fade }
            ShadeAnim.CURTAIN -> { scaleX = p.coerceAtLeast(0f); alpha = fade }
            ShadeAnim.BLINDS -> { scaleY = p.coerceAtLeast(0f); alpha = fade }
            ShadeAnim.CORNER_LEFT -> { transformOrigin = TransformOrigin(0f, 1f); scaleX = fade; scaleY = fade; alpha = fade }
            ShadeAnim.CORNER_RIGHT -> { transformOrigin = TransformOrigin(1f, 1f); scaleX = fade; scaleY = fade; alpha = fade }
            ShadeAnim.TILT -> { rotationX = 35f * q; slide(q * 0.3f); alpha = fade }
            ShadeAnim.SWOOP -> { translationX = (if (side == 0) -1 else side) * q * w * 0.6f; translationY = q * h * 0.4f; rotationZ = -15f * q; alpha = fade }
            ShadeAnim.SQUASH -> { transformOrigin = TransformOrigin(0.5f, 1f); scaleX = 1f + 0.4f * q; scaleY = p.coerceAtLeast(0f); alpha = fade }
            ShadeAnim.STRETCH -> { scaleY = 1f + 0.6f * q; scaleX = p.coerceAtLeast(0f); alpha = fade }
            ShadeAnim.WOBBLE -> { rotationZ = 12f * q; scaleX = 0.8f + 0.2f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.JELLY -> { transformOrigin = TransformOrigin(0.5f, 1f); scaleX = 0.5f + 0.5f * p; scaleY = 1.5f - 0.5f * p; alpha = fade }
            ShadeAnim.SLINGSHOT -> { slide(q); scaleY = 1f + 0.3f * q; alpha = fade }
            ShadeAnim.GROW -> { scaleX = p.coerceAtLeast(0f); scaleY = scaleX; alpha = fade }
            ShadeAnim.SHRINK -> { scaleX = 1.4f - 0.4f * p; scaleY = scaleX; alpha = fade }
            ShadeAnim.DEAL -> { translationX = (if (side == 0) 1 else side) * q * w * 0.5f; rotationZ = 10f * q; alpha = fade }
        }
        cameraDistance = 16f * density
    }
}
