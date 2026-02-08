package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * Metadata extracted from a Skill's SKILL.md YAML frontmatter.
 * This is the Level 1 data that is always loaded into the system prompt.
 */
@Serializable
data class SkillMetadata(
    val name: String,           // From YAML frontmatter (e.g., "pdf-processing")
    val description: String     // From YAML frontmatter
)

/**
 * Represents an installed skill on the device.
 * The actual skill content lives on disk as files - this is just a lightweight reference.
 */
@Serializable
data class InstalledSkill(
    val directoryName: String,  // Name of the skill's directory under skills/
    val metadata: SkillMetadata,
    val isEnabled: Boolean = true,       // Whether this skill is active
    val sourceUrl: String? = null,       // Original GitHub URL if imported from GitHub
    val files: List<String> = emptyList() // List of relative file paths in the skill directory
)

