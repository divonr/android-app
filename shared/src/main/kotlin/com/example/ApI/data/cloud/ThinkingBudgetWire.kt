package com.example.ApI.data.cloud

import com.example.ApI.data.model.ThinkingBudgetValue

/**
 * The `thinkingBudget` string of the run / send requests (CLOUD_RUNS_PLAN.md).
 *
 * Legacy forms (what the web client sends): `none`, `low`, `medium`, `high`, `max` (= high) and
 * a plain integer (token budget).  They cannot carry every [ThinkingBudgetValue] a device has
 * (effort levels such as `minimal` / `xhigh`, or the explicit effort `none`, which a provider
 * treats differently from "no budget"), so two explicit forms exist as well:
 * `effort:<level>` and `tokens:<count>`.
 *
 * [encode] uses a legacy form whenever [decode] gives the same value back from it.
 */
object ThinkingBudgetWire {
    private const val EFFORT_PREFIX = "effort:"
    private const val TOKENS_PREFIX = "tokens:"
    private val legacyEfforts = setOf("low", "medium", "high")

    fun encode(value: ThinkingBudgetValue): String = when (value) {
        ThinkingBudgetValue.None -> "none"
        is ThinkingBudgetValue.Effort -> if (value.level in legacyEfforts) value.level else EFFORT_PREFIX + value.level
        is ThinkingBudgetValue.Tokens -> value.count.toString()
    }

    fun decode(raw: String): ThinkingBudgetValue {
        if (raw.startsWith(EFFORT_PREFIX, ignoreCase = true)) {
            return ThinkingBudgetValue.Effort(raw.substring(EFFORT_PREFIX.length))
        }
        if (raw.startsWith(TOKENS_PREFIX, ignoreCase = true)) {
            return raw.substring(TOKENS_PREFIX.length).trim().toIntOrNull()
                ?.let { ThinkingBudgetValue.Tokens(it) } ?: ThinkingBudgetValue.None
        }
        return when (raw.lowercase()) {
            "none" -> ThinkingBudgetValue.None
            "low" -> ThinkingBudgetValue.Effort("low")
            "medium" -> ThinkingBudgetValue.Effort("medium")
            "high" -> ThinkingBudgetValue.Effort("high")
            "max" -> ThinkingBudgetValue.Effort("high")
            else -> raw.toIntOrNull()?.let { ThinkingBudgetValue.Tokens(it) } ?: ThinkingBudgetValue.None
        }
    }
}
