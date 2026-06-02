package com.example.ApI.tools.skills

import com.example.ApI.data.repository.SkillsStorageManager
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool that lets the LLM read a skill's SKILL.md body (Level 2 content).
 * This is the primary way progressive disclosure works:
 * the LLM sees the skill catalog in the system prompt (Level 1),
 * then calls this tool to load full instructions when relevant.
 */
class ReadSkillTool(
    private val skillsManager: SkillsStorageManager
) : Tool {

    override val id = "read_skill"
    override val name = "Read Skill"
    override val description = "Load the full instructions of an installed skill by name. Use when a skill from the catalog is relevant to the user's request."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        val skillName = parameters["skill_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error("Parameter 'skill_name' is required")

        val body = skillsManager.readSkillBody(skillName)
            ?: return ToolExecutionResult.Error("Skill '$skillName' not found or has no content")

        // Also list available files for the LLM to know what else it can read
        val files = skillsManager.listSkillFiles(skillName)
        val filesNote = if (files != null && files.size > 1) {
            val otherFiles = files.filter { it != "SKILL.md" }
            if (otherFiles.isNotEmpty()) {
                "\n\n---\n_Additional files available: ${otherFiles.joinToString(", ")}. Use read_skill_file to access them._"
            } else ""
        } else ""

        return ToolExecutionResult.Success(body + filesNote)
    }

    override fun getSpecification(provider: String): ToolSpecification {
        val params = when (provider) {
            "google" -> buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The name of the skill to load (from the skills catalog)")
                    })
                })
                put("required", buildJsonArray { add("skill_name") })
            }
            else -> buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("skill_name", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the skill to load (from the skills catalog)")
                    })
                })
                put("required", buildJsonArray { add("skill_name") })
            }
        }

        return ToolSpecification(name = id, description = description, parameters = params)
    }
}

