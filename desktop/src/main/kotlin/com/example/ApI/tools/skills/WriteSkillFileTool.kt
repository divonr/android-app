package com.example.ApI.tools.skills

import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool that lets the LLM write/overwrite a file within a skill directory.
 * This enables continuous improvement: the LLM can update skill instructions
 * based on what works well during conversations.
 */
class WriteSkillFileTool(
    private val skillsManager: SkillsStorageManager
) : Tool {

    override val id = "write_skill_file"
    override val name = "Write Skill File"
    override val description = "Create or overwrite a file in a skill's directory. Use to improve or add content to skills based on what works well."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        val skillName = parameters["skill_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'skill_name' is required")
        val filePath = parameters["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'file_path' is required")
        val content = parameters["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'content' is required")

        val success = skillsManager.writeSkillFile(skillName, filePath, content)
        return if (success) {
            ToolExecutionResult.Success("Successfully wrote '$filePath' in skill '$skillName'")
        } else {
            ToolExecutionResult.Error("Failed to write '$filePath' in skill '$skillName'. Skill may not exist.")
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
                        put("description", "Relative path for the file (e.g. 'SKILL.md' or 'REFERENCE.md')")
                    })
                    put("content", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The full content to write to the file")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path"); add("content") })
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
                        put("description", "Relative path for the file (e.g. 'SKILL.md' or 'REFERENCE.md')")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The full content to write to the file")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path"); add("content") })
            }
        }

        return ToolSpecification(name = id, description = description, parameters = params)
    }
}

