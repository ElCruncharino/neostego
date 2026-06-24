/*
 * Desktop port of the Android Compose design-system components. Pure-Compose,
 * accessibility-first (semantics/contentDescription). Platform-specific bits (file picking) are
 * passed in from the screen. These are the building blocks shared visual-language-wise with Android;
 * a later phase can hoist the portable ones into a common Multiplatform module.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** App background: a soft vertical gradient on the surface, like the Android GradientBackground. */
@Composable
fun GradientBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(c.surface, c.surfaceVariant.copy(alpha = 0.35f))),
        ),
        content = content,
    )
}

/** A small section header (sidebar groups, screen sections). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A file input card: label + current selection + Choose/Change. Mirrors the Android FilePickCard,
 * including its merged semantics so a screen reader announces the card as one unit.
 */
@Composable
fun FilePickCard(label: String, chosen: String?, hint: String, onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$label. ${chosen ?: hint}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    chosen ?: hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.semantics {
                    contentDescription = (if (chosen == null) "Choose " else "Change ") + label
                },
            ) { Text(if (chosen == null) "Choose" else "Change") }
        }
    }
}

/**
 * A connected segmented selector — the desktop copy of the Android SegmentedButtonGroup. Each
 * segment is a ≥44dp selectable exposing the radio role.
 */
@Composable
fun SegmentedButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp).selectableGroup()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    animationSpec = tween(220),
                    label = "segBg",
                )
                val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize()) {}
                    Text(
                        label,
                        color = fg,
                        textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Password field with a Show/Hide toggle, on a card like the Android SecurePasswordField.
 * (Desktop note: Compose holds the value as a String; a later phase can swap in a char[]-backed
 * field to match Android's wipe-after-use behaviour.)
 */
@Composable
fun SecurePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    show: Boolean,
    onToggleShow: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Password (optional)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleShow) { Text(if (show) "Hide" else "Show") }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password, optional" },
            )
        }
    }
}

/** Full-width primary action with an inline progress bar while busy — like Android PrimaryActionButton. */
@Composable
fun PrimaryActionButton(label: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }
        Button(
            onClick = onClick,
            enabled = !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (busy) "Working…" else label) }
    }
}

/** Outcome card shared by the action screens: green on success, error-coloured on failure. */
@Composable
fun ResultCard(result: Result<String>, successMessage: (String) -> String) {
    val ok = result.isSuccess
    val container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (ok) "Done" else "Failed", fontWeight = FontWeight.SemiBold, color = content)
            Text(
                result.fold(successMessage, { it.message ?: it.toString() }),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}
