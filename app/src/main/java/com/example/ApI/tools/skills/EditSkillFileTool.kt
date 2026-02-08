package com.example.ApI.tools.skills

import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool that lets the LLM edit a skill file using search-and-replace diffs.
 * More efficient than rewriting entire files - the LLM sends only the changed parts.
 * This is key for continuous skill improvement during conversations.
 */
class EditSkillFileTool(
    private val skillsManager: SkillsStorageManager
) : Tool {

    override val id = "edit_skill_file"
    override val name = "Edit Skill File"
    override val description = "Edit a file in a skill's directory using search-and-replace. More efficient than rewriting the entire file. Provide the exact text to find and its replacement."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        val skillName = parameters["skill_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'skill_name' is required")
        val filePath = parameters["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'file_path' is required")
        val searchText = parameters["search_text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'search_text' is required")
        val replaceText = parameters["replace_text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'replace_text' is required")

        val success = skillsManager.editSkillFile(skillName, filePath, searchText, replaceText)
        return if (success) {
            ToolExecutionResult.Success("Successfully edited '$filePath' in skill '$skillName'")
        } else {
            ToolExecutionResult.Error("Failed to edit '$filePath' in skill '$skillName'. The search text may not have been found, or the skill/file may not exist.")
        }
    }

    override fun getSpecification(provider: String): ToolSpecification {
        val params = when (provider) {
            "google" -> buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The name of the skill")
                    })
                    put("file_path", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Relative path of the file to edit (e.g. 'SKILL.md')")
                    })
                    put("search_text", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The exact text to find in the file")
                    })
                    put("replace_text", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The text to replace it with")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path"); add("search_text"); add("replace_text") })
            }
            else -> buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the skill")
                    })
                    put("file_path", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative path of the file to edit (e.g. 'SKILL.md')")
                    })
                    put("search_text", buildJsonObject {
                        put("type", "string")
                        put("description", "The exact text to find in the file")
                    })
                    put("replace_text", buildJsonObject {
                        put("type", "string")
                        put("description", "The text to replace it with")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path"); add("search_text"); add("replace_text") })
            }
        }

        return ToolSpecification(name = id, description = description, parameters = params)
    }
}

