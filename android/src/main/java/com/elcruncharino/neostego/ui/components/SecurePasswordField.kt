/*
 * Steganography utility to hide messages into cover files
 * Copyright (c) 2026 Nick Haghiri
 */

package com.elcruncharino.neostego.ui.components

import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elcruncharino.neostego.R

/**
 * Reads the password directly from the EditText as a char[], without ever creating a String, so it
 * can be wiped after use. Returns null when empty.
 */
internal fun readPasswordChars(editText: EditText?): CharArray? {
    val editable = editText?.text ?: return null
    val length = editable.length
    if (length == 0) {
        return null
    }
    val chars = CharArray(length)
    TextUtils.getChars(editable, 0, length, chars, 0)
    return chars
}

/** The native EditText for one password field, shared by [SecurePasswordField] and [PasswordFieldWithConfirm]. */
@Composable
private fun PasswordEditText(show: Boolean, label: String, description: String, onViewCreated: (EditText) -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accentColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        factory = { ctx ->
            EditText(ctx).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                transformationMethod = PasswordTransformationMethod.getInstance()
                // Label the native field for TalkBack; the visual header above is a separate
                // composable and is not otherwise associated.
                hint = label
                contentDescription = description
                onViewCreated(this)
            }
        },
        update = { et ->
            et.setTextColor(textColor)
            et.setHintTextColor(hintColor)
            et.highlightColor = accentColor
            et.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
            et.setSelection(et.text.length)
        },
    )
}

/** Label + Show/Hide toggle row, shared by [SecurePasswordField] and [PasswordFieldWithConfirm]. */
@Composable
private fun PasswordHeaderRow(label: String, show: Boolean, onToggleShow: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleShow) {
            Text(if (show) stringResource(R.string.btn_hide_password) else stringResource(R.string.btn_show_password))
        }
    }
}

/**
 * A password field backed by a native EditText. Unlike a Compose TextField (whose value is a String
 * that cannot be wiped), this lets the password be read out as a char[] and erased after use.
 */
@Composable
fun SecurePasswordField(
    show: Boolean,
    onToggleShow: () -> Unit,
    onViewCreated: (EditText) -> Unit,
    required: Boolean = false,
    label: String? = null,
    description: String? = null,
) {
    val passwordLabel = label ?: stringResource(
        if (required) R.string.label_password_required else R.string.label_password_optional,
    )
    val passwordDescription = description ?: stringResource(
        if (required) R.string.cd_password_required else R.string.cd_password_optional,
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            PasswordHeaderRow(passwordLabel, show, onToggleShow)
            PasswordEditText(show, passwordLabel, passwordDescription, onViewCreated)
        }
    }
}

/**
 * Password entry for Hide, with a confirm field that only exists while the password is masked - once
 * shown in plaintext, the user can just look at it, so there is nothing left to confirm. Both fields
 * share one container and one Show/Hide toggle.
 */
@Composable
fun PasswordFieldWithConfirm(
    show: Boolean,
    onToggleShow: () -> Unit,
    onViewCreated: (EditText) -> Unit,
    onConfirmViewCreated: (EditText) -> Unit,
) {
    val passwordLabel = stringResource(R.string.label_password_optional)
    val passwordDescription = stringResource(R.string.cd_password_optional)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PasswordHeaderRow(passwordLabel, show, onToggleShow)
            PasswordEditText(show, passwordLabel, passwordDescription, onViewCreated)
            if (!show) {
                val confirmLabel = stringResource(R.string.label_password_confirm)
                val confirmDescription = stringResource(R.string.cd_password_confirm)
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                PasswordEditText(show, confirmLabel, confirmDescription, onConfirmViewCreated)
            }
        }
    }
}
