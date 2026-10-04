package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartRegexMatcherTest {

    private fun createNode(remarks: String): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            this.remarks = remarks
            this.server = "1.2.3.4"
            this.serverPort = "443"
        }
    }

    @Test
    fun testCountryCodeToEmoji() {
        assertEquals("🇷🇺", SmartRegexMatcher.countryCodeToEmoji("RU"))
        assertEquals("🇺🇸", SmartRegexMatcher.countryCodeToEmoji("US"))
        assertEquals("🇩🇪", SmartRegexMatcher.countryCodeToEmoji("DE"))
        assertEquals("🇳🇱", SmartRegexMatcher.countryCodeToEmoji("NL"))
        assertEquals("🇰🇿", SmartRegexMatcher.countryCodeToEmoji("KZ"))
    }

    @Test
    fun testFlagMacroExpansion() {
        val input = "(?!.*{flag:RU}).+$"
        val expanded = SmartRegexMatcher.expandFlagMacros(input)
        assertTrue(expanded.contains("🇷🇺"))
        assertTrue(expanded.contains("Russia") || expanded.contains("Россия"))
    }

    @Test
    fun testAutoAnchoringFiltersRussianNodesEvenWithoutCaret() {
        // User entered pattern without leading ^ anchor
        val unanchoredPattern = "(?!.*{flag:RU})(?!.*(?i)(?:ост[а-я.]*|left|remain|expire|day|\\d+[dд]|\\(ai\\)|\\bai\\b)).+$"

        val ruFlagNode1 = createNode("🇷🇺  Россия #1")
        val ruFlagNode2 = createNode("[VLESS] 🇷🇺 Moscow | TCP-1")
        val ruFlagNodeWithVariation = createNode("[VLESS] \uD83C\uDDF7\uD83C\uDDFA\uFE0F Saint Petersburg")
        val ruTextNode = createNode("[VLESS] Russia Fast TCP")
        val ruBracketNode = createNode("[RU] Premium Server")

        val validItaly = createNode("[VLESS] 🇮🇹 Italy | TCP-1")
        val validGermany = createNode("🇩🇪  Германия #3")
        val validNetherlands = createNode("🇳🇱  Нидерланды #3")
        val validKazakhstan = createNode("🇰🇿 Казахстан #1")

        println("[Test Log] Testing unanchored pattern auto-normalization: $unanchoredPattern")
        val normalized = SmartRegexMatcher.normalizePattern(unanchoredPattern)
        println("[Test Log] Normalized pattern: $normalized")
        assertTrue(normalized.startsWith("^"), "Lookahead pattern must be auto-anchored with ^")

        assertFalse(SmartRegexMatcher.matches(unanchoredPattern, ruFlagNode1.remarks), "Must exclude '🇷🇺  Россия #1'")
        assertFalse(SmartRegexMatcher.matches(unanchoredPattern, ruFlagNode2.remarks), "Must exclude '🇷🇺 Moscow'")
        assertFalse(SmartRegexMatcher.matches(unanchoredPattern, ruFlagNodeWithVariation.remarks), "Must exclude variation selector flag")
        assertFalse(SmartRegexMatcher.matches(unanchoredPattern, ruTextNode.remarks), "Must exclude 'Russia Fast TCP'")
        assertFalse(SmartRegexMatcher.matches(unanchoredPattern, ruBracketNode.remarks), "Must exclude '[RU]'")

        assertTrue(SmartRegexMatcher.matches(unanchoredPattern, validItaly.remarks), "Must include Italy")
        assertTrue(SmartRegexMatcher.matches(unanchoredPattern, validGermany.remarks), "Must include Germany")
        assertTrue(SmartRegexMatcher.matches(unanchoredPattern, validNetherlands.remarks), "Must include Netherlands")
        assertTrue(SmartRegexMatcher.matches(unanchoredPattern, validKazakhstan.remarks), "Must include Kazakhstan")
    }

    @Test
    fun testDoubleEscapedBackslashesNormalization() {
        // User pasted double backslashes from raw JSON/code snippet
        val doubleEscaped = "(?!.*{flag:RU})(?!.*(?i)(?:ост[а-я\\.]*|left|remain|expire|day|\\\\d+[dд]|\\\\(ai\\\\)|\\\\bai\\\\b)).+$"

        val nodeRemain1 = createNode("[VLESS] 🇩🇪 Germany 5d left")
        val nodeRemain2 = createNode("[VLESS] 🇩🇪 Germany 10д осталось")
        val nodeAi1 = createNode("[VLESS] 🇫🇮 Finland (ai)")
        val nodeAi2 = createNode("[VLESS] 🇫🇮 Finland ai dedicated")
        val nodeValid = createNode("[VLESS] 🇫🇮 Finland Fast")

        assertFalse(SmartRegexMatcher.matches(doubleEscaped, nodeRemain1.remarks))
        assertFalse(SmartRegexMatcher.matches(doubleEscaped, nodeRemain2.remarks))
        assertFalse(SmartRegexMatcher.matches(doubleEscaped, nodeAi1.remarks))
        assertFalse(SmartRegexMatcher.matches(doubleEscaped, nodeAi2.remarks))
        assertTrue(SmartRegexMatcher.matches(doubleEscaped, nodeValid.remarks))
    }

    @Test
    fun testVariousCountryMacros() {
        val usOnlyPattern = "{flag:US}"
        val usNode = createNode("🇺🇸 USA | XHTTP")
        val ukNode = createNode("🇬🇧 United Kingdom | TCP")
        assertTrue(SmartRegexMatcher.matches(usOnlyPattern, usNode.remarks))
        assertFalse(SmartRegexMatcher.matches(usOnlyPattern, ukNode.remarks))

        val deFilter = "^(?!.*{flag:DE}).+$"
        val deNode = createNode("🇩🇪 Германия #1")
        val nlNode = createNode("🇳🇱 Нидерланды #1")
        assertFalse(SmartRegexMatcher.matches(deFilter, deNode.remarks))
        assertTrue(SmartRegexMatcher.matches(deFilter, nlNode.remarks))
    }

    @Test
    fun testStopWordsFiltering() {
        val pattern = "^(?!.*(?i)(?:ост[а-я.]*|left|expire|traffic)).+$"
        val n1 = createNode("Осталось 5 дней")
        val n2 = createNode("10 GB traffic")
        val n3 = createNode("Subscription expire soon")
        val n4 = createNode("🇩🇪 Fast Server")

        val list = listOf(n1, n2, n3, n4)
        val filtered = SmartRegexMatcher.filter(pattern, list)
        assertEquals(1, filtered.size)
        assertEquals("🇩🇪 Fast Server", filtered[0].remarks)
    }
}
