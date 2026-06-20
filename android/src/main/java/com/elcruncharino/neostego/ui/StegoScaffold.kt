/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.elcruncharino.neostego.LaunchTarget
import com.elcruncharino.neostego.StegoEngine
import com.elcruncharino.neostego.ui.components.GradientBackground
import com.elcruncharino.neostego.ui.screens.AboutDialog
import com.elcruncharino.neostego.ui.screens.HideScreen
import com.elcruncharino.neostego.ui.screens.RevealScreen
import com.elcruncharino.neostego.ui.screens.SettingsScreen
import com.elcruncharino.neostego.ui.screens.WatermarkScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StegoScaffold(target: LaunchTarget, appState: AppState) {
    var showOverflow by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Apply a share/shortcut launch: open the right screen with the handed-in files preselected.
    LaunchedEffect(target) {
        appState.dest = target.destination
        if (target.wavCover) appState.hide.algorithm = StegoEngine.Algorithm.WAV
        target.coverUri?.let { appState.hide.coverUri = it }
        target.payloadUri?.let { appState.hide.messageUri = it }
        target.stegoUri?.let { appState.reveal.stegoUri = it }
        if (target.splitCoverUris.isNotEmpty()) {
            appState.hide.splitMode = true
            appState.hide.splitCovers.clear()
            appState.hide.splitCovers.addAll(target.splitCoverUris)
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("NeoStego") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                showAbout = true
                            },
                        )
                    }
                },
            )
        },
        bottomBar = { FloatingNavBar(appState.dest, onSelect = { appState.dest = it }) },
        snackbarHost = { SnackbarHost(appState.snackbar) },
    ) { padding ->
        GradientBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (appState.dest) {
                    DEST_HIDE -> HideScreen(appState)
                    DEST_REVEAL -> RevealScreen(appState)
                    DEST_WATERMARK -> WatermarkScreen(appState)
                    else -> SettingsScreen(appState)
                }
            }
        }
    }
}

/** A floating, pill-shaped navigation toolbar — the expressive replacement for the bottom NavigationBar. */
@Composable
private fun FloatingNavBar(dest: Int, onSelect: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavPill(selected = dest == DEST_HIDE, icon = Icons.Filled.Lock, label = "Hide") { onSelect(DEST_HIDE) }
                NavPill(selected = dest == DEST_REVEAL, icon = Icons.Filled.LockOpen, label = "Reveal") { onSelect(DEST_REVEAL) }
                NavPill(selected = dest == DEST_WATERMARK, icon = Icons.Filled.Verified, label = "Mark") { onSelect(DEST_WATERMARK) }
                NavPill(selected = dest == DEST_SETTINGS, icon = Icons.Filled.Settings, label = "Settings") { onSelect(DEST_SETTINGS) }
            }
        }
    }
}

@Composable
private fun NavPill(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = label, tint = fg)
            if (selected) {
                Text(label, color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
