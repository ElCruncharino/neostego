/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.data.ThemeMode
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.SegmentedButtonGroup

/** Preset seed colours offered in the palette picker. */
private val SEED_PRESETS = listOf(
    0xFF2E6DF6.toInt(), // blue (default brand)
    0xFF7C4DFF.toInt(), // violet
    0xFF00897B.toInt(), // teal
    0xFF2E7D32.toInt(), // green
    0xFFF9A825.toInt(), // amber
    0xFFE5393B.toInt(), // red
    0xFFD81B60.toInt(), // pink
    0xFF5D4037.toInt(), // brown
)

@Composable
fun SettingsScreen(appState: AppState) {
    val prefs = appState.themePrefs

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Personalize the look. Choices are saved on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Theme", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                SegmentedButtonGroup(
                    options = listOf("System", "Light", "Dark"),
                    selectedIndex = when (prefs.themeMode) {
                        ThemeMode.SYSTEM -> 0
                        ThemeMode.LIGHT -> 1
                        ThemeMode.DARK -> 2
                    },
                    onSelect = {
                        prefs.updateThemeMode(
                            when (it) {
                                0 -> ThemeMode.SYSTEM
                                1 -> ThemeMode.LIGHT
                                else -> ThemeMode.DARK
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic colour", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Recolour from your wallpaper. Turn off to use a fixed colour below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = prefs.useDynamicColor, onCheckedChange = { prefs.updateDynamicColor(it) })
                }
            }
        }

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Accent colour", fontWeight = FontWeight.SemiBold)
                Text(
                    "Pick a fixed accent colour for the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // "Default" swatch clears the manual seed.
                    SeedSwatch(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        selected = prefs.seedColorArgb == null,
                        label = "Default",
                        onClick = { prefs.updateSeedColor(null) },
                    )
                    SEED_PRESETS.forEach { argb ->
                        SeedSwatch(
                            color = Color(argb),
                            selected = prefs.seedColorArgb == argb,
                            onClick = { prefs.updateSeedColor(argb) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeedSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
) {
    val border = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .border(width = if (selected) 3.dp else 1.dp, color = border, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        } else if (label != null) {
            Text(label.take(1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
