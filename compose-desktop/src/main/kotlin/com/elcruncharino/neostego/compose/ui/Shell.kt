/*
 * The desktop app shell: a left sidebar (the familiar Swing information architecture — Data hiding /
 * Digital watermarking groups) plus the content area. Keeps existing users oriented while wearing the
 * modern Material 3 / Android-brand skin.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import com.elcruncharino.neostego.compose.ui.screens.ExtractScreen
import com.elcruncharino.neostego.compose.ui.screens.HideScreen

enum class Destination(val title: String, val section: String, val icon: ImageVector) {
    HIDE("Hide data", "Data hiding", Icons.Filled.Lock),
    EXTRACT("Extract data", "Data hiding", Icons.Filled.LockOpen),
    GENERATE_SIGNATURE("Generate signature", "Digital watermarking", Icons.Filled.VpnKey),
    EMBED_WATERMARK("Embed watermark", "Digital watermarking", Icons.Filled.Verified),
    VERIFY_WATERMARK("Verify watermark", "Digital watermarking", Icons.Filled.Shield),
    SETTINGS("Settings", "", Icons.Filled.Settings),
}

@Composable
fun AppShell(algorithms: List<AlgoInfo>, dark: Boolean, onToggleDark: () -> Unit) {
    var dest by remember { mutableStateOf(Destination.HIDE) }
    Row(Modifier.fillMaxSize()) {
        Sidebar(selected = dest, onSelect = { dest = it }, dark = dark, onToggleDark = onToggleDark)
        GradientBackground(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(dest.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                when (dest) {
                    Destination.HIDE -> HideScreen(algorithms)
                    Destination.EXTRACT -> ExtractScreen()
                    else -> PlaceholderScreen(dest.title)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit, dark: Boolean, onToggleDark: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxHeight().width(248.dp),
    ) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("NeoStego", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleDark) {
                    Icon(
                        if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme",
                    )
                }
            }
            val grouped = Destination.entries.filter { it.section.isNotEmpty() }.groupBy { it.section }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                grouped.forEach { (section, items) ->
                    SectionLabel(section, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
                    items.forEach { d -> NavItem(d, selected == d) { onSelect(d) } }
                }
            }
            Spacer(Modifier.fillMaxWidth())
            NavItem(Destination.SETTINGS, selected == Destination.SETTINGS) { onSelect(Destination.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(dest: Destination, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
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
