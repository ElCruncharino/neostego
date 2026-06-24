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
import androidx.compose.foundation.layout.Box
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

enum class Destination(val title: String, val section: String, val icon: ImageVector) {
    HIDE("Hide data", "Data hiding", Icons.Filled.Lock),
    EXTRACT("Extract data", "Data hiding", Icons.Filled.LockOpen),
    GENERATE_SIGNATURE("Generate signature", "Digital watermarking", Icons.Filled.VpnKey),
    EMBED_WATERMARK("Embed watermark", "Digital watermarking", Icons.Filled.Verified),
    VERIFY_WATERMARK("Verify watermark", "Digital watermarking", Icons.Filled.Shield),
    SETTINGS("Settings", "", Icons.Filled.Settings),
}

@Composable
fun AppShell(
    dhAlgorithms: List<AlgoInfo>,
    wmAlgorithms: List<AlgoInfo>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
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
                    dest.title,
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
                    Destination.SETTINGS -> SettingsScreen(themeMode, onThemeChange)
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
                Text("NeoStego", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val grouped = Destination.entries.filter { it.section.isNotEmpty() }.groupBy { it.section }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                grouped.forEach { (section, items) ->
                    SectionLabel(section, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
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
            Text(dest.title, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$title — coming soon", style = MaterialTheme.typography.titleMedium)
            Text(
                "Being ported from the Swing UI in this module.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
