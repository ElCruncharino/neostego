/*
 * Shared algorithm picker: a keyboard-navigable combobox (open with Enter/Space/Down, arrow through
 * the options, Enter to choose, Esc to close) plus a card describing the selected algorithm. Built
 * from a focusable anchor + DropdownMenu rather than ExposedDropdownMenu, which isn't keyboard-
 * operable. Used by the Hide screen and all three watermarking screens.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo

@Composable
fun AlgorithmSelector(algorithms: List<AlgoInfo>, selected: AlgoInfo?, onSelect: (AlgoInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val scheme = MaterialTheme.colorScheme

    SectionLabel("Algorithm")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) scheme.primary else scheme.outline,
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null) { open = !open }
            // Keyboard: open the menu with Enter/Space/Down (the anchor is focusable via clickable).
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionDown -> {
                        open = true
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                role = Role.DropdownList
                contentDescription = "Algorithm: ${selected?.name ?: "none selected"}"
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(selected?.name.orEmpty(), modifier = Modifier.weight(1f), color = scheme.onSurface)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = scheme.onSurfaceVariant)
        // The menu anchors here; when open it takes focus, so arrows/Enter/Esc work on the items.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            algorithms.forEach { info ->
                DropdownMenuItem(text = { Text(info.name) }, onClick = { onSelect(info); open = false })
            }
        }
    }

    selected?.let { info ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.name, fontWeight = FontWeight.SemiBold)
                Text(
                    info.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
