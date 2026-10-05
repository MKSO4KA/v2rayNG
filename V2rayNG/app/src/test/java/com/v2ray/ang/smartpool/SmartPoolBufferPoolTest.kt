package com.v2ray.ang.smartpool

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SmartPoolBufferPoolTest {

    @BeforeEach
    fun setUp() {
        SmartPoolBufferPool.clear()
    }

    @AfterEach
    fun tearDown() {
        SmartPoolBufferPool.clear()
    }

    @Test
    fun testBufferFixedSize128KB() {
        val buf = SmartPoolBufferPool.acquire()
        assertNotNull(buf)
        assertEquals(131072, buf.size, "Buffer must be exactly 128KB for 8K Remux throughput")
        SmartPoolBufferPool.release(buf)
        assertEquals(1, SmartPoolBufferPool.currentPoolSize())
    }

    @Test
    fun testBufferRecyclingZeroNewAllocations() {
        val initialBuf = SmartPoolBufferPool.acquire()
        SmartPoolBufferPool.release(initialBuf)
        val recycledBuf = SmartPoolBufferPool.acquire()
        assertTrue(initialBuf === recycledBuf, "Acquire must return the exact pooled buffer instance")
        SmartPoolBufferPool.release(recycledBuf)
    }

    @Test
    fun testPoolCapacityBoundedTo64() {
        val buffers = List(100) { SmartPoolBufferPool.acquire() }
        buffers.forEach { SmartPoolBufferPool.release(it) }
        assertTrue(
            SmartPoolBufferPool.currentPoolSize() <= SmartPoolBufferPool.MAX_POOLED_BUFFERS,
            "Pool size must never exceed MAX_POOLED_BUFFERS"
        )
        assertEquals(64, SmartPoolBufferPool.currentPoolSize())
    }

    @Test
    fun testConcurrentAcquireReleaseStress() {
        val threads = 32
        val iterations = 500
        val executor = Executors.newFixedThreadPool(threads)
        val tasks = (1..threads).map {
            Callable {
                for (i in 0 until iterations) {
                    val buf = SmartPoolBufferPool.acquire()
                    assertEquals(131072, buf.size)
                    SmartPoolBufferPool.release(buf)
                }
            }
        }
        val futures = executor.invokeAll(tasks)
        futures.forEach { it.get() }
        executor.shutdown()
        assertTrue(SmartPoolBufferPool.currentPoolSize() <= 64)
    }
}
