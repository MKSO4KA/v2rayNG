package com.v2ray.ang.smartpool.gist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GistBlacklistTest {

    private val sampleBlacklistJson = """
    [
      {
        "pattern": "domain:rezvorck.github.io",
        "comment": "Block analytics tracking"
      },
      {
        "pattern": "domain:tigr1234566.github.io",
        "comment": "Block specific update server"
      },
      {
        "pattern": "geosite:category-ads-all",
        "comment": "Generic global ad-blocking"
      }
    ]
    """.trimIndent()

    @Test
    fun testParseBlacklistJson() {
        val items = GistSyncManager.parseBlacklistJson(sampleBlacklistJson)
        assertEquals(3, items.size)
        assertEquals("domain:rezvorck.github.io", items[0].pattern)
        assertEquals("Block analytics tracking", items[0].comment)
        assertTrue(items.any { it.pattern == "geosite:category-ads-all" })
    }
}
