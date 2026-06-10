package com.example.ApI.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    needsReauth: Boolean,
    isSignInInProgress: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onSyncApiKeysChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onTestConnection: suspend () -> Boolean,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var syncNowConfirmed by remember { mutableStateOf(false) }
    var testConnectionResult by remember { mutableStateOf<Boolean?>(null) }
    var testInProgress by remember { mutableStateOf(false) }

    // Local editable state for server URL field
    var serverUrlInput by remember { mutableStateOf(settings.serverBaseUrl) }

    // Keep local input in sync if settings change externally (e.g. from a sync pull)
    LaunchedEffect(settings.serverBaseUrl) {
        if (serverUrlInput != settings.serverBaseUrl) serverUrlInput = settings.serverBaseUrl
    }

    val isSignedIn = settings.accountEmail.isNotEmpty()

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
                        text = when {
                            needsReauth -> stringResource(R.string.remote_sync_status_needs_reauth)
                            settings.enabled && isSignedIn -> stringResource(R.string.remote_sync_status_signed_in)
                            else -> stringResource(R.string.remote_sync_status_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            needsReauth -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            settings.enabled && isSignedIn -> Primary.copy(alpha = 0.9f)
                            else -> OnSurface.copy(alpha = 0.5f)
                        },
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { on ->
                        if (on && !isSignedIn) {
                            // No account yet — launch sign-in flow instead of enabling directly
                            onSignInClick()
                        } else {
                            onEnabledChange(on)
                        }
                    },
                    enabled = !isSignInInProgress,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f),
                        uncheckedThumbColor = OnSurfaceVariant,
                        uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Account area ──────────────────────────────────────────────────

            when {
                isSignInInProgress -> {
                    // Sign-in in progress indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                        Text(
                            text = stringResource(R.string.remote_sync_signing_in),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                needsReauth -> {
                    // Session-expired warning row
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.remote_sync_session_expired),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onSignInClick,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = stringResource(R.string.remote_sync_sign_in),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                isSignedIn -> {
                    // Signed-in account row
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.08f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.remote_sync_signed_in_as),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = settings.accountEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            TextButton(
                                onClick = onSignOutClick,
                                colors = ButtonDefaults.textButtonColors(contentColor = OnSurface.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = stringResource(R.string.remote_sync_sign_out),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                else -> {
                    // Signed-out state — "Sign in with Google" button
                    OutlinedButton(
                        onClick = onSignInClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = stringResource(R.string.remote_sync_sign_in),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Server URL field (advanced) ───────────────────────────────────

            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = { newVal ->
                    serverUrlInput = newVal
                    onServerUrlChange(newVal)
                },
                label = {
                    Text(
                        text = stringResource(R.string.remote_sync_server_url_advanced),
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

            Spacer(modifier = Modifier.height(16.dp))

            // ── "Also sync API keys" toggle ───────────────────────────────────

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

            // ── Action buttons: "Sync now" + "Test connection" ────────────────

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
