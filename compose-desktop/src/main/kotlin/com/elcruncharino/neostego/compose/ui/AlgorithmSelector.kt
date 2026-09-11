/*
 * Shared algorithm picker: a KeyboardDropdown plus a card describing the selected algorithm. Used by
 * the Hide screen and all three watermarking screens.
 */
package com.elcruncharino.neostego.compose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elcruncharino.neostego.compose.engine.AlgoInfo
import openstego.compose_desktop.generated.resources.Res
import openstego.compose_desktop.generated.resources.algorithm_content_description
import openstego.compose_desktop.generated.resources.algorithm_label
import openstego.compose_desktop.generated.resources.algorithm_none_selected
import org.jetbrains.compose.resources.stringResource

@Composable
fun AlgorithmSelector(algorithms: List<AlgoInfo>, selected: AlgoInfo?, onSelect: (AlgoInfo) -> Unit) {
    val noneSelected = stringResource(Res.string.algorithm_none_selected)
    val algorithmContentDescription = stringResource(Res.string.algorithm_content_description, selected?.name ?: noneSelected)
    SectionLabel(stringResource(Res.string.algorithm_label))
    KeyboardDropdown(
        displayText = selected?.name.orEmpty(),
        options = algorithms.map { it.name },
        onSelect = { index -> onSelect(algorithms[index]) },
        contentDescription = algorithmContentDescription,
    )

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
