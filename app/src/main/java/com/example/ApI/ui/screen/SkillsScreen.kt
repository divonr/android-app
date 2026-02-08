package com.example.ApI.ui.screen

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.InstalledSkill
import com.example.ApI.ui.ChatViewModel
import com.example.ApI.ui.theme.*
import java.util.zip.ZipInputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SkillsScreen(
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onEditSkill: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var skills by remember { mutableStateOf(viewModel.getInstalledSkills()) }
    var showImportTextDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf<InstalledSkill?>(null) }
    var contextMenuSkill by remember { mutableStateOf<InstalledSkill?>(null) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // File picker for ZIP import
    val zipImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                inputStream?.let { stream ->
                    val zipStream = ZipInputStream(stream)
                    val imported = viewModel.importSkillFromZip(zipStream)
                    zipStream.close()
                    stream.close()
                    if (imported != null) {
                        skills = viewModel.getInstalledSkills()
                        viewModel.showSnackbar("סקיל '${imported.metadata.name}' יובא בהצלחה")
                    } else {
                        viewModel.showSnackbar("שגיאה בייבוא הסקיל - קובץ SKILL.md לא נמצא")
                    }
                }
            } catch (e: Exception) {
                viewModel.showSnackbar("שגיאה בייבוא: ${e.message}")
            }
        }
    }

    // File picker for SKILL.md text import
    val textImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val content = inputStream?.bufferedReader()?.readText() ?: return@let
                inputStream.close()
                val imported = viewModel.importSkillFromText(content)
                if (imported != null) {
                    skills = viewModel.getInstalledSkills()
                    viewModel.showSnackbar("סקיל '${imported.metadata.name}' יובא בהצלחה")
                } else {
                    viewModel.showSnackbar("שגיאה בייבוא - הקובץ אינו SKILL.md תקין")
                }
            } catch (e: Exception) {
                viewModel.showSnackbar("שגיאה בייבוא: ${e.message}")
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BackHandler { onBackClick() }

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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "חזרה",
                            tint = OnSurface
                        )
                    }
                    Text(
                        text = "סקילים",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // Import menu
                    var showImportMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showImportMenu = true }) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "ייבוא סקיל",
                                tint = Primary
                            )
                        }
                        DropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("ייבוא מקובץ ZIP") },
                                onClick = {
                                    showImportMenu = false
                                    zipImportLauncher.launch("application/zip")
                                },
                                leadingIcon = { Icon(Icons.Default.FolderZip, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("ייבוא מקובץ SKILL.md") },
                                onClick = {
                                    showImportMenu = false
                                    textImportLauncher.launch("*/*")
                                },
                                leadingIcon = { Icon(Icons.Default.Description, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("ייבוא מטקסט") },
                                onClick = {
                                    showImportMenu = false
                                    showImportTextDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.ContentPaste, null) }
                            )
                        }
                    }
                    // Create new skill
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "יצירת סקיל חדש",
                            tint = Primary
                        )
                    }
                }
            }

            // Skills list
            if (skills.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = Primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "אין סקילים מותקנים",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "סקילים מרחיבים את יכולות המודל עם ידע ומומחיות ייעודיים.\nייבא סקיל קיים או צור חדש.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface.copy(alpha = 0.5f),
                            lineHeight = 22.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(skills, key = { it.directoryName }) { skill ->
                        SkillCard(
                            skill = skill,
                            onTap = { onEditSkill(skill.directoryName) },
                            onLongPress = {
                                contextMenuSkill = skill
                            },
                            onToggleEnabled = { enabled ->
                                viewModel.setSkillEnabled(skill.directoryName, enabled)
                                skills = viewModel.getInstalledSkills()
                            }
                        )
                    }
                }
            }
        }

        // Context menu for long press
        if (contextMenuSkill != null) {
            AlertDialog(
                onDismissRequest = { contextMenuSkill = null },
                title = { Text(contextMenuSkill!!.metadata.name, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Edit
                        TextButton(
                            onClick = {
                                val dirName = contextMenuSkill!!.directoryName
                                contextMenuSkill = null
                                onEditSkill(dirName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("עריכה")
                            }
                        }
                        // Copy SKILL.md content
                        TextButton(
                            onClick = {
                                val content = viewModel.getSkillMdContent(contextMenuSkill!!.directoryName)
                                if (content != null) {
                                    clipboardManager.setText(AnnotatedString(content))
                                    viewModel.showSnackbar("תוכן SKILL.md הועתק")
                                }
                                contextMenuSkill = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("העתקת SKILL.md")
                            }
                        }
                        // Export as ZIP
                        TextButton(
                            onClick = {
                                try {
                                    val skill = contextMenuSkill!!
                                    val manager = viewModel.getSkillsStorageManager()
                                    val cacheFile = java.io.File(context.cacheDir, "${skill.directoryName}.zip")
                                    val zipOut = java.util.zip.ZipOutputStream(cacheFile.outputStream())
                                    val success = manager.exportToZip(skill.directoryName, zipOut)
                                    zipOut.close()
                                    if (success) {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            cacheFile
                                        )
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "שיתוף סקיל"))
                                    }
                                } catch (e: Exception) {
                                    viewModel.showSnackbar("שגיאה בייצוא: ${e.message}")
                                }
                                contextMenuSkill = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("ייצוא ושיתוף (ZIP)")
                            }
                        }
                        // Source URL
                        if (contextMenuSkill!!.sourceUrl != null) {
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(contextMenuSkill!!.sourceUrl!!))
                                    viewModel.showSnackbar("קישור המקור הועתק")
                                    contextMenuSkill = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text("העתקת קישור מקור")
                                }
                            }
                        }
                        Divider(color = OnSurface.copy(alpha = 0.1f))
                        // Delete
                        TextButton(
                            onClick = {
                                showDeleteConfirmation = contextMenuSkill
                                contextMenuSkill = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("מחיקה", color = AccentRed)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { contextMenuSkill = null }) {
                        Text("סגירה")
                    }
                }
            )
        }

        // Delete confirmation
        if (showDeleteConfirmation != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = null },
                title = { Text("מחיקת סקיל") },
                text = { Text("למחוק את הסקיל '${showDeleteConfirmation!!.metadata.name}' וכל הקבצים שלו?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteSkill(showDeleteConfirmation!!.directoryName)
                            skills = viewModel.getInstalledSkills()
                            showDeleteConfirmation = null
                        }
                    ) {
                        Text("מחיקה", color = AccentRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = null }) {
                        Text("ביטול")
                    }
                }
            )
        }

        // Import from text dialog
        if (showImportTextDialog) {
            ImportSkillTextDialog(
                onDismiss = { showImportTextDialog = false },
                onImport = { content ->
                    val imported = viewModel.importSkillFromText(content)
                    if (imported != null) {
                        skills = viewModel.getInstalledSkills()
                        viewModel.showSnackbar("סקיל '${imported.metadata.name}' יובא בהצלחה")
                    } else {
                        viewModel.showSnackbar("שגיאה - תוכן SKILL.md לא תקין (חסר frontmatter)")
                    }
                    showImportTextDialog = false
                }
            )
        }

        // Create new skill dialog
        if (showCreateDialog) {
            CreateSkillDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, description ->
                    val created = viewModel.createSkill(name, description)
                    if (created != null) {
                        skills = viewModel.getInstalledSkills()
                        showCreateDialog = false
                        onEditSkill(created.directoryName)
                    } else {
                        viewModel.showSnackbar("שגיאה ביצירת סקיל - ייתכן שכבר קיים סקיל עם שם זה")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SkillCard(
    skill: InstalledSkill,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Skill icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Primary.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = skill.metadata.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (skill.isEnabled) OnSurface else OnSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = skill.metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (skill.isEnabled) OnSurface.copy(alpha = 0.6f) else OnSurface.copy(alpha = 0.35f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                if (skill.files.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${skill.files.size} קבצים",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Enable/disable toggle
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Switch(
                    checked = skill.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.scale(0.8f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Primary,
                        checkedTrackColor = Primary.copy(alpha = 0.3f),
                        uncheckedThumbColor = OnSurface.copy(alpha = 0.3f),
                        uncheckedTrackColor = OnSurface.copy(alpha = 0.1f)
                    )
                )
            }
        }
    }
}

@Composable
fun ImportSkillTextDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ייבוא סקיל מטקסט") },
        text = {
            Column {
                Text(
                    "הדבק את תוכן ה-SKILL.md כולל ה-frontmatter:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    placeholder = {
                        Text(
                            "---\nname: my-skill\ndescription: ...\n---\n\n# My Skill\n...",
                            color = OnSurface.copy(alpha = 0.3f)
                        )
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.contains("---")
            ) {
                Text("ייבוא")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}

@Composable
fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("יצירת סקיל חדש") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().replace(Regex("[^a-z0-9-]"), "-") },
                    label = { Text("שם (אנגלית, מקפים)") },
                    placeholder = { Text("my-skill-name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("תיאור") },
                    placeholder = { Text("תיאור קצר של מה הסקיל עושה ומתי להשתמש בו") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank() && description.isNotBlank()
            ) {
                Text("צור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ביטול") }
        }
    )
}

