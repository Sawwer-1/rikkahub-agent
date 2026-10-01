package me.rerere.rikkahub.personal.heartbeat

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool

/**
 * Filters tools before request construction. Execution-time approval is not the primary guard:
 * tools that are absent here are never disclosed to the heartbeat model.
 *
 * AAA adaptation: Tool.needsApproval is a `(JsonElement) -> Boolean` predicate here (jude had a
 * plain Boolean), so classification evaluates it against an empty argument object.
 */
class HeartbeatToolPolicy(private val config: HeartbeatConfig) {
    fun filter(
        availableTools: List<Tool>,
        mode: HeartbeatExecutionMode = HeartbeatExecutionMode.LIVE,
    ): List<Tool> =
        availableTools.mapNotNull { tool ->
            if (mode == HeartbeatExecutionMode.READ_ONLY_TEST &&
                classify(tool) != HeartbeatToolRisk.READ_ONLY
            ) {
                return@mapNotNull null
            }
            when (classify(tool)) {
                HeartbeatToolRisk.READ_ONLY,
                HeartbeatToolRisk.AUTONOMOUS -> tool.copy(needsApproval = { false })

                HeartbeatToolRisk.LOW_RISK_WRITE -> {
                    tool.copy(needsApproval = { false }).takeIf { config.allowLowRiskWrites }
                }
                HeartbeatToolRisk.HIGH_RISK -> null
            }
        }

    fun classify(tool: Tool): HeartbeatToolRisk {
        if (tool.name.startsWith(MCP_TOOL_PREFIX)) {
            return if (tool.needsApproval(JsonObject(emptyMap()))) {
                HeartbeatToolRisk.HIGH_RISK
            } else {
                HeartbeatToolRisk.AUTONOMOUS
            }
        }
        if (tool.name in config.autonomousToolNames) {
            return HeartbeatToolRisk.AUTONOMOUS
        }
        if (tool.name in config.readOnlyToolNames) {
            return HeartbeatToolRisk.READ_ONLY
        }
        if (tool.name in config.lowRiskWriteToolNames) {
            return HeartbeatToolRisk.LOW_RISK_WRITE
        }
        return HeartbeatToolRisk.HIGH_RISK
    }

    private companion object {
        const val MCP_TOOL_PREFIX = "mcp__"
    }
}

enum class HeartbeatToolRisk {
    READ_ONLY,
    LOW_RISK_WRITE,
    AUTONOMOUS,
    HIGH_RISK,
}
