package com.example.ApI.tools.skills

import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool that lets the LLM read any file within a skill directory (Level 3 content).
 * Used when the skill's SKILL.md references additional resources.
 */
class ReadSkillFileTool(
    private val skillsManager: SkillsStorageManager
) : Tool {

    override val id = "read_skill_file"
    override val name = "Read Skill File"
    override val description = "Read an additional file from a skill's directory. Use when a skill's instructions reference other files."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        val skillName = parameters["skill_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'skill_name' is required")
        val filePath = parameters["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'file_path' is required")

        val content = skillsManager.readSkillFile(skillName, filePath)
            ?: return ToolExecutionResult.Error("File '$filePath' not found in skill '$skillName'")

        return ToolExecutionResult.Success(content)
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
                        put("description", "Relative path of the file within the skill directory (e.g. 'FORMS.md' or 'scripts/fill_form.py')")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path") })
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
                        put("description", "Relative path of the file within the skill directory (e.g. 'FORMS.md' or 'scripts/fill_form.py')")
                    })
                })
                put("required", buildJsonArray { add("skill_name"); add("file_path") })
            }
        }

        return ToolSpecification(name = id, description = description, parameters = params)
    }
}

