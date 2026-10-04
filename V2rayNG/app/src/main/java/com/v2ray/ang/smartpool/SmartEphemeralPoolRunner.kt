package com.v2ray.ang.smartpool

import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Уровень 3 каскада: скрытый запуск временного инстанса SmartPool (только прокси, без тумблера VPN)
 * для обновления подписки, если все прямые запросы заблокированы.
 */
object SmartEphemeralPoolRunner {
    private val ephemeralMutex = Mutex()

    fun isCoreActive(): Boolean = CoreServiceManager.isRunning()

    suspend fun fetchViaEphemeralPool(subUrl: String, prof: MimicryProfile): String? {
        if (isCoreActive()) return null

        return ephemeralMutex.withLock {
            if (isCoreActive()) return@withLock null

            val context = AngApplication.application
            val candidates = SmartPoolManager.getValidPoolCandidates()
            if (candidates.isEmpty()) {
                LogUtil.d(SmartPoolConstants.TAG, "⚠️ [EphemeralPool] Нет доступных кандидатов для временного пула")
                return@withLock null
            }

            LogUtil.i(SmartPoolConstants.TAG, "⚡ [EphemeralPool] Запуск скрытого SmartPool без VPN (${candidates.size} нод)... премиум уровень 3")
            var controller: CoreController? = null
            try {
                CoreNativeManager.initCoreEnv(context)
                val dummyHandler = object : CoreCallbackHandler {
                    override fun startup(): Long = 0
                    override fun shutdown(): Long = 0
                    override fun onEmitStatus(l: Long, s: String?): Long = 0
                }
                val ctrl = CoreNativeManager.newCoreController(dummyHandler)
                controller = ctrl

                val limit = SmartPoolConstants.STANDBY_CAPACITY.coerceAtLeast(candidates.size.coerceAtMost(SmartPoolConstants.DEFAULT_PORT_LIMIT))
                val config = SmartPoolConfigBuilder.buildMultiInboundConfig(context, candidates, limit)
                val configJson = JsonUtil.toJson(config)
                if (configJson.isNullOrBlank()) return@withLock null

                ctrl.startLoop(configJson, 0)
                if (!ctrl.isRunning) {
                    LogUtil.w(SmartPoolConstants.TAG, "❌ [EphemeralPool] Не удалось запустить временное ядро")
                    return@withLock null
                }

                Thread.sleep(200)

                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SmartPoolConstants.BASE_POOL_PORT))
                val client = OkHttpClient.Builder()
                    .proxy(proxy)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val req = Request.Builder()
                    .url(subUrl)
                    .header("User-Agent", prof.userAgent)
                    .header("Accept-Language", prof.lang)
                    .header("Accept-Encoding", prof.encoding)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val bodyBytes = resp.body.bytes()
                    if (bodyBytes.isNotEmpty()) {
                        LogUtil.i(SmartPoolConstants.TAG, "✅ [EphemeralPool] Подписка успешно получена через скрытый SmartPool (${bodyBytes.size} байт)")
                        return@withLock SmartSubFetcher.decompressBytes(bodyBytes, resp.header("Content-Encoding"))
                    }
                }
                null
            } catch (e: Exception) {
                LogUtil.e(SmartPoolConstants.TAG, "❌ [EphemeralPool] Ошибка загрузки через скрытый пул: ${e.message}")
                null
            } finally {
                runCatching { controller?.stopLoop() }
                LogUtil.i(SmartPoolConstants.TAG, "⏹️ [EphemeralPool] Скрытый SmartPool завершил работу, порты закрыты")
            }
        }
    }
}
