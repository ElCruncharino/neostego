/*
 * Settings: theme mode (System/Light/Dark, persisted) and an About section (version, license,
 * credits, homepage, and the accessibility note pointing screen-reader users to the classic UI/CLI).
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.appVersion
import com.elcruncharino.neostego.compose.theme.ThemeMode
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup
import java.awt.Desktop
import java.net.URI

private const val HOMEPAGE = "https://github.com/ElCruncharino/neostego"

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Theme")
        SegmentedButtonGroup(
            options = listOf("System", "Light", "Dark"),
            selectedIndex = themeMode.ordinal,
            onSelect = { onThemeChange(ThemeMode.entries[it]) },
        )

        SectionLabel("About")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text("NeoStego ${appVersion()}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text("Hide data inside images and audio, and watermark your files.", style = MaterialTheme.typography.bodyMedium, color = muted)
                Text("A modernized fork of OpenStego by Samir Vaidya. Maintained by Nick Haghiri.", style = MaterialTheme.typography.bodySmall, color = muted)
                Text("Licensed under the GNU General Public License v2.", style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(onClick = { openUrl(HOMEPAGE) }) { Text(HOMEPAGE) }
                Text(
                    "Using a screen reader on Linux? The classic (Swing) UI and the command-line interface are fully accessible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
    }
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
