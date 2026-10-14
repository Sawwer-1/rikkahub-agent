package me.rerere.rikkahub.memory

/** Enforces truth semantics locally instead of trusting provider output. */
class MemoryNarrativePolicy {
    fun normalize(
        proposal: MemoryProposal,
        identity: MemoryNarrativeIdentity = defaultMemoryNarrativeIdentity(),
    ): MemoryProposal {
        val truthNormalized = when {
            proposal.kind == MemoryKind.THEORY && proposal.attribution == MemoryAttribution.ASSISTANT ->
                proposal.copy(truthStatus = MemoryTruthStatus.PROVISIONAL)

            else -> proposal
        }
        return truthNormalized.copy(
            title = normalizeMemoryNarrativeText(truthNormalized.title, identity),
            content = normalizeMemoryNarrativeText(truthNormalized.content, identity),
            outcome = truthNormalized.outcome?.let { normalizeMemoryNarrativeText(it, identity) },
            reason = normalizeMemoryNarrativeText(truthNormalized.reason, identity),
            tags = truthNormalized.tags.map { normalizeMemoryNarrativeText(it, identity) },
            participants = truthNormalized.attribution.canonicalParticipants(),
        )
    }

    fun normalize(
        relation: MemoryRelationProposal,
        identity: MemoryNarrativeIdentity = defaultMemoryNarrativeIdentity(),
    ): MemoryRelationProposal = relation.copy(
        description = normalizeMemoryNarrativeText(relation.description, identity),
    )
}

private fun MemoryAttribution.canonicalParticipants(): List<String> = when (this) {
    MemoryAttribution.USER -> listOf("USER")
    MemoryAttribution.ASSISTANT -> listOf("ASSISTANT")
    MemoryAttribution.SHARED -> listOf("USER", "ASSISTANT")
    MemoryAttribution.EXTERNAL,
    MemoryAttribution.UNKNOWN,
    -> emptyList()
}

/** Rewrites only the stable participant-role aliases in readable model output. */
fun normalizeMemoryNarrativeText(
    text: String,
    identity: MemoryNarrativeIdentity,
): String = ROLE_LABEL_REPLACEMENTS.fold(text) { current, (label, target) ->
        label.replace(current) {
            when (target) {
                NarrativeRole.SELF -> identity.selfName
                NarrativeRole.COMPANION -> identity.companionName
            }
        }
    }

private fun defaultMemoryNarrativeIdentity() = MemoryNarrativeIdentity(
    selfName = DEFAULT_MEMORY_NARRATIVE_SELF_NAME,
    companionName = DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME,
)

/**
 * 模型侧可读文本（工具输出等非 UI 界面）用的同一张别名表：把上游遗留的生造名与角色词统一换成
 * 中性的默认角色名。
 *
 * 为什么工具输出也要过这张表：设备自检（2026-10-14）发现 `dream_view` 直接读库，把「斯啾伊 /
 * 七七」原样回给模型；模型据此可能把这两个生造名再写进新的 claim / experience，形成回流。
 * 持久化行一律不动（claim revision 与 hash 保持稳定），只改这一份面向模型的可读文本。
 */
fun normalizeMemoryNarrativeTextForOutput(text: String): String =
    normalizeMemoryNarrativeText(text, defaultMemoryNarrativeIdentity())

private enum class NarrativeRole {
    SELF,
    COMPANION,
}

private val ROLE_LABEL_REPLACEMENTS = listOf(
    // 2026-10-13 (work order B): the upstream fork hard-coded its author's personal names
    // (斯啾伊 / 七七) into Dream experience summaries and synthesis prompts, and those names
    // have leaked into persisted claim statements on this device (7 claims, 600+ experiences).
    // They are meaningless here (the conversation has its own configured names), so they are
    //回收 as legacy aliases — every readableText() call maps them back to the configured
    // self/companion names without touching persisted rows (claim revisions stay hash-stable).
    Regex("斯啾伊") to NarrativeRole.SELF,
    Regex("七七") to NarrativeRole.COMPANION,
    Regex("(?i)\\bthe\\s+user\\b") to NarrativeRole.SELF,
    Regex("(?i)\\buser\\b") to NarrativeRole.SELF,
    Regex("用户") to NarrativeRole.SELF,
    Regex("用戶") to NarrativeRole.SELF,
    Regex("(?i)\\bthe\\s+assistant\\b") to NarrativeRole.COMPANION,
    Regex("(?i)\\bassistant\\b") to NarrativeRole.COMPANION,
    Regex("助手") to NarrativeRole.COMPANION,
    Regex("助理") to NarrativeRole.COMPANION,
)
