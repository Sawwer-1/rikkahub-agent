package me.rerere.rikkahub.memory.dreaming.store

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.db.entity.DreamClaimExperienceSourceEntity
import me.rerere.rikkahub.memory.dreaming.model.DreamPairScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-10-13 device evidence (2.5.11-2.5.13): every Dream run ended in a bare STORE_FAILURE and
 * the experience lag only grew (570 -> 597), because one drifted Experience source made
 * `loadClaims` return null and the whole seed looked corrupt before the invalidation path could
 * ever run. The lenient seed path now rebuilds the source pin from the recorded provenance row so
 * `deterministicInvalidations()` can classify the drift (SOURCE_MISSING / SOURCE_TOMBSTONED /
 * SOURCE_REVISION_CHANGED / SOURCE_HASH_CHANGED) and synthesis can repair the claim.
 *
 * This contract pins the rebuild: total on well-formed rows, null on malformed ones — genuine
 * corruption must stay fatal in either projection mode.
 */
class RecordedSourcePinRebuildTest {
    private val scopeId = DreamScopeId(
        DreamPairScopeId.forAssistant(Uuid.parse("11111111-2222-3333-4444-555555555555")).value,
    )

    private fun source(
        digest: String = "a".repeat(64),
        manifestHash: String = "b".repeat(64),
        epoch: Long = 7L,
    ) = DreamClaimExperienceSourceEntity(
        claimId = "claim-1",
        claimRevision = 1L,
        experienceId = "exp-1",
        experienceEpoch = epoch,
        contentDigest = digest,
        sourceManifestHash = manifestHash,
        supportType = "DIRECT",
        createdAtMs = 1L,
    )

    @Test
    fun `well-formed provenance row rebuilds the recorded pin`() {
        val pin = rebuildRecordedSourcePin(scopeId, source())

        assertNotNull(pin)
        assertEquals(scopeId, pin!!.scopeId)
        assertEquals("exp-1", pin.memoryId)
        assertEquals(7L, pin.expectedRevision)
        assertEquals("a".repeat(64), pin.expectedAuthorityFingerprint.value)
        assertEquals("b".repeat(64), pin.expectedSourceManifestHash.value)
    }

    @Test
    fun `malformed digest stays fatal`() {
        assertNull(rebuildRecordedSourcePin(scopeId, source(digest = "not-a-sha")))
    }

    @Test
    fun `uppercase digest stays fatal`() {
        assertNull(rebuildRecordedSourcePin(scopeId, source(digest = "A".repeat(64))))
    }

    @Test
    fun `non-positive epoch stays fatal`() {
        assertNull(rebuildRecordedSourcePin(scopeId, source(epoch = 0L)))
    }
}
