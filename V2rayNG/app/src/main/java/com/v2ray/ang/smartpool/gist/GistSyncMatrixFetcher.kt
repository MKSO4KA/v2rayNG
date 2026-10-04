package com.v2ray.ang.smartpool.gist

import com.v2ray.ang.AppConfig
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.smartpool.SmartPoolManager
import com.v2ray.ang.smartpool.SmartSubFetcher
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object GistSyncMatrixFetcher {
    private const val SOCKET_TIMEOUT_SECONDS = 5L

    private fun createOkHttpClient(proxyPort: Int?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (proxyPort != null && proxyPort > 0) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))
        }
        return builder.build()
    }

    suspend fun fetchUrlWithRace(targetUrl: String, availablePorts: List<Int>): String {
        if (targetUrl.isBlank()) return ""
        val ports = availablePorts.distinct().take(12)
        if (ports.isEmpty()) {
            return SmartSubFetcher.fetchRawContentWithCascade(targetUrl)
        }

        val deferredResult = CompletableDeferred<String>()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)

        ports.forEach { port ->
            scope.launch {
                runCatching {
                    val client = createOkHttpClient(port)
                    val request = Request.Builder().url(targetUrl).build()
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyText = resp.body?.string().orEmpty()
                            if (bodyText.isNotBlank()) {
                                deferredResult.complete(bodyText)
                                job.cancel()
                            }
                        }
                    }
                }
            }
        }

        scope.launch {
            delay(1000)
            if (!deferredResult.isCompleted) {
                runCatching {
                    val directClient = createOkHttpClient(null)
                    val req = Request.Builder().url(targetUrl).build()
                    directClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val text = resp.body?.string().orEmpty()
                            if (text.isNotBlank()) {
                                deferredResult.complete(text)
                                job.cancel()
                            }
                        }
                    }
                }
            }
        }

        val winner = withTimeoutOrNull(6000L) {
            deferredResult.await()
        }
        job.cancel()

        return if (!winner.isNullOrBlank()) {
            winner
        } else {
            LogUtil.w(SmartPoolConstants.TAG, "[RaceMatrix] Falling back to cascade fetch for: $targetUrl")
            SmartSubFetcher.fetchRawContentWithCascade(targetUrl)
        }
    }

    suspend fun fetchBatchMultiplexed(
        urlList: List<String>,
        availablePorts: List<Int>
    ): Map<String, String> = coroutineScope {
        val targets = urlList.filter { it.isNotBlank() }
        val deferredPairs = targets.map { targetUrl ->
            targetUrl to this@coroutineScope.async { fetchUrlWithRace(targetUrl, availablePorts) }
        }
        val results = mutableMapOf<String, String>()
        for ((url, deferred) in deferredPairs) {
            val content = deferred.await()
            if (content.isNotBlank()) {
                results[url] = content
            }
        }
        results
    }
}
