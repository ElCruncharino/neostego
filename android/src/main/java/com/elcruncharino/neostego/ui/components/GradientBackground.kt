/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * A soft full-screen gradient drawn from the theme's primary/tertiary accents into the surface
 * colour. Because the accents track the cover-derived seed, the whole backdrop gently recolours when
 * a cover image is chosen. Colours are animated so theme/seed changes cross-fade rather than snap.
 */
@Composable
fun GradientBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val topColor by animateColorAsState(
        targetValue = scheme.primaryContainer.copy(alpha = 0.55f),
        animationSpec = tween(600),
        label = "gradientTop",
    )
    val midColor by animateColorAsState(
        targetValue = scheme.tertiaryContainer.copy(alpha = 0.30f),
        animationSpec = tween(600),
        label = "gradientMid",
    )
    val brush = Brush.linearGradient(
        colors = listOf(topColor, midColor, scheme.surface),
        start = Offset.Zero,
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )
    Box(modifier = modifier.fillMaxSize().background(scheme.surface).background(brush)) {
        content()
    }
}
