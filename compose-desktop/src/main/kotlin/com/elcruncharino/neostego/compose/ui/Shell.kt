/*
 * The desktop app shell: a left sidebar (the familiar Swing information architecture — Data hiding /
 * Digital watermarking groups) plus the content area. Keeps existing users oriented while wearing the
 * modern Material 3 / Android-brand skin.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import com.elcruncharino.neostego.compose.theme.ThemeMode
import com.elcruncharino.neostego.compose.ui.screens.EmbedWatermarkScreen
import com.elcruncharino.neostego.compose.ui.screens.ExtractScreen
import com.elcruncharino.neostego.compose.ui.screens.GenerateSignatureScreen
import com.elcruncharino.neostego.compose.ui.screens.HideScreen
import com.elcruncharino.neostego.compose.ui.screens.SettingsScreen
import com.elcruncharino.neostego.compose.ui.screens.VerifyWatermarkScreen
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.app_name
import openstego.compose_desktop.generated.resources.destination_embed_watermark
import openstego.compose_desktop.generated.resources.destination_extract
import openstego.compose_desktop.generated.resources.destination_generate_signature
import openstego.compose_desktop.generated.resources.destination_hide
import openstego.compose_desktop.generated.resources.destination_settings
import openstego.compose_desktop.generated.resources.destination_verify_watermark
import openstego.compose_desktop.generated.resources.section_data_hiding
import openstego.compose_desktop.generated.resources.section_digital_watermarking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class Destination(val titleRes: StringResource, val sectionRes: StringResource?, val icon: ImageVector) {
    HIDE(Res.string.destination_hide, Res.string.section_data_hiding, Icons.Filled.Lock),
    EXTRACT(Res.string.destination_extract, Res.string.section_data_hiding, Icons.Filled.LockOpen),
    GENERATE_SIGNATURE(Res.string.destination_generate_signature, Res.string.section_digital_watermarking, Icons.Filled.VpnKey),
    EMBED_WATERMARK(Res.string.destination_embed_watermark, Res.string.section_digital_watermarking, Icons.Filled.Verified),
    VERIFY_WATERMARK(Res.string.destination_verify_watermark, Res.string.section_digital_watermarking, Icons.Filled.Shield),
    SETTINGS(Res.string.destination_settings, null, Icons.Filled.Settings),
}

@Composable
fun AppShell(
    dhAlgorithms: List<AlgoInfo>,
    wmAlgorithms: List<AlgoInfo>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    languageMode: String,
    onLanguageChange: (String) -> Unit,
    dest: Destination,
    onSelect: (Destination) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Sidebar(selected = dest, onSelect = onSelect)
        GradientBackground(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    stringResource(dest.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                when (dest) {
                    Destination.HIDE -> HideScreen(dhAlgorithms)
                    Destination.EXTRACT -> ExtractScreen()
                    Destination.GENERATE_SIGNATURE -> GenerateSignatureScreen(wmAlgorithms)
                    Destination.EMBED_WATERMARK -> EmbedWatermarkScreen(wmAlgorithms)
                    Destination.VERIFY_WATERMARK -> VerifyWatermarkScreen(wmAlgorithms)
                    Destination.SETTINGS -> SettingsScreen(themeMode, onThemeChange, languageMode, onLanguageChange)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit) {
    val firstFocus = remember { FocusRequester() }
    // Land focus on the first nav item at startup so keyboard users have a clear starting point.
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxHeight().width(248.dp).semantics { isTraversalGroup = true },
    ) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Image(painterResource("neostego.png"), contentDescription = null, modifier = Modifier.size(28.dp))
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val grouped = Destination.entries.filter { it.sectionRes != null }.groupBy { it.sectionRes!! }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                grouped.forEach { (sectionRes, items) ->
                    SectionLabel(stringResource(sectionRes), modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
                    items.forEach { d ->
                        NavItem(
                            d,
                            selected == d,
                            modifier = if (d == Destination.HIDE) Modifier.focusRequester(firstFocus) else Modifier,
                        ) { onSelect(d) }
                    }
                }
            }
            Spacer(Modifier.fillMaxWidth())
            NavItem(Destination.SETTINGS, selected == Destination.SETTINGS) { onSelect(Destination.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(dest: Destination, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        shape = shape,
        color = bg,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            // Visible focus ring for keyboard navigation (Material's default indication is subtle here).
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(dest.icon, contentDescription = null, tint = fg)
            Text(stringResource(dest.titleRes), color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
