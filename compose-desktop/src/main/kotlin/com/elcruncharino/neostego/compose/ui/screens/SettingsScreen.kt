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
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.about_accessibility_note
import openstego.compose_desktop.generated.resources.about_app_name_version
import openstego.compose_desktop.generated.resources.about_fork_credit
import openstego.compose_desktop.generated.resources.about_license
import openstego.compose_desktop.generated.resources.about_tagline
import openstego.compose_desktop.generated.resources.section_about
import openstego.compose_desktop.generated.resources.section_theme
import openstego.compose_desktop.generated.resources.theme_dark
import openstego.compose_desktop.generated.resources.theme_light
import openstego.compose_desktop.generated.resources.theme_system
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.net.URI

private const val HOMEPAGE = "https://github.com/ElCruncharino/neostego"

@Composable
fun SettingsScreen(themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(Res.string.section_theme))
        SegmentedButtonGroup(
            options = listOf(stringResource(Res.string.theme_system), stringResource(Res.string.theme_light), stringResource(Res.string.theme_dark)),
            selectedIndex = themeMode.ordinal,
            onSelect = { onThemeChange(ThemeMode.entries[it]) },
        )

        SectionLabel(stringResource(Res.string.section_about))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(Res.string.about_app_name_version, appVersion()), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = muted)
                Text(stringResource(Res.string.about_fork_credit), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(stringResource(Res.string.about_license), style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(onClick = { openUrl(HOMEPAGE) }) { Text(HOMEPAGE) }
                Text(
                    stringResource(Res.string.about_accessibility_note),
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
