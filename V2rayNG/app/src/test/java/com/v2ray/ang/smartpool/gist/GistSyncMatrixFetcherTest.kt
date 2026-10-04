package com.v2ray.ang.smartpool.gist

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GistSyncMatrixFetcherTest {

    @Test
    fun testBlankUrlReturnsEmpty() = runBlocking {
        val result = GistSyncMatrixFetcher.fetchUrlWithRace("", listOf(30001, 30002))
        assertEquals("", result)
    }

    @Test
    fun testEmptyUrlBatch() = runBlocking {
        val batch = GistSyncMatrixFetcher.fetchBatchMultiplexed(emptyList(), listOf(30001))
        assertTrue(batch.isEmpty())
    }

    @Test
    fun testGistSyncResultEvaluation() {
        val rEmpty = GistSyncResult()
        assertEquals(false, rEmpty.success)
        assertEquals(false, rEmpty.hasAnyData)

        val rPartial = GistSyncResult(rulesCount = 0, poolCount = 2, blacklistCount = 0, success = true)
        assertEquals(true, rPartial.success)
        assertEquals(true, rPartial.hasAnyData)
    }
}
