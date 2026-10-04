package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import java.util.Locale

object SmartRegexMatcher {
    private val FLAG_MACRO_REGEX = Regex("\\{flag:([A-Za-z]{2})\\}", RegexOption.IGNORE_CASE)

    fun countryCodeToEmoji(code: String): String {
        if (code.length != 2) return ""
        val upper = code.uppercase(Locale.ROOT)
        val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
        return String(first) + String(second)
    }

    fun getCountrySynonyms(countryCode: String): List<String> {
        val upper = countryCode.uppercase(Locale.ROOT)
        return when (upper) {
            "RU" -> listOf("Russia", "Россия", "РФ", "RU")
            "US" -> listOf("USA", "United States", "США", "US")
            "DE" -> listOf("Germany", "Германия", "DE")
            "NL" -> listOf("Netherlands", "Нидерланды", "NL")
            "GB", "UK" -> listOf("United Kingdom", "UK", "Britain", "Великобритания", "GB")
            "FR" -> listOf("France", "Франция", "FR")
            "FI" -> listOf("Finland", "Финляндия", "FI")
            "PL" -> listOf("Poland", "Польша", "PL")
            "KZ" -> listOf("Kazakhstan", "Казахстан", "KZ")
            "TR" -> listOf("Turkey", "Турция", "TR")
            "JP" -> listOf("Japan", "Япония", "JP")
            "IT" -> listOf("Italy", "Италия", "IT")
            "EE" -> listOf("Estonia", "Эстония", "EE")
            "BY" -> listOf("Belarus", "Беларусь", "Белоруссия", "BY")
            "IN" -> listOf("India", "Индия", "IN")
            "HK" -> listOf("Hong Kong", "Гонконг", "HK")
            "SG" -> listOf("Singapore", "Сингапур", "SG")
            else -> listOf(upper)
        }
    }

    fun expandFlagMacros(pattern: String): String {
        if (!pattern.contains("{flag:", ignoreCase = true)) return pattern
        return FLAG_MACRO_REGEX.replace(pattern) { matchResult ->
            val code = matchResult.groupValues[1].uppercase(Locale.ROOT)
            val emoji = countryCodeToEmoji(code)
            val synonyms = getCountrySynonyms(code)
            val escapedSynonyms = synonyms.joinToString("|") { Regex.escape(it) }
            val emojiEscaped = Regex.escape(emoji)
            "(?:$emojiEscaped(?:\\uFE0F)?|(?<!\\p{L})(?i:(?:$escapedSynonyms))(?!\\p{L})|\\[(?i:$code)\\])"
        }
    }

    fun normalizePattern(raw: String): String {
        var p = raw.trim()
        if (p.contains("\\\\")) {
            p = p.replace("\\\\", "\\")
        }
        p = p.replace("(?i)", "(?iu)").replace("(?i:", "(?iu:")
        if ((p.startsWith("(?=") || p.startsWith("(?!")) && !p.startsWith("^")) {
            p = "^$p"
        }
        return p
    }

    fun compileSafe(pattern: String?): Regex? {
        if (pattern.isNullOrBlank()) return null
        val normalized = normalizePattern(pattern)
        val expanded = expandFlagMacros(normalized)
        return runCatching { Regex(expanded) }.getOrNull()
    }

    fun matches(pattern: String?, text: String): Boolean {
        if (pattern.isNullOrBlank()) return true
        val regex = compileSafe(pattern) ?: return false
        return regex.containsMatchIn(text)
    }

    fun filter(pattern: String?, profiles: List<ProfileItem>): List<ProfileItem> {
        if (pattern.isNullOrBlank()) return profiles
        val regex = compileSafe(pattern) ?: return profiles
        return profiles.filter { regex.containsMatchIn(it.remarks) }
    }
}
