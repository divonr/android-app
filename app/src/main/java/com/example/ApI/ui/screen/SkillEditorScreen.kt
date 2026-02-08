package com.example.ApI.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    viewModel: ChatViewModel,
    skillDirectoryName: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skillsManager = viewModel.getSkillsStorageManager()
    val skills = remember { viewModel.getInstalledSkills() }
    val skill = remember(skillDirectoryName) {
        skills.find { it.directoryName == skillDirectoryName }
    }

    if (skill == null) {
        // Skill not found - go back
        LaunchedEffect(Unit) {
            viewModel.showSnackbar("הסקיל לא נמצא")
            onBackClick()
        }
        return
    }

    // Currently selected file for editing
    var selectedFile by remember { mutableStateOf("SKILL.md") }
    var fileContent by remember { mutableStateOf("") }
    var isEdited by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showAddFileDialog by remember { mutableStateOf(false) }

    // Load file content when selected file changes
    LaunchedEffect(selectedFile) {
        val content = skillsManager.readSkillFile(skillDirectoryName, selectedFile)
        fileContent = content ?: ""
        isEdited = false
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BackHandler {
            if (isEdited) {
                // TODO: Could show a "save changes?" dialog
            }
            onBackClick()
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Background)
                .systemBarsPadding()
        ) {
            // Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Surface,
                shadowElevation = 4.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "חזרה",
                                tint = OnSurface
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.metadata.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        // Save button
                        if (isEdited) {
                            IconButton(
                                onClick = {
                                    isSaving = true
                                    val success = skillsManager.writeSkillFile(
                                        skillDirectoryName,
                                        selectedFile,
                                        fileContent
                                    )
                                    isSaving = false
                                    if (success) {
                                        isEdited = false
                                        viewModel.showSnackbar("נשמר ✓")
                                    } else {
                                        viewModel.showSnackbar("שגיאה בשמירה")
                                    }
                                }
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = "שמירה",
                                        tint = Primary
                                    )
                                }
                            }
                        }
                        // Add file button
                        IconButton(onClick = { showAddFileDialog = true }) {
                            Icon(
                                Icons.Default.NoteAdd,
                                contentDescription = "הוספת קובץ",
                                tint = OnSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // File tabs
                    if (skill.files.size > 1) {
                        ScrollableTabRow(
                            selectedTabIndex = skill.files.indexOf(selectedFile).coerceAtLeast(0),
                            containerColor = Surface,
                            contentColor = Primary,
                            edgePadding = 16.dp,
                            divider = {}
                        ) {
                            skill.files.forEach { fileName ->
                                Tab(
                                    selected = selectedFile == fileName,
                                    onClick = {
                                        if (isEdited) {
                                            // Auto-save before switching
                                            skillsManager.writeSkillFile(
                                                skillDirectoryName,
                                                selectedFile,
                                                fileContent
                                            )
                                        }
                                        selectedFile = fileName
                                    },
                                    text = {
                                        Text(
                                            text = fileName,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Editor area
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = fileContent,
                    onValueChange = {
                        fileContent = it
                        isEdited = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = OnSurface
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = Primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = OnSurface.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // Add file dialog
        if (showAddFileDialog) {
            AddFileDialog(
                onDismiss = { showAddFileDialog = false },
                onAdd = { fileName ->
                    val success = skillsManager.writeSkillFile(
                        skillDirectoryName,
                        fileName,
                        "# ${fileName.removeSuffix(".md")}\n\n"
                    )
                    if (success) {
                        showAddFileDialog = false
                        // Refresh and select new file
                        selectedFile = fileName
                        viewModel.showSnackbar("קובץ '$fileName' נוצר")
                    } else {
                        viewModel.showSnackbar("שגיאה ביצירת הקובץ")
                    }
                }
            )
        }
    }
}

@Composable
fun AddFileDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var fileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הוספת קובץ חדש") },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("שם קובץ") },
                placeholder = { Text("REFERENCE.md") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(fileName) },
                enabled = fileName.isNotBlank()
            ) {
                Text("צור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}

