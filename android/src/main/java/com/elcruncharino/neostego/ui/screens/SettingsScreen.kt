/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.screens

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.elcruncharino.neostego.R
import com.elcruncharino.neostego.data.ThemeMode
import com.elcruncharino.neostego.ui.AppState
import com.elcruncharino.neostego.ui.components.SegmentedButtonGroup

/** Language tags offered by the in-app language switcher, in display order (System first). */
private val LANGUAGE_TAGS = listOf(null, "en", "zh", "ja")

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
            stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.label_theme), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                SegmentedButtonGroup(
                    options = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark),
                    ),
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

        Card(shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.label_language), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                var appLocaleTag by remember {
                    mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() })
                }
                var expanded by remember { mutableStateOf(false) }
                // A dropdown rather than a segmented row: this list only grows as more languages
                // are translated, and a row of segments doesn't scale past a handful of options.
                val labels = listOf(stringResource(R.string.theme_system), "English", "中文", "日本語")
                val shape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .clickable { expanded = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels[LANGUAGE_TAGS.indexOf(appLocaleTag).coerceAtLeast(0)], modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        labels.forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                expanded = false
                                val tag = LANGUAGE_TAGS[index]
                                appLocaleTag = tag
                                // MainActivity is an AppCompatActivity, so this recreates it
                                // automatically to apply the change; a plain ComponentActivity
                                // would silently no-op here instead.
                                AppCompatDelegate.setApplicationLocales(
                                    if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                                )
                            })
                        }
                    }
                }
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
                        Text(stringResource(R.string.label_dynamic_color), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.hint_dynamic_color),
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
                Text(stringResource(R.string.label_accent_color), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.hint_accent_color),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // "Default" swatch clears the manual seed.
                    SeedSwatch(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        selected = prefs.seedColorArgb == null,
                        label = stringResource(R.string.label_default_color),
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
