package me.rerere.rikkahub.subagent

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.generationFinalizationStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentExecutionProfilePolicyTest {
    private val nameModelA = Uuid.random()
    private val nameModelB = Uuid.random()
    private val nameCandidates = listOf(
        SubAgentModelCandidate(nameModelA, "deepseek-v4-flash", "DeepSeek V4 Flash"),
        SubAgentModelCandidate(nameModelB, "claude-3-5-sonnet", "Claude 3.5 Sonnet"),
    )

    @Test
    fun `dispatch model_id resolves by modelId ignoring case`() {
        val resolution = resolveSubAgentExecutionProfile(
            runId = "run-name-1",
            request = SubAgentRequest(task = "research", modelId = "DEEPSEEK-V4-FLASH"),
            inputs = SubAgentExecutionInputs(
                parentEffectiveModelId = Uuid.random(),
                assistantDefaultModelId = null,
                assistantSystemPrompt = "",
                availableModelIds = setOf(nameModelA, nameModelB),
                callerToolNames = emptySet(),
                headlessToolNames = emptySet(),
                modelCandidates = nameCandidates,
            ),
        ) as SubAgentExecutionProfileResolution.Resolved

        assertEquals(nameModelA, resolution.profile.effectiveModelId)
    }

    @Test
    fun `dispatch model_id resolves by displayName ignoring case`() {
        val resolution = resolveSubAgentExecutionProfile(
            runId = "run-name-2",
            request = SubAgentRequest(task = "research", modelId = "claude 3.5 sonnet"),
            inputs = SubAgentExecutionInputs(
                parentEffectiveModelId = Uuid.random(),
                assistantDefaultModelId = null,
                assistantSystemPrompt = "",
                availableModelIds = setOf(nameModelA, nameModelB),
                callerToolNames = emptySet(),
                headlessToolNames = emptySet(),
                modelCandidates = nameCandidates,
            ),
        ) as SubAgentExecutionProfileResolution.Resolved

        assertEquals(nameModelB, resolution.profile.effectiveModelId)
    }

    @Test
    fun `unknown model name fails loudly with the full candidate list`() {
        val resolution = resolveSubAgentExecutionProfile(
            runId = "run-name-3",
            request = SubAgentRequest(task = "research", modelId = "gpt-9-hallucinated"),
            inputs = SubAgentExecutionInputs(
                parentEffectiveModelId = nameModelA,
                assistantDefaultModelId = null,
                assistantSystemPrompt = "",
                availableModelIds = setOf(nameModelA, nameModelB),
                callerToolNames = emptySet(),
                headlessToolNames = emptySet(),
                modelCandidates = nameCandidates,
            ),
        ) as SubAgentExecutionProfileResolution.Rejected

        assertEquals("unknown_model", resolution.error)
        assertTrue(resolution.detail.contains("Available chat models"))
        assertTrue(resolution.detail.contains("deepseek-v4-flash"))
        assertTrue(resolution.detail.contains("Claude 3.5 Sonnet"))
        assertTrue(resolution.detail.contains(nameModelB.toString()))
    }

    @Test
    fun `ambiguous displayName match is rejected with a self-correct hint`() {
        val dupA = Uuid.random()
        val dupB = Uuid.random()
        val resolution = resolveSubAgentExecutionProfile(
            runId = "run-name-4",
            request = SubAgentRequest(task = "research", modelId = "Same Display"),
            inputs = SubAgentExecutionInputs(
                parentEffectiveModelId = nameModelA,
                assistantDefaultModelId = null,
                assistantSystemPrompt = "",
                availableModelIds = setOf(dupA, dupB),
                callerToolNames = emptySet(),
                headlessToolNames = emptySet(),
                modelCandidates = listOf(
                    SubAgentModelCandidate(dupA, "model-one", "Same Display"),
                    SubAgentModelCandidate(dupB, "model-two", "same display"),
                ),
            ),
        ) as SubAgentExecutionProfileResolution.Rejected

        assertEquals("ambiguous_model", resolution.error)
        assertTrue(resolution.detail.contains("matches multiple models"))
    }

    @Test
    fun `request overrides become the effective child execution profile`() {
        val parentModel = Uuid.random()
        val assistantDefaultModel = Uuid.random()
        val requestedModel = Uuid.random()

        val resolution = resolveSubAgentExecutionProfile(
            runId = "run-1",
            request = SubAgentRequest(
                task = "research",
                modelId = requestedModel.toString(),
                systemPrompt = "  Act as a source auditor.  ",
                tools = listOf("search_web"),
                maxTrips = 2,
            ),
            inputs = SubAgentExecutionInputs(
                parentEffectiveModelId = parentModel,
                assistantDefaultModelId = assistantDefaultModel,
                assistantSystemPrompt = "assistant child prompt",
                availableModelIds = setOf(parentModel, assistantDefaultModel, requestedModel),
                callerToolNames = setOf("search_web", "web_fetch", "workspace_shell"),
                headlessToolNames = setOf("search_web", "web_fetch"),
            ),
        ) as SubAgentExecutionProfileResolution.Resolved

        assertEquals(requestedModel, resolution.profile.effectiveModelId)
        assertEquals(SubAgentPromptSource.REQUEST, resolution.profile.promptSource)
        assertEquals("Act as a source auditor.", resolution.profile.effectiveSystemPrompt)
        assertEquals(setOf("search_web"), resolution.profile.effectiveToolNames)
        assertEquals(2, resolution.profile.maxToolTrips)
    }

    @Test
    fun `explicit tool restrictions report unknown unauthorized and headless unavailable separately`() {
        val model = Uuid.random()
        val inputs = SubAgentExecutionInputs(
            parentEffectiveModelId = model,
            assistantDefaultModelId = null,
            assistantSystemPrompt = "",
            availableModelIds = setOf(model),
            callerToolNames = setOf("search_web", "workspace_shell"),
            headlessToolNames = setOf("search_web", "web_fetch"),
            knownToolNames = setOf("search_web", "workspace_shell", "web_fetch"),
        )

        fun rejectFor(tool: String) = resolveSubAgentExecutionProfile(
            runId = "run-$tool",
            request = SubAgentRequest(task = "research", tools = listOf(tool)),
            inputs = inputs,
        ) as SubAgentExecutionProfileResolution.Rejected

        assertEquals("unknown_tool", rejectFor("made_up").error)
        assertEquals("tool_not_authorized", rejectFor("web_fetch").error)
        assertEquals("tool_unavailable_headless", rejectFor("workspace_shell").error)
    }

    @Test
    fun `headless tool policy removes interactive and recursive surfaces`() {
        val callerTools = setOf(
            "search_web",
            "web_fetch",
            "workspace_shell",
            "browser_open",
            "mcp__trusted__read",
            "ask_user",
            "launch_app",
            "take_photo",
            "subagent_dispatch",
            "research_start",
        )

        assertEquals(
            setOf(
                "search_web",
                "web_fetch",
                "workspace_shell",
                "browser_open",
                "mcp__trusted__read",
            ),
            subAgentHeadlessToolNames(callerTools),
        )
    }

    @Test
    fun `two tool trips reserve one tool-free final answer after the second batch`() {
        val profile = SubAgentExecutionProfile(
            runId = "run-1",
            effectiveModelId = Uuid.random(),
            promptSource = SubAgentPromptSource.DEFAULT,
            effectiveSystemPrompt = SubAgentDefaults.DEFAULT_SYSTEM_PROMPT,
            effectiveToolNames = setOf("search_web"),
            maxToolTrips = 2,
        )
        val maxSteps = profile.generationMaxSteps()

        assertEquals(3, maxSteps)
        assertFalse(generationFinalizationStep(0, maxSteps, false, false).forceFinalization)
        assertFalse(generationFinalizationStep(1, maxSteps, false, false).forceFinalization)
        val secondBatchBoundary = generationFinalizationStep(2, maxSteps, false, false)
        assertTrue(secondBatchBoundary.forceFinalization)
        assertFalse(secondBatchBoundary.skipResumableTools)
        assertTrue(generationFinalizationStep(3, maxSteps, false, false).skipResumableTools)
    }
}
