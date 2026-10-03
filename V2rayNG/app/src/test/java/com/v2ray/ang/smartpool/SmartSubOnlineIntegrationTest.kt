package com.v2ray.ang.smartpool

import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class SmartSubOnlineIntegrationTest {

    private fun loadMimicryProfile(): MimicryProfile {
        val stream = javaClass.classLoader?.getResourceAsStream("happ_profile.json")
        if (stream != null) {
            val json = InputStreamReader(stream).readText()
            val map = JsonUtil.fromJson(json, Map::class.java)
            if (map != null) {
                return MimicryProfile(
                    userAgent = map["UserAgent"]?.toString() ?: "Happ/2.14.0/Windows/2605071230500",
                    model = map["Model"]?.toString() ?: "WIN-ISSKDS27EMN_x86_64",
                    hwid = map["HWID"]?.toString() ?: "ac87c7e4-d900-4c3a-812d-83bce4c727c6",
                    os = map["OS"]?.toString() ?: "Windows",
                    osVer = map["OSVer"]?.toString() ?: "11_10.0.26200",
                    appVer = map["AppVer"]?.toString() ?: "2.14.0",
                    encoding = map["Encoding"]?.toString() ?: "gzip, deflate",
                    locale = map["Locale"]?.toString() ?: "RU",
                    lang = map["Lang"]?.toString() ?: "ru-RU,en,*"
                )
            }
        }
        return MimicryProfile(
            userAgent = "Happ/2.14.0/Windows/2605071230500",
            model = "WIN-ISSKDS27EMN_x86_64",
            hwid = "ac87c7e4-d900-4c3a-812d-83bce4c727c6",
            os = "Windows",
            osVer = "11_10.0.26200",
            appVer = "2.14.0",
            encoding = "gzip, deflate",
            locale = "RU",
            lang = "ru-RU,en,*"
        )
    }

    @Test
    fun testOnlineSubscriptionFetchAndDeduplication() {
        val testUrl = "https://xpnetsub.space/sub/tUXaZdTstjdS8u-A"
        val profile = loadMimicryProfile()

        println("\n==================================================")
        println("=== [SmartPool Online Test & Mimicry Inspection] ===")
        println("==================================================")
        println("[Подписка] Tier 1: Загрузка с $testUrl...")
        println("[Мимикрия] Клиент: ${profile.userAgent}")
        println("[Мимикрия] HWID: ${profile.hwid} | Модель: ${profile.model} | ОС: ${profile.os} ${profile.osVer}")

        val rawContent = SmartSubFetcher.fetchRawContentWithCascade(testUrl, profile = profile)
        if (rawContent.isBlank()) {
            println("[Подписка] ⚠️ Сетевой запрос завершился без ответа (проверьте интернет-соединение)")
            return
        }

        println("[Подписка] Tier 1 УСПЕХ: получено ${rawContent.length} байт данных")
        val preview = if (rawContent.length > 200) rawContent.substring(0, 200) + "..." else rawContent
        println("[Подписка] Сигнатура ответа: $preview")

        val nodes = SmartSubFetcher.fetchWithTierCascade(testUrl, profile = profile)
        println("[ПУЛ-НОД] 📊 Итог анализа: загружено и дедуплицировано уникальных физических нод: ${nodes.size}")

        assertTrue(nodes.isNotEmpty(), "Must parse at least 1 valid node from subscription")
        val balancer = SmartPoolBalancer(nodes)
        val leader = balancer.getActiveLeader()
        assertNotNull(leader, "SmartPoolBalancer must assign an active leader")

        println("[Оркестратор] Загружено валидных прокси для маршрутизации: ${balancer.listAll().size}")
        println("[Балансировщик] ⚡ Активный Лидер назначен: '${leader?.profile?.remarks}' (порт: 127.0.0.1:${leader?.localPort})")
        println("[Балансировщик] Горячий резерв Standbys: ${balancer.listAll().size - 1} узлов")
        println("==================================================")
        println("=== [ONLINE TEST SUCCESSFUL - SMART POOL READY] ===")
        println("==================================================\n")
    }
}
