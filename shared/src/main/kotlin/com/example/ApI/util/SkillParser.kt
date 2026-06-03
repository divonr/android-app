package com.example.ApI.util

import com.example.ApI.data.model.SkillMetadata

/**
 * Parses SKILL.md files to extract YAML frontmatter metadata and body content.
 * 
 * Keeps it simple: regex-based parsing of the --- delimited YAML block.
 * No heavy YAML library needed - we only extract `name` and `description`.
 */
object SkillParser {

    private val FRONTMATTER_REGEX = Regex(
        """^---\s*\n(.*?)\n---\s*\n?(.*)""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val YAML_FIELD_REGEX = Regex("""^(\w+)\s*:\s*(.+)$""", RegexOption.MULTILINE)

    /**
     * Parse a SKILL.md file content and extract metadata + body.
     * 
     * @param content The full text content of SKILL.md
     * @return Pair of (SkillMetadata, body) or null if parsing fails
     */
    fun parse(content: String): ParseResult? {
        val match = FRONTMATTER_REGEX.find(content.trimStart()) ?: return null
        val yamlBlock = match.groupValues[1]
        val body = match.groupValues[2].trim()

        val fields = mutableMapOf<String, String>()
        YAML_FIELD_REGEX.findAll(yamlBlock).forEach { fieldMatch ->
            val key = fieldMatch.groupValues[1].trim()
            val value = fieldMatch.groupValues[2].trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            fields[key] = value
        }

        val name = fields["name"] ?: return null
        val description = fields["description"] ?: return null

        if (name.isBlank() || description.isBlank()) return null

        return ParseResult(
            metadata = SkillMetadata(name = name, description = description),
            body = body
        )
    }

    /**
     * Extract only the metadata (Level 1) without parsing the full body.
     * More efficient for building the skill catalog.
     */
    fun parseMetadata(content: String): SkillMetadata? {
        return parse(content)?.metadata
    }

    /**
     * Extract only the body content (Level 2) - everything after the frontmatter.
     */
    fun parseBody(content: String): String? {
        return parse(content)?.body
    }

    /**
     * Build a valid SKILL.md content from metadata and body.
     */
    fun buildSkillMd(metadata: SkillMetadata, body: String): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${metadata.name}")
            appendLine("description: ${metadata.description}")
            appendLine("---")
            appendLine()
            append(body)
        }
    }

    /**
     * Apply a simple diff/patch to file content.
     * Supports search-and-replace style edits.
     * 
     * @param originalContent The original file content
     * @param searchText The text to find in the original
     * @param replaceText The text to replace it with
     * @return The patched content, or null if searchText was not found
     */
    fun applyDiff(originalContent: String, searchText: String, replaceText: String): String? {
        if (!originalContent.contains(searchText)) return null
        return originalContent.replaceFirst(searchText, replaceText)
    }

    data class ParseResult(
        val metadata: SkillMetadata,
        val body: String
    )
}

