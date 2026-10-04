package com.v2ray.ang.smartpool.gist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GistPoolResolverTest {

    private val samplePoolJson = """
    [
      {
        "remarks": "RU/RB Auto",
        "testUrls": [
          "https://www.gstatic.com/generate_204",
          "https://cp.cloudflare.com/generate_204"
        ],
        "baselineUrl": "https://1.1.1.1/cdn-cgi/trace"
      },
      {
        "remarks": "Overseas Auto",
        "testUrls": [
          "https://www.google.com/generate_204",
          "https://www.gstatic.com/generate_204"
        ],
        "baselineUrl": "https://1.1.1.1/cdn-cgi/trace"
      }
    ]
    """.trimIndent()

    @Test
    fun testParsePoolJson() {
        val items = GistSyncManager.parsePoolJson(samplePoolJson)
        assertEquals(2, items.size)
        assertEquals("RU/RB Auto", items[0].remarks)
        assertEquals(2, items[0].testUrls.size)
        assertTrue(items[0].testUrls.contains("https://www.gstatic.com/generate_204"))
        assertEquals("https://1.1.1.1/cdn-cgi/trace", items[0].baselineUrl)
    }
}
