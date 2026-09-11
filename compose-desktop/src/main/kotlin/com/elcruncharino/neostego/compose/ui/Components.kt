/*
 * Desktop port of the Android Compose design-system components. Pure-Compose,
 * accessibility-first (semantics/contentDescription). Platform-specific bits (file picking) are
 * passed in from the screen. These are the building blocks shared visual-language-wise with Android;
 * a later phase can hoist the portable ones into a common Multiplatform module.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.pickFiles
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.action_button_state_ready
import openstego.compose_desktop.generated.resources.action_button_state_working
import openstego.compose_desktop.generated.resources.action_button_working
import openstego.compose_desktop.generated.resources.action_button_working_progress
import openstego.compose_desktop.generated.resources.file_pick_change
import openstego.compose_desktop.generated.resources.file_pick_change_content_description
import openstego.compose_desktop.generated.resources.file_pick_choose
import openstego.compose_desktop.generated.resources.file_pick_choose_content_description
import openstego.compose_desktop.generated.resources.file_pick_drop_hint
import openstego.compose_desktop.generated.resources.password_field_default_label
import openstego.compose_desktop.generated.resources.password_field_hide
import openstego.compose_desktop.generated.resources.password_field_show
import openstego.compose_desktop.generated.resources.result_card_done
import openstego.compose_desktop.generated.resources.result_card_failed
import openstego.compose_desktop.generated.resources.selection_summary
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.math.roundToInt

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
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun FilePickCard(label: String, chosen: String?, hint: String, onFileDropped: ((String) -> Unit)? = null, onPick: () -> Unit) {
    // Highlight the whole card while a file is dragged over it, so the entire card reads as the drop zone.
    var dragOver by remember { mutableStateOf(false) }
    val dndModifier = if (onFileDropped != null) {
        val target = remember(onFileDropped) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    dragOver = true
                }
                override fun onExited(event: DragAndDropEvent) {
                    dragOver = false
                }
                override fun onEnded(event: DragAndDropEvent) {
                    dragOver = false
                }
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dragOver = false
                    return runCatching {
                        val transfer = event.awtTransferable
                        if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            @Suppress("UNCHECKED_CAST")
                            val files = transfer.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            files.firstOrNull()?.let { onFileDropped(it.absolutePath) }
                            files.isNotEmpty()
                        } else {
                            false
                        }
                    }.getOrDefault(false)
                }
            }
        }
        // fillMaxWidth + the target on the Card itself makes the entire card the active drop region.
        Modifier
            .fillMaxWidth()
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
    } else {
        Modifier.fillMaxWidth()
    }
    val borderShape = RoundedCornerShape(12.dp)
    // The caller phrases [hint] as a natural call-to-action (e.g. "Choose or drag … here"); we only
    // swap in a live "drop now" prompt while a file is actually hovering over the card.
    val canDrop = onFileDropped != null
    val subtitle = when {
        chosen != null -> chosen
        canDrop && dragOver -> stringResource(Res.string.file_pick_drop_hint)
        else -> hint
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = dndModifier.then(
            if (dragOver) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, borderShape) else Modifier,
        ),
    ) {
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
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canDrop && dragOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pickContentDescription = if (chosen == null) {
                stringResource(Res.string.file_pick_choose_content_description, label)
            } else {
                stringResource(Res.string.file_pick_change_content_description, label)
            }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.semantics {
                    contentDescription = pickContentDescription
                },
            ) { Text(if (chosen == null) stringResource(Res.string.file_pick_choose) else stringResource(Res.string.file_pick_change)) }
        }
    }
}

/** A connected segmented selector, backed by Material 3's stable segmented button row. */
@Composable
fun SegmentedButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
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
    label: String = stringResource(Res.string.password_field_default_label),
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onToggleShow) { Text(if (show) stringResource(Res.string.password_field_hide) else stringResource(Res.string.password_field_show)) }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

/**
 * Full-width primary action with an inline progress bar while busy. [progress] (0..1) shows a
 * determinate bar with a percentage; null shows an indeterminate bar.
 */
@Composable
fun PrimaryActionButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val workingState = stringResource(Res.string.action_button_state_working)
    val readyState = stringResource(Res.string.action_button_state_ready)
    Column(
        // Announce the busy state to screen readers when it changes.
        modifier = modifier.fillMaxWidth().semantics { stateDescription = if (busy) workingState else readyState },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
        Button(
            onClick = onClick,
            enabled = !busy,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            val busyText = progress?.let { stringResource(Res.string.action_button_working_progress, (it * 100).roundToInt()) }
                ?: stringResource(Res.string.action_button_working)
            Text(if (busy) busyText else label)
        }
    }
}

/** Outcome card shared by the action screens: green on success, error-coloured on failure. */
@Composable
fun ResultCard(result: Result<String>, successMessage: (String) -> String) {
    val ok = result.isSuccess
    val container = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        // Announce the outcome to screen readers as soon as it appears.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (ok) stringResource(Res.string.result_card_done) else stringResource(Res.string.result_card_failed), fontWeight = FontWeight.SemiBold, color = content)
            Text(
                result.fold(successMessage, { it.message ?: it.toString() }),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

/**
 * Keyboard-navigable combobox (open with Enter/Space/Down, arrow through the options, Enter to
 * choose, Esc to close) showing [displayText] with [options] in its dropdown. Built from a focusable
 * anchor + DropdownMenu rather than ExposedDropdownMenu, which isn't keyboard-operable.
 */
@Composable
fun KeyboardDropdown(
    displayText: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    contentDescription: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val scheme = MaterialTheme.colorScheme
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
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(displayText, modifier = Modifier.weight(1f), color = scheme.onSurface)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = scheme.onSurfaceVariant)
        // The menu anchors here; when open it takes focus, so arrows/Enter/Esc work on the items.
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelect(index)
                    open = false
                })
            }
        }
    }
}

/**
 * Card for picking a variable number of files at once (multiple covers for batch/split embedding,
 * or the parts of a split reveal): a label, a summary of the current selection (or [emptyHint] when
 * none is chosen yet), and a button that opens a multi-select file picker restricted to [extensions].
 */
@Composable
fun MultiFilePickCard(
    label: String,
    emptyHint: String,
    chooseLabel: String,
    changeLabel: String,
    filterLabel: String,
    files: List<String>,
    onChange: (List<String>) -> Unit,
    extensions: List<String> = emptyList(),
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                if (files.isEmpty()) {
                    emptyHint
                } else {
                    stringResource(Res.string.selection_summary, files.size, files.joinToString(", ") { it.substringAfterLast('/') })
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                val picked = pickFiles(extensions = extensions, filterLabel = filterLabel)
                if (picked.isNotEmpty()) onChange(picked)
            }) { Text(if (files.isEmpty()) chooseLabel else changeLabel) }
        }
    }
}
