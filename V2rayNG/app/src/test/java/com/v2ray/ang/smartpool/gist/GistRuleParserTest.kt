package com.v2ray.ang.smartpool.gist

import com.v2ray.ang.smartpool.SmartRegexMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GistRuleParserTest {

    private val sampleJson = """
    [
      {
        "remarks": "RU/RB Auto",
        "regex": "^(?=.*{flag:RU})(?!.*(?i)(?:ост[а-я\\.]*|left|remain|expire|day)\\d+[дd]).+$",
        "strategy": "lowest_latency",
        "interval": "1m",
        "tolerance": 30.0,
        "validationMethod": "normal_ping",
        "portLimit": 256,
        "subUpdateInterval": "60m"
      },
      {
        "remarks": "Overseas Auto",
        "regex": "^(?!.*{flag:RU})(?!.*(?i)(?:ост[а-я\\.]*|left|remain|expire|day)\\d+[дd]).+$",
        "strategy": "lowest_latency",
        "interval": "20s",
        "tolerance": 30.0,
        "validationMethod": "normal_ping",
        "portLimit": 256,
        "subUpdateInterval": "60m"
      }
    ]
    """.trimIndent()

    @Test
    fun testParseRulesJson() {
        val rules = GistSyncManager.parseRulesJson(sampleJson)
        assertEquals(2, rules.size)
        assertEquals("RU/RB Auto", rules[0].remarks)
        assertEquals("1m", rules[0].interval)
        assertEquals(30.0, rules[0].tolerance, 0.001)
        assertEquals("lowest_latency", rules[0].strategy)

        assertEquals("Overseas Auto", rules[1].remarks)
        assertEquals("20s", rules[1].interval)
    }

    @Test
    fun testRegexFlagExpansion() {
        val rules = GistSyncManager.parseRulesJson(sampleJson)
        val ruRegex = rules[0].regex
        val compiled = SmartRegexMatcher.compileSafe(ruRegex)
        assertNotNull(compiled)
        assertTrue(SmartRegexMatcher.matches(ruRegex, "🇷🇺 Moscow Highspeed"))
        assertTrue(SmartRegexMatcher.matches(ruRegex, "Russia Premium 1"))
    }
}
