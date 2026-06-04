package com.example.ApI.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.R
import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RemoteSyncSection(
    settings: RemoteSyncSettings,
    onEnabledChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onAuthTokenChange: (String) -> Unit,
    onSyncApiKeysChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: suspend () -> Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var tokenVisible by remember { mutableStateOf(false) }
    var syncNowConfirmed by remember { mutableStateOf(false) }
    var testConnectionResult by remember { mutableStateOf<Boolean?>(null) }
    var testInProgress by remember { mutableStateOf(false) }

    // Local editable state for text fields – seeded from settings, persisted on change
    var serverUrlInput by remember { mutableStateOf(settings.serverBaseUrl) }
    var authTokenInput by remember { mutableStateOf(settings.authToken) }

    // Keep local inputs in sync if settings change externally (e.g. from a sync pull)
    LaunchedEffect(settings.serverBaseUrl) {
        if (serverUrlInput != settings.serverBaseUrl) serverUrlInput = settings.serverBaseUrl
    }
    LaunchedEffect(settings.authToken) {
        if (authTokenInput != settings.authToken) authTokenInput = settings.authToken
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = SurfaceVariant,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {

            // Header row: title + enabled toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_sync_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (settings.enabled)
                            stringResource(R.string.remote_sync_status_enabled)
                        else
                            stringResource(R.string.remote_sync_status_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.enabled) Primary.copy(alpha = 0.9f)
                                else OnSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { onEnabledChange(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f),
                        uncheckedThumbColor = OnSurfaceVariant,
                        uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Server URL field
            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = { newVal ->
                    serverUrlInput = newVal
                    onServerUrlChange(newVal)
                },
                label = {
                    Text(
                        text = stringResource(R.string.remote_sync_server_url),
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Auth token field (password-style with show/hide)
            OutlinedTextField(
                value = authTokenInput,
                onValueChange = { newVal ->
                    authTokenInput = newVal
                    onAuthTokenChange(newVal)
                },
                label = {
                    Text(
                        text = stringResource(R.string.remote_sync_auth_token),
                        color = OnSurface.copy(alpha = 0.7f)
                    )
                },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff
                                          else Icons.Default.Visibility,
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                            tint = OnSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = Primary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // "Also sync API keys" toggle with warning subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_sync_api_keys),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.remote_sync_api_keys_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.6f),
                        lineHeight = 15.sp
                    )
                }
                Switch(
                    checked = settings.syncApiKeys,
                    onCheckedChange = { onSyncApiKeysChange(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f),
                        uncheckedThumbColor = OnSurfaceVariant,
                        uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons: "Sync now" + "Test connection"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSyncNow()
                        syncNowConfirmed = true
                        scope.launch {
                            delay(3000)
                            syncNowConfirmed = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.6f))
                ) {
                    Text(
                        text = if (syncNowConfirmed)
                            stringResource(R.string.remote_sync_now_done)
                        else
                            stringResource(R.string.remote_sync_now),
                        fontSize = 13.sp
                    )
                }

                val testBorderColor = when (testConnectionResult) {
                    true -> Primary.copy(alpha = 0.6f)
                    false -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                    null -> OnSurface.copy(alpha = 0.3f)
                }
                val testContentColor = when (testConnectionResult) {
                    true -> Primary
                    false -> MaterialTheme.colorScheme.error
                    null -> OnSurface
                }

                OutlinedButton(
                    onClick = {
                        testConnectionResult = null
                        testInProgress = true
                        scope.launch {
                            testConnectionResult = onTestConnection()
                            testInProgress = false
                        }
                    },
                    enabled = !testInProgress,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = testContentColor),
                    border = BorderStroke(1.dp, testBorderColor)
                ) {
                    if (testInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = OnSurface
                        )
                    } else {
                        Text(
                            text = when (testConnectionResult) {
                                true -> stringResource(R.string.remote_sync_test_ok)
                                false -> stringResource(R.string.remote_sync_test_fail)
                                null -> stringResource(R.string.remote_sync_test)
                            },
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
