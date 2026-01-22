package com.example.ApI.ui.screen

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.data.model.AppSettings
import com.example.ApI.ui.theme.*
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.ui.components.GitHubOAuthWebViewDialog
import com.example.ApI.ui.components.OAuthResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    viewModel: ChatViewModel,
    appSettings: AppSettings,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // State for GitHub OAuth WebView dialog
    var showGitHubOAuthDialog by remember { mutableStateOf(false) }
    var gitHubAuthUrl by remember { mutableStateOf("") }
    var gitHubAuthState by remember { mutableStateOf("") }

    // Google Sign-In ActivityResultLauncher
    // Note: Google Sign-In does NOT return Activity.RESULT_OK on success!
    // It returns RESULT_CANCELED (0) even when successful.
    // The actual result is determined by parsing the intent data.
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Always try to process the result - don't check resultCode
        // Google Sign-In SDK will determine success/failure from the intent data
        result.data?.let { data ->
            viewModel.handleGoogleSignInResult(data)
        }
    }

    // Show GitHub OAuth WebView dialog
    if (showGitHubOAuthDialog && gitHubAuthUrl.isNotEmpty()) {
        GitHubOAuthWebViewDialog(
            authUrl = gitHubAuthUrl,
            onResult = { result ->
                when (result) {
                    is OAuthResult.Success -> {
                        // Handle successful OAuth - exchange code for token
                        viewModel.handleGitHubCallback(result.code, result.state)
                    }
                    is OAuthResult.Error -> {
                        // Show error message
                        viewModel.showSnackbar("שגיאת התחברות: ${result.message}")
                    }
                    is OAuthResult.Cancelled -> {
                        // User cancelled - do nothing
                    }
                }
            },
            onDismiss = {
                showGitHubOAuthDialog = false
                gitHubAuthUrl = ""
                gitHubAuthState = ""
            }
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {

        // Handle Android back button
        BackHandler {
            onBackClick()
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
                        text = "כלים חיצוניים",
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Tools Section
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    shadowElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "כלים זמינים",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurface,
                            fontWeight = FontWeight.Medium
                        )
                        
                        // Date and Time Tool
                        DateTimeToolItem(
                            isEnabled = appSettings.enabledTools.contains("get_date_time"),
                            onToggle = { enabled ->
                                if (enabled) {
                                    viewModel.enableTool("get_date_time")
                                } else {
                                    viewModel.disableTool("get_date_time")
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Group Conversations Tool
                        GroupConversationsToolItem(
                            isEnabled = appSettings.enabledTools.contains("get_current_group_conversations"),
                            onToggle = { enabled ->
                                if (enabled) {
                                    viewModel.enableTool("get_current_group_conversations")
                                } else {
                                    viewModel.disableTool("get_current_group_conversations")
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Python Interpreter Tool
                        PythonInterpreterToolItem(
                            isEnabled = appSettings.enabledTools.contains(ToolRegistry.PYTHON_INTERPRETER),
                            onToggle = { enabled ->
                                if (enabled) {
                                    viewModel.enableTool(ToolRegistry.PYTHON_INTERPRETER)
                                } else {
                                    viewModel.disableTool(ToolRegistry.PYTHON_INTERPRETER)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // GitHub Integration
                        GitHubIntegrationItem(
                            viewModel = viewModel,
                            isConnected = appSettings.githubConnections.containsKey(appSettings.current_user),
                            githubConnection = viewModel.getGitHubConnection(),
                            onToggle = { shouldConnect ->
                                if (shouldConnect) {
                                    // Start OAuth flow with in-app WebView
                                    val (authUrl, state) = viewModel.getGitHubAuthUrl()
                                    gitHubAuthUrl = authUrl
                                    gitHubAuthState = state
                                    showGitHubOAuthDialog = true
                                } else {
                                    // Disconnect
                                    viewModel.disconnectGitHub()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Google Workspace Integration
                        GoogleWorkspaceIntegrationItem(
                            viewModel = viewModel,
                            // Use appSettings to check connection status reactively
                            isConnected = appSettings.googleWorkspaceConnections.containsKey(appSettings.current_user) &&
                                    (appSettings.googleWorkspaceConnections[appSettings.current_user]?.let { 
                                        // Also verify token validity if possible, or assume true if in settings
                                        true 
                                    } ?: false),
                            googleWorkspaceConnection = viewModel.getGoogleWorkspaceConnection(),
                            onConnect = {
                                // Launch Google Sign-In
                                val intent = viewModel.getGoogleSignInIntent()
                                googleSignInLauncher.launch(intent)
                            },
                            onDisconnect = {
                                viewModel.disconnectGoogleWorkspace()
                            },
                            onServicesChange = { gmail, calendar, drive ->
                                viewModel.updateGoogleWorkspaceServices(gmail, calendar, drive)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateTimeToolItem(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant.copy(alpha = 0.3f)
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
                    text = "תאריך ושעה",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "מאפשר למודל לקבל את התאריך והשעה הנוכחיים במכשיר",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Primary,
                    checkedTrackColor = Primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun GroupConversationsToolItem(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant.copy(alpha = 0.3f)
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
                    text = "שיחות בקבוצה",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "אפשר למודל לראות שיחות אחרות באותה הקבוצה",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Primary,
                    checkedTrackColor = Primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun PythonInterpreterToolItem(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant.copy(alpha = 0.3f)
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
                    text = "מפרש Python",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "הרצת קוד Python עם pandas, numpy, matplotlib לניתוח נתונים",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Primary,
                    checkedTrackColor = Primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun GitHubIntegrationItem(
    viewModel: ChatViewModel,
    isConnected: Boolean,
    githubConnection: com.example.ApI.data.model.GitHubConnection?,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Main switch row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "חיבור ל-GitHub",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isConnected) {
                            "מחובר כ-${githubConnection?.user?.login ?: "משתמש"}"
                        } else {
                            "אפשר למודל לעבוד עם קוד ב-GitHub"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }

                Switch(
                    checked = isConnected,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.5f)
                    )
                )
            }

            // Show GitHub tools list when connected
            if (isConnected) {
                Spacer(modifier = Modifier.height(12.dp))

                // Separator
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = OnSurface.copy(alpha = 0.1f)
                )

                Text(
                    text = "כלי GitHub כלולים:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // List of GitHub tools
                val githubTools = listOf(
                    "קריאת קבצים מ-repositories",
                    "כתיבה ועדכון קבצים",
                    "רשימת תוכן תיקיות",
                    "חיפוש קוד ב-repositories",
                    "יצירת ענפים (branches)",
                    "יצירת Pull Requests",
                    "קבלת מידע על repositories",
                    "רשימת repositories של המשתמש"
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    githubTools.forEach { tool ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.4f)
                            )
                            Text(
                                text = tool,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface.copy(alpha = 0.4f),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleWorkspaceIntegrationItem(
    viewModel: ChatViewModel,
    isConnected: Boolean,
    googleWorkspaceConnection: com.example.ApI.data.model.GoogleWorkspaceConnection?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onServicesChange: (Boolean, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val services = googleWorkspaceConnection?.enabledServices
        ?: com.example.ApI.data.model.EnabledGoogleServices()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Main parent switch row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "חיבור ל-Google Workspace",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isConnected) {
                            "מחובר כ-${googleWorkspaceConnection?.user?.email ?: "משתמש"}"
                        } else {
                            "גישה ל-Gmail, Calendar ו-Drive"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }

                Switch(
                    checked = isConnected,
                    onCheckedChange = { shouldConnect ->
                        if (shouldConnect) {
                            onConnect()
                        } else {
                            onDisconnect()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.5f)
                    )
                )
            }

            // Show sub-switches when connected
            if (isConnected) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = OnSurface.copy(alpha = 0.1f)
                )

                Text(
                    text = "שירותים מופעלים:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Gmail sub-toggle
                ServiceToggleRow(
                    name = "Gmail",
                    description = "קריאה, שליחה וחיפוש אימיילים",
                    isEnabled = services.gmail,
                    onToggle = { enabled ->
                        onServicesChange(enabled, services.calendar, services.drive)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Calendar sub-toggle
                ServiceToggleRow(
                    name = "Calendar",
                    description = "רשימת אירועים, יצירה וצפייה",
                    isEnabled = services.calendar,
                    onToggle = { enabled ->
                        onServicesChange(services.gmail, enabled, services.drive)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Drive sub-toggle
                ServiceToggleRow(
                    name = "Drive",
                    description = "רשימה, קריאה, העלאה ומחיקה",
                    isEnabled = services.drive,
                    onToggle = { enabled ->
                        onServicesChange(services.gmail, services.calendar, enabled)
                    }
                )
            }
        }
    }
}

@Composable
private fun ServiceToggleRow(
    name: String,
    description: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
        }

        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary,
                checkedTrackColor = Primary.copy(alpha = 0.5f)
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}
