package com.example.ApI.data.repository

import com.example.ApI.data.model.InstalledSkill
import com.example.ApI.data.model.SkillMetadata
import com.example.ApI.util.SkillParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages skill directories on the device filesystem.
 * Skills are stored as plain directories with SKILL.md files - faithful to the standard.
 * 
 * Directory structure:
 *   internalDir/skills/
 *   ├── pdf-processing/
 *   │   ├── SKILL.md
 *   │   ├── FORMS.md
 *   │   └── scripts/
 *   │       └── fill_form.py
 *   ├── code-review/
 *   │   └── SKILL.md
 *   └── ...
 */
class SkillsStorageManager(
    private val internalDir: File,
    private val json: Json
) {
    private val skillsDir: File
        get() = File(internalDir, "skills").also { if (!it.exists()) it.mkdirs() }

    // ============ Skill Catalog (Level 1) ============

    /**
     * Get all installed skills with their metadata.
     * Reads each skill's SKILL.md frontmatter in real-time (no cache).
     */
    fun getInstalledSkills(): List<InstalledSkill> {
        val enabledState = loadEnabledState()
        val sourceUrls = loadSourceUrls()

        return skillsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
            ?.mapNotNull { dir ->
                val skillMdContent = File(dir, "SKILL.md").readText()
                val metadata = SkillParser.parseMetadata(skillMdContent) ?: return@mapNotNull null
                val files = dir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(dir).path.replace("\\", "/") }
                    .toList()

                InstalledSkill(
                    directoryName = dir.name,
                    metadata = metadata,
                    isEnabled = enabledState[dir.name] ?: true,
                    sourceUrl = sourceUrls[dir.name],
                    files = files
                )
            }
            ?.sortedBy { it.metadata.name }
            ?: emptyList()
    }

    /**
     * Get only the metadata for all enabled skills (for system prompt injection).
     */
    fun getEnabledSkillsMetadata(): List<Pair<String, SkillMetadata>> {
        return getInstalledSkills()
            .filter { it.isEnabled }
            .map { it.directoryName to it.metadata }
    }

    // ============ Skill Content (Level 2 & 3) ============

    /**
     * Read the body of a skill's SKILL.md (Level 2 content).
     * Called when the LLM triggers a skill via the read_skill tool.
     */
    fun readSkillBody(skillName: String): String? {
        val dir = findSkillDir(skillName) ?: return null
        val skillMd = File(dir, "SKILL.md")
        if (!skillMd.exists()) return null
        return SkillParser.parseBody(skillMd.readText())
    }

    /**
     * Read any file within a skill directory (Level 3 content).
     * Called via read_skill_file tool.
     * 
     * @param skillName The skill directory name or skill metadata name
     * @param relativePath Relative path within the skill directory
     */
    fun readSkillFile(skillName: String, relativePath: String): String? {
        val dir = findSkillDir(skillName) ?: return null
        val file = File(dir, relativePath)
        // Security: ensure the resolved path is still within the skill directory
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) return null
        if (!file.exists() || !file.isFile) return null
        return file.readText()
    }

    /**
     * Write/overwrite a file within a skill directory.
     * Called via write_skill_file tool (allows LLM to create/update skill files).
     */
    fun writeSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val dir = findSkillDir(skillName) ?: return false
        val file = File(dir, relativePath)
        // Security: ensure the resolved path is still within the skill directory
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) return false
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Apply a diff (search-and-replace) to a file within a skill directory.
     * Called via edit_skill_file tool.
     * 
     * @param skillName The skill name
     * @param relativePath Relative path within the skill directory
     * @param searchText The text to find
     * @param replaceText The text to replace it with
     * @return true if the diff was applied successfully
     */
    fun editSkillFile(skillName: String, relativePath: String, searchText: String, replaceText: String): Boolean {
        val dir = findSkillDir(skillName) ?: return false
        val file = File(dir, relativePath)
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) return false
        if (!file.exists() || !file.isFile) return false

        return try {
            val original = file.readText()
            val patched = SkillParser.applyDiff(original, searchText, replaceText) ?: return false
            file.writeText(patched)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * List all files in a skill directory.
     */
    fun listSkillFiles(skillName: String): List<String>? {
        val dir = findSkillDir(skillName) ?: return null
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).path.replace("\\", "/") }
            .toList()
    }

    // ============ CRUD Operations ============

    /**
     * Create a new skill with a basic SKILL.md template.
     */
    fun createSkill(name: String, description: String, body: String = ""): InstalledSkill? {
        val dirName = name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val dir = File(skillsDir, dirName)
        if (dir.exists()) return null // Already exists

        return try {
            dir.mkdirs()
            val metadata = SkillMetadata(name = name, description = description)
            val skillMdContent = SkillParser.buildSkillMd(
                metadata,
                body.ifBlank { "# ${name}\n\n## Instructions\n" }
            )
            File(dir, "SKILL.md").writeText(skillMdContent)

            InstalledSkill(
                directoryName = dirName,
                metadata = metadata,
                isEnabled = true,
                files = listOf("SKILL.md")
            )
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Delete a skill and all its files.
     */
    fun deleteSkill(skillName: String): Boolean {
        val dir = findSkillDir(skillName) ?: return false
        return try {
            dir.deleteRecursively()
            // Clean up enabled state and source URLs
            val enabledState = loadEnabledState().toMutableMap()
            enabledState.remove(dir.name)
            saveEnabledState(enabledState)
            val sourceUrls = loadSourceUrls().toMutableMap()
            sourceUrls.remove(dir.name)
            saveSourceUrls(sourceUrls)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Toggle a skill's enabled/disabled state.
     */
    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        val dir = findSkillDir(skillName) ?: return
        val state = loadEnabledState().toMutableMap()
        state[dir.name] = enabled
        saveEnabledState(state)
    }

    // ============ Import ============

    /**
     * Import a skill from a ZIP file.
     * The ZIP should contain a directory with SKILL.md at its root (or directly SKILL.md files).
     */
    fun importFromZip(zipInputStream: ZipInputStream): InstalledSkill? {
        // First, extract to a temp directory to inspect the structure
        val tempDir = File(skillsDir, "_temp_import_${System.currentTimeMillis()}")
        try {
            tempDir.mkdirs()
            var entry: ZipEntry? = zipInputStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(tempDir, entry.name)
                    // Security: prevent zip path traversal
                    if (!outFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                        entry = zipInputStream.nextEntry
                        continue
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        zipInputStream.copyTo(out)
                    }
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }

            // Find SKILL.md - could be at root or in a subdirectory
            val skillMdFile = findSkillMdInDir(tempDir) ?: return null
            val skillDir = skillMdFile.parentFile ?: return null

            // Parse and validate
            val metadata = SkillParser.parseMetadata(skillMdFile.readText()) ?: return null
            val targetDirName = metadata.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
            val targetDir = File(skillsDir, targetDirName)

            // Move to final location (overwrite if exists)
            if (targetDir.exists()) targetDir.deleteRecursively()
            skillDir.copyRecursively(targetDir, overwrite = true)
            tempDir.deleteRecursively()

            return InstalledSkill(
                directoryName = targetDirName,
                metadata = metadata,
                isEnabled = true,
                files = listSkillFiles(targetDirName) ?: emptyList()
            )
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            return null
        }
    }

    /**
     * Import a skill from raw SKILL.md text content.
     */
    fun importFromText(skillMdContent: String): InstalledSkill? {
        val parseResult = SkillParser.parse(skillMdContent) ?: return null
        val metadata = parseResult.metadata
        val dirName = metadata.name.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val dir = File(skillsDir, dirName)

        return try {
            dir.mkdirs()
            File(dir, "SKILL.md").writeText(skillMdContent)
            InstalledSkill(
                directoryName = dirName,
                metadata = metadata,
                isEnabled = true,
                files = listOf("SKILL.md")
            )
        } catch (e: IOException) {
            null
        }
    }

    // ============ Export ============

    /**
     * Export a skill as a ZIP file to the given output stream.
     */
    fun exportToZip(skillName: String, zipOutputStream: ZipOutputStream): Boolean {
        val dir = findSkillDir(skillName) ?: return false
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = "${dir.name}/${file.relativeTo(dir).path.replace("\\", "/")}"
                    zipOutputStream.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zipOutputStream) }
                    zipOutputStream.closeEntry()
                }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the raw SKILL.md content for sharing as text.
     */
    fun getSkillMdContent(skillName: String): String? {
        val dir = findSkillDir(skillName) ?: return null
        val skillMd = File(dir, "SKILL.md")
        return if (skillMd.exists()) skillMd.readText() else null
    }

    /**
     * Save a source URL for a skill (when imported from GitHub).
     */
    fun saveSkillSourceUrl(skillDirName: String, url: String) {
        val urls = loadSourceUrls().toMutableMap()
        urls[skillDirName] = url
        saveSourceUrls(urls)
    }

    // ============ Internal Helpers ============

    /**
     * Find a skill directory by name (matches both directory name and metadata name).
     */
    private fun findSkillDir(skillName: String): File? {
        // Try direct directory name match first
        val directMatch = File(skillsDir, skillName)
        if (directMatch.isDirectory && File(directMatch, "SKILL.md").exists()) {
            return directMatch
        }

        // Try matching by metadata name
        return skillsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
            ?.find { dir ->
                val content = File(dir, "SKILL.md").readText()
                val metadata = SkillParser.parseMetadata(content)
                metadata?.name == skillName
            }
    }

    /**
     * Find SKILL.md in a directory tree (for ZIP import).
     */
    private fun findSkillMdInDir(dir: File): File? {
        // Check root first
        val rootSkillMd = File(dir, "SKILL.md")
        if (rootSkillMd.exists()) return rootSkillMd

        // Check one level of subdirectories
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, "SKILL.md") }
            ?.firstOrNull { it.exists() }
    }

    // ============ Enabled State Persistence ============

    private fun loadEnabledState(): Map<String, Boolean> {
        val file = File(internalDir, "skills_enabled.json")
        return if (file.exists()) {
            try {
                json.decodeFromString<Map<String, Boolean>>(file.readText())
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    private fun saveEnabledState(state: Map<String, Boolean>) {
        val file = File(internalDir, "skills_enabled.json")
        try {
            file.writeText(json.encodeToString(state))
        } catch (e: IOException) {
            // Handle error
        }
    }

    // ============ Source URL Persistence ============

    private fun loadSourceUrls(): Map<String, String> {
        val file = File(internalDir, "skills_sources.json")
        return if (file.exists()) {
            try {
                json.decodeFromString<Map<String, String>>(file.readText())
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    private fun saveSourceUrls(urls: Map<String, String>) {
        val file = File(internalDir, "skills_sources.json")
        try {
            file.writeText(json.encodeToString(urls))
        } catch (e: IOException) {
            // Handle error
        }
    }
}

