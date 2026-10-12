package me.rerere.rikkahub.memory.dreaming.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DreamSynthesisForensicsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `record persists events to the diagnostics file and read back round trips`() {
        val store = DreamSynthesisForensicsStore(filesDir = temp.root)

        store.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = 1_000L,
                runId = "run-1",
                pairScope = true,
                stage = "COMMIT_EXCEPTION",
                exceptionType = "android.database.sqlite.SQLiteFullException",
                exceptionMessage = "disk I/O error (code 1294)",
                mode = "FULL",
                planUpsertCount = 12,
                planTransitionCount = 3,
                planClaimCount = 15,
                baseExperienceEpoch = 548L,
                baseAppliedExperienceEpoch = 0L,
                baseDreamRevision = 7L,
                inputMemoryCount = 22,
            ),
        )
        store.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = 2_000L,
                runId = "run-2",
                pairScope = true,
                stage = "TERMINALIZED",
                reason = "MEMORY_EPOCH_CONFLICT",
                failureCode = "MEMORY_EPOCH_CONFLICT",
            ),
        )

        val readBack = DreamSynthesisForensicsStore(filesDir = temp.root).events()
        assertEquals(2, readBack.size)
        // Newest first.
        assertEquals("run-2", readBack.first().runId)
        assertEquals("MEMORY_EPOCH_CONFLICT", readBack.first().reason)
        assertEquals("run-1", readBack.last().runId)
        assertEquals(
            "android.database.sqlite.SQLiteFullException",
            readBack.last().exceptionType,
        )
        assertEquals(548L, readBack.last().baseExperienceEpoch)
    }

    @Test
    fun `record is bounded and never throws on a hostile destination`() {
        // maxEntries=1 keeps only the newest event.
        val bounded = DreamSynthesisForensicsStore(filesDir = temp.root, maxEntries = 1)
        (1..5).forEach { index ->
            bounded.record(
                DreamSynthesisForensicsEvent(
                    recordedAtEpochMs = index.toLong(),
                    runId = "run-$index",
                    pairScope = false,
                    stage = "COMMIT_REJECTED",
                    reason = "FENCE_CONFLICT",
                ),
            )
        }
        val events = bounded.events()
        assertEquals(1, events.size)
        assertEquals("run-5", events.single().runId)

        // A corrupt/undecodable destination must never break the synthesis caller: record
        // swallows the failure (and may repair the file on a later write).
        val hostileRoot = temp.newFolder("hostile")
        java.io.File(hostileRoot, "diagnostics").writeText("}{ not json")
        val broken = DreamSynthesisForensicsStore(filesDir = hostileRoot)
        broken.record(
            DreamSynthesisForensicsEvent(
                recordedAtEpochMs = 9L,
                runId = "run-9",
                pairScope = false,
                stage = "COMMIT_REJECTED",
                reason = "STORE_CORRUPTION",
            ),
        )
    }
}
