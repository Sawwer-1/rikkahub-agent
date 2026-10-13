package me.rerere.rikkahub.memory.dreaming.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One durable forensics record for a Dream synthesis run failure. Enum names, opaque run ids,
 * counts and exception class/message summaries only — never model or user text.
 */
@Serializable
data class DreamSynthesisForensicsEvent(
    @SerialName("recorded_at_ms") val recordedAtEpochMs: Long,
    @SerialName("run_id") val runId: String,
    @SerialName("pair_scope") val pairScope: Boolean,
    /** COMMIT_REJECTED | COMMIT_EXCEPTION | TERMINALIZED */
    @SerialName("stage") val stage: String,
    @SerialName("reason") val reason: String? = null,
    @SerialName("failure_code") val failureCode: String? = null,
    @SerialName("exception_type") val exceptionType: String? = null,
    @SerialName("exception_message") val exceptionMessage: String? = null,
    /**
     * Top stack frames as `Class.method(File:line)`. 2026-10-12: the first device report only
     * had "STORE_FAILURE", so the fix could not be pinned to a line; frames make the next
     * failure self-locating.
     */
    @SerialName("exception_frames") val exceptionFrames: String? = null,
    @SerialName("mode") val mode: String? = null,
    @SerialName("plan_upsert_count") val planUpsertCount: Int? = null,
    @SerialName("plan_transition_count") val planTransitionCount: Int? = null,
    @SerialName("plan_claim_count") val planClaimCount: Int? = null,
    @SerialName("base_experience_epoch") val baseExperienceEpoch: Long? = null,
    @SerialName("base_applied_experience_epoch") val baseAppliedExperienceEpoch: Long? = null,
    @SerialName("base_dream_revision") val baseDreamRevision: Long? = null,
    @SerialName("input_memory_count") val inputMemoryCount: Int? = null,
)

// File-top constants: the private snapshot data class below cannot see the store's companion.
private const val DREAM_SYNTHESIS_FORENSICS_SCHEMA_VERSION = 1
private const val DREAM_SYNTHESIS_FORENSICS_PRIVACY_NOTE =
    "Only counters, enum names, opaque run ids and exception summaries are stored; " +
        "no model or user text is persisted."

@Serializable
private data class DreamSynthesisForensicsSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = DREAM_SYNTHESIS_FORENSICS_SCHEMA_VERSION,
    @SerialName("max_entries") val maxEntries: Int,
    val entries: List<DreamSynthesisForensicsEvent>,
    @SerialName("privacy_note") val privacyNote: String = DREAM_SYNTHESIS_FORENSICS_PRIVACY_NOTE,
)

/**
 * Durable forensics for Dream synthesis commit failures, written to
 * `filesDir/diagnostics/dream_synthesis_forensics.json` — the same directory the on-device
 * self-check already reads (memory_retrieval_history.json etc.).
 *
 * Why: on 2026-10-12 the run row's failure_code collapsed STORE_CORRUPTION / FENCE_CONFLICT /
 * LEASE_MISSING into STORE_FAILURE, and a terminalize race could relabel the row LEASE_EXPIRED.
 * Device evidence then showed 16+ consecutive STORE_FAILURE runs with no recoverable root cause,
 * because the only forensics channel was logcat. Upstream (AAAelina/rikkahub-agent up244.x)
 * collapses the same way; this store extends that design with durable, on-device distinguishable
 * reasons so the next failure names itself.
 *
 * 2026-10-13: the run row now names each rejection directly (no more collapse), so the sidecar's
 * remaining job is to add the abort stage and the exception summary where one code is not enough.
 * It is also readable through the `dream_view` tool's `failures` view.
 *
 * [record] never throws and never blocks the caller beyond a small atomic file write:
 * forensics is a sidecar and must not alter synthesis behavior.
 */
class DreamSynthesisForensicsStore(
    filesDir: File? = null,
    maxEntries: Int = MAX_DREAM_SYNTHESIS_FORENSICS_ENTRIES,
) {
    private val boundedMaxEntries = maxEntries.coerceIn(1, MAX_DREAM_SYNTHESIS_FORENSICS_ENTRIES)
    private val destination = filesDir?.let(::outputFile)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun record(event: DreamSynthesisForensicsEvent) {
        val file = destination ?: return
        runCatching {
            val retained = (listOf(event) + eventsInternal()).take(boundedMaxEntries)
            writeAtomically(
                destination = file,
                payload = json.encodeToString(
                    DreamSynthesisForensicsSnapshot(
                        maxEntries = boundedMaxEntries,
                        entries = retained,
                    ),
                ),
            )
        }
    }

    @Synchronized
    fun events(): List<DreamSynthesisForensicsEvent> = runCatching { eventsInternal() }
        .getOrDefault(emptyList())

    private fun eventsInternal(): List<DreamSynthesisForensicsEvent> {
        val file = destination?.takeIf(File::isFile) ?: return emptyList()
        return json.decodeFromString<DreamSynthesisForensicsSnapshot>(file.readText())
            .entries
            .take(boundedMaxEntries)
    }

    companion object {
        const val MAX_DREAM_SYNTHESIS_FORENSICS_ENTRIES = 50

        fun outputFile(filesDir: File): File =
            File(File(filesDir, "diagnostics"), "dream_synthesis_forensics.json")

        private fun writeAtomically(destination: File, payload: String) {
            val directory = requireNotNull(destination.parentFile).apply { mkdirs() }
            val temporary = Files.createTempFile(
                directory.toPath(),
                ".dream_synthesis_forensics_",
                ".tmp",
            )
            try {
                Files.write(temporary, payload.toByteArray(Charsets.UTF_8))
                Files.move(
                    temporary,
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
