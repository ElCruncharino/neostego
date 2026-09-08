/*
 * Settings: theme mode (System/Light/Dark, persisted) and an About section (version, license,
 * credits, homepage, and the accessibility note pointing screen-reader users to the classic UI/CLI).
 */
package com.elcruncharino.neostego.compose.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.appVersion
import com.elcruncharino.neostego.compose.theme.ThemeMode
import com.elcruncharino.neostego.compose.ui.SectionLabel
import com.elcruncharino.neostego.compose.ui.SegmentedButtonGroup
import com.openstego.desktop.ui.UILocale
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.about_accessibility_note
import openstego.compose_desktop.generated.resources.about_app_name_version
import openstego.compose_desktop.generated.resources.about_fork_credit
import openstego.compose_desktop.generated.resources.about_license
import openstego.compose_desktop.generated.resources.about_tagline
import openstego.compose_desktop.generated.resources.language_restart_required
import openstego.compose_desktop.generated.resources.section_about
import openstego.compose_desktop.generated.resources.section_language
import openstego.compose_desktop.generated.resources.section_theme
import openstego.compose_desktop.generated.resources.theme_dark
import openstego.compose_desktop.generated.resources.theme_light
import openstego.compose_desktop.generated.resources.theme_system
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.net.URI

private const val HOMEPAGE = "https://github.com/ElCruncharino/neostego"

private val LANGUAGE_MODES = listOf(UILocale.SYSTEM, UILocale.EN, UILocale.ZH, UILocale.JA)

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    languageMode: String,
    onLanguageChange: (String) -> Unit,
) {
    var showRestartNotice by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(Res.string.section_theme))
        SegmentedButtonGroup(
            options = listOf(stringResource(Res.string.theme_system), stringResource(Res.string.theme_light), stringResource(Res.string.theme_dark)),
            selectedIndex = themeMode.ordinal,
            onSelect = { onThemeChange(ThemeMode.entries[it]) },
        )

        SectionLabel(stringResource(Res.string.section_language))
        SegmentedButtonGroup(
            options = listOf(stringResource(Res.string.theme_system), "English", "中文", "日本語"),
            selectedIndex = LANGUAGE_MODES.indexOf(languageMode).coerceAtLeast(0),
            onSelect = {
                onLanguageChange(LANGUAGE_MODES[it])
                showRestartNotice = true
            },
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

    if (showRestartNotice) {
        AlertDialog(
            onDismissRequest = { showRestartNotice = false },
            confirmButton = { TextButton(onClick = { showRestartNotice = false }) { Text("OK") } },
            text = { Text(stringResource(Res.string.language_restart_required)) },
        )
    }
}

private fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
