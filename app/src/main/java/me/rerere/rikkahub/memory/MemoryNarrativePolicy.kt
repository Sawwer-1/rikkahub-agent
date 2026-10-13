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
