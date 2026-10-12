package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the widening plan that keeps production retrieval aligned with the JVM evaluation index.
 * Production FTS joins terms into an implicit AND; realistic chat messages therefore returned
 * zero matches while [me.rerere.rikkahub.memory.eval.ChineseMemoryRetrievalEvalTest]'s in-memory
 * index matches on ANY term. Device evidence 2026-10-12: memory_count = 0 with data present.
 */
class MemorySearchFallbackTermsTest {
    @Test
    fun `single term plans nothing to widen`() {
        assertEquals(listOf("银狼"), memorySearchFallbackTerms("银狼"))
        assertTrue(memorySearchFallbackTerms("银狼").size < 2)
    }

    @Test
    fun `multi term query keeps every distinct term in order`() {
        assertEquals(
            listOf("银狼", "自检", "前端"),
            memorySearchFallbackTerms("银狼 自检 前端"),
        )
    }

    @Test
    fun `duplicates and blank padding collapse`() {
        assertEquals(
            listOf("a", "b"),
            memorySearchFallbackTerms("  a   b  a  "),
        )
    }

    @Test
    fun `plan is bounded so a long message cannot fan out unbounded queries`() {
        val many = (1..40).joinToString(" ") { "t$it" }
        val plan = memorySearchFallbackTerms(many)
        assertEquals(MAX_MEMORY_SEARCH_FALLBACK_TERMS, plan.size)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5", "t6"), plan)
    }

    @Test
    fun `empty query plans nothing`() {
        assertTrue(memorySearchFallbackTerms("").isEmpty())
        assertTrue(memorySearchFallbackTerms("   ").isEmpty())
    }
}
