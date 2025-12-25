package com.example.ApI.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.R
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.data.model.ChildLockSettings
import com.example.ApI.data.model.Screen
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import com.example.ApI.ui.components.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsScreen(
    viewModel: ChatViewModel,
    appSettings: AppSettings,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val context = LocalContext.current
        var showImportWarning by remember { mutableStateOf(false) }

        // Child lock state
        var showChildLockSetupDialog by remember { mutableStateOf(false) }
        var showChildLockDisableDialog by remember { mutableStateOf(false) }
        var childLockPassword by remember { mutableStateOf("") }
        var childLockStartTime by remember { mutableStateOf("23:00") }
        var childLockEndTime by remember { mutableStateOf("07:00") }
        var disablePasswordInput by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var disablePasswordVisible by remember { mutableStateOf(false) }
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default_device"

        // JSON file picker launcher
        val jsonPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                viewModel.importChatHistoryFromUri(it)
            }
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
                .systemBarsPadding()
        ) {
            // Modern Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Surface,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onBackClick() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = "הגדרות מתקדמות",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Settings content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Integrations Section
                IntegrationsNavigationItem(
                    onClick = {
                        viewModel.navigateToScreen(Screen.Integrations)
                    }
                )

                // 2. AI Title Generation Section
                TitleGenerationSettingsSection(
                    settings = appSettings.titleGenerationSettings,
                    onSettingsChange = { newSettings ->
                        viewModel.updateTitleGenerationSettings(newSettings)
                    },
                    availableProviders = viewModel.getAvailableProvidersForTitleGeneration()
                )

                // 3. Multi-message mode switch
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = R.string.multi_message_mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OnSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(id = R.string.multi_message_mode_explainer),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface.copy(alpha = 0.7f),
                                    lineHeight = 16.sp
                                )
                            }
                            Switch(
                                checked = appSettings.multiMessageMode,
                                onCheckedChange = { enabled ->
                                    viewModel.updateMultiMessageMode(enabled)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Primary,
                                    checkedTrackColor = Primary.copy(alpha = 0.3f),
                                    uncheckedThumbColor = OnSurfaceVariant,
                                    uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                }

                // 4. Import chat history row
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showImportWarning = true },
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.import_chat_history),
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.beta),
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // 5. Child Lock Section
                ChildLockSettingsSection(
                    settings = appSettings.childLockSettings,
                    onSettingsChange = { enabled, password, startTime, endTime ->
                        viewModel.updateChildLockSettings(enabled, password, startTime, endTime)
                    },
                    onShowSetupDialog = { showChildLockSetupDialog = true },
                    onShowDisableDialog = { showChildLockDisableDialog = true },
                    deviceId = deviceId
                )
            }
        }

        if (showImportWarning) {
            AlertDialog(
                onDismissRequest = { showImportWarning = false },
                title = { Text(text = stringResource(R.string.approve)) },
                text = { Text(text = stringResource(R.string.import_warning)) },
                confirmButton = {
                    TextButton(onClick = {
                        showImportWarning = false
                        jsonPickerLauncher.launch("application/json")
                    }) {
                        Text(text = stringResource(R.string.approve))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportWarning = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Child Lock Setup Dialog
        if (showChildLockSetupDialog) {
            ChildLockSetupDialog(
                password = childLockPassword,
                onPasswordChange = { childLockPassword = it },
                passwordVisible = passwordVisible,
                onPasswordVisibilityChange = { passwordVisible = it },
                startTime = childLockStartTime,
                onStartTimeChange = { childLockStartTime = it },
                endTime = childLockEndTime,
                onEndTimeChange = { childLockEndTime = it },
                onConfirm = {
                    viewModel.setupChildLock(childLockPassword, childLockStartTime, childLockEndTime, deviceId)
                    showChildLockSetupDialog = false
                    childLockPassword = ""
                    passwordVisible = false
                },
                onDismiss = {
                    showChildLockSetupDialog = false
                    childLockPassword = ""
                    passwordVisible = false
                }
            )
        }

        // Child Lock Disable Dialog
        if (showChildLockDisableDialog) {
            ChildLockDisableDialog(
                password = disablePasswordInput,
                onPasswordChange = { disablePasswordInput = it },
                passwordVisible = disablePasswordVisible,
                onPasswordVisibilityChange = { disablePasswordVisible = it },
                onConfirm = {
                    if (viewModel.verifyAndDisableChildLock(disablePasswordInput, deviceId)) {
                        showChildLockDisableDialog = false
                        disablePasswordInput = ""
                        disablePasswordVisible = false
                    }
                },
                onDismiss = {
                    showChildLockDisableDialog = false
                    disablePasswordInput = ""
                    disablePasswordVisible = false
                }
            )
        }
    }
}
