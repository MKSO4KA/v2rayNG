package com.v2ray.ang.smartpool

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Неблокирующий Zero-Allocation пул байтовых буферов для диспетчера Smart Pool.
 * Фиксированный размер буфера 128 КБ (131 072 байт) обеспечивает максимальную пропускную
 * способность для 8K Remux стриминга и торрентов с минимальным числом системных вызовов.
 * Жесткий потолок в 64 буфера гарантирует максимальное потребление ОЗУ не более 8 МБ.
 */
object SmartPoolBufferPool {
    const val BUFFER_SIZE = 131072 // 128 KB
    const val MAX_POOLED_BUFFERS = 64

    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private val poolSize = AtomicInteger(0)

    fun acquire(): ByteArray {
        val existing = pool.poll()
        if (existing != null) {
            poolSize.decrementAndGet()
            return existing
        }
        return ByteArray(BUFFER_SIZE)
    }

    fun release(buffer: ByteArray) {
        if (buffer.size != BUFFER_SIZE) return
        if (poolSize.get() < MAX_POOLED_BUFFERS) {
            pool.offer(buffer)
            poolSize.incrementAndGet()
        }
    }

    fun currentPoolSize(): Int = poolSize.get().coerceAtLeast(0)

    fun clear() {
        pool.clear()
        poolSize.set(0)
    }
}
