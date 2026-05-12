package com.v2ray.ang.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object GzipUtil {
    fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(data)).use { 
            return it.readBytes()
        }
    }
}

