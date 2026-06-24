/*
 * Expandable per-algorithm advanced options for the Hide screen, with a plain-language explanation
 * of each knob. Matches the Swing plugin option panels (LSB max-bits, Adaptive CMD/mu, JpegUniward
 * quality). Sliders are keyboard-operable (arrow keys) and the header opens with Enter/Space.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AdvancedOptions
import com.elcruncharino.neostego.compose.engine.OptionsKind
import kotlin.math.roundToInt

@Composable
fun AdvancedOptionsPanel(kind: OptionsKind, options: AdvancedOptions, onChange: (AdvancedOptions) -> Unit) {
    if (kind == OptionsKind.NONE) return
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced options", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse advanced options" else "Expand advanced options",
                )
            }
        }

        if (expanded) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (kind) {
                        OptionsKind.LSB -> SliderOption(
                            "Maximum bits per color channel", options.maxBitsPerChannel.toString(),
                            options.maxBitsPerChannel.toFloat(), 1f..8f, 6,
                            "How many least-significant bits of each colour channel to overwrite. Higher hides more " +
                                "data but distorts the image more and is easier to detect. Default 3.",
                        ) { onChange(options.copy(maxBitsPerChannel = it.roundToInt())) }

                        OptionsKind.ADAPTIVE -> {
                            ToggleOption(
                                "Cluster changes (CMD)", options.cmd,
                                "Groups the +/-1 pixel changes so they reinforce each other, which resists statistical " +
                                    "steganalysis. Default on.",
                            ) { onChange(options.copy(cmd = it)) }
                            if (options.cmd) {
                                SliderOption(
                                    "Clustering strength (mu)", formatMu(options.cmdMu),
                                    options.cmdMu.toFloat(), 1f..9f, 15,
                                    "How strongly the changes cluster together. Higher concentrates them more. Default 3.0.",
                                ) { onChange(options.copy(cmdMu = Math.round(it * 2.0) / 2.0)) }
                            }
                        }

                        OptionsKind.JPEG -> SliderOption(
                            "JPEG quality", options.quality.toString(),
                            options.quality.toFloat(), 50f..100f, 49,
                            "Quality of the output JPEG (50-100). Higher means better image quality and more capacity, " +
                                "but a larger file. Default 90.",
                        ) { onChange(options.copy(quality = it.roundToInt())) }

                        OptionsKind.NONE -> Unit
                    }
                }
            }
        }
    }
}

private fun formatMu(v: Double): String = if (v == v.toLong().toDouble()) "${v.toLong()}.0" else v.toString()

@Composable
private fun SliderOption(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    explanation: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleOption(label: String, checked: Boolean, explanation: String, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
