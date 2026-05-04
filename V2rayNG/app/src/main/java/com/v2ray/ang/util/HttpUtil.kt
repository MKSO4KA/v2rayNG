package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.GzipSource
import okio.buffer

object HttpUtil {

    /**
     * Converts the domain part of a URL string to its IDN (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like "https://例子.中国/path" will be converted to "https://xn--fsqu00a.xn--fiqs8s/path".
     *
     * @param str The URL string to convert (can contain non-ASCII characters in the domain).
     * @return The URL string with the domain part converted to ASCII-compatible (Punycode) format.
     */
    fun toIdnUrl(str: String): String {
        val url = URL(str)
        val host = url.host
        val asciiHost = IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED)
        if (host != asciiHost) {
            return str.replace(host, asciiHost)
        } else {
            return str
        }
    }

    /**
     * Converts a Unicode domain name to its IDN (Punycode, ASCII Compatible Encoding) format.
     * If the input is an IP address or already an ASCII domain, returns the original string.
     *
     * @param domain The domain string to convert (can include non-ASCII internationalized characters).
     * @return The domain in ASCII-compatible (Punycode) format, or the original string if input is an IP or already ASCII.
     */
    fun toIdnDomain(domain: String): String {
        // Return as is if it's a pure IP address (IPv4 or IPv6)
        if (Utils.isPureIpAddress(domain)) {
            return domain
        }

        // Return as is if already ASCII (English domain or already punycode)
        if (domain.all { it.code < 128 }) {
            return domain
        }

        // Otherwise, convert to ASCII using IDN
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    /**
     * Resolves a hostname to an IP address, returns original input if it's already an IP
     *
     * @param host The hostname or IP address to resolve
     * @param ipv6Preferred Whether to prefer IPv6 addresses, defaults to false
     * @return The resolved IP address or the original input (if it's already an IP or resolution fails)
     */
    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        try {
            // If it's already an IP address, return it as a list
            if (Utils.isPureIpAddress(host)) {
                return null
            }

            // Get all IP addresses
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) {
                return null
            }

            // Sort addresses based on preference
            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedWith(compareByDescending { it is Inet6Address })
            } else {
                addresses.sortedWith(compareBy { it is Inet6Address })
            }

            val ipList = sortedAddresses.mapNotNull { it.hostAddress }

            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")

            return ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            return null
        }
    }


    /**
     * Retrieves the content of a URL as a string.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     */
    fun getUrlContent(
        url: String,
        timeout: Int,
        httpPort: Int = 0,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): String? {
        val client = buildOkHttpClient(timeout, httpPort, proxyUsername, proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        if (httpPort != 0 && !proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
        }
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Failed to get URL content, code=${response.code}")
                    return null
                }
                return response.body?.string()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get URL content", e)
        }
        return null
    }

    /**
     * Retrieves the content of a URL as a string with a custom User-Agent header.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun getUrlContentWithUserAgent(
        url: String?,
        userAgent: String?,
        timeout: Int = 15000,
        httpPort: Int = 0,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): String {
        val headers = mutableMapOf<String, String>()
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        return getUrlContentWithCustomHeaders(url, headers, timeout, httpPort, proxyUsername, proxyPassword)
    }

    /**
     * Retrieves the content of a URL as a string with custom headers (e.g. Mimicry feature).
     *
     * @param url The URL to fetch content from.
     * @param headers The Map containing header key-values.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun getUrlContentWithCustomHeaders(
        url: String?,
        headers: Map<String, String>,
        timeout: Int = 15000,
        httpPort: Int = 0,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): String {
        var currentUrl = url
        var redirects = 0
        val maxRedirects = 3

        while (redirects++ < maxRedirects) {
            if (currentUrl == null) continue
            val client = buildOkHttpClient(timeout, httpPort, proxyUsername, proxyPassword, followRedirects = false)
            
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Connection", "close")

            var hasUserAgent = false
            LogUtil.i(AppConfig.TAG, "--- HTTP Request to: $currentUrl ---")
            headers.forEach { (key, value) ->
                var finalValue = value
                // OkHttp does not natively support brotli ('br') decompression without extra interceptors.
                // If we override Accept-Encoding to explicitly include 'br', it will fail to parse the body as string.
                // Also removing 'deflate' to rely on OkHttp or manual 'gzip' safely.
                if (key.equals("Accept-Encoding", ignoreCase = true)) {
                    finalValue = finalValue.replace(Regex(",?\\s*br\\b"), "")
                                           .replace(Regex(",?\\s*deflate\\b"), "")
                                           .trim(',').trim()
                    if (finalValue.isEmpty()) finalValue = "gzip"
                }
                
                if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                requestBuilder.header(key, finalValue)
                LogUtil.i(AppConfig.TAG, "Request Header -> $key: $finalValue")
            }
            if (!hasUserAgent) {
                val defaultUa = "v2rayNG/${BuildConfig.VERSION_NAME}"
                requestBuilder.header("User-Agent", defaultUa)
                LogUtil.i(AppConfig.TAG, "Request Header (Default) -> User-Agent: $defaultUa")
            }

            if (httpPort != 0 && !proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                requestBuilder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                LogUtil.i(AppConfig.TAG, "Request Header -> Proxy-Authorization: [HIDDEN]")
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    LogUtil.i(AppConfig.TAG, "--- HTTP Response ---")
                    LogUtil.i(AppConfig.TAG, "Code: ${response.code} Message: ${response.message}")
                    response.headers.forEach { (name, value) ->
                        LogUtil.i(AppConfig.TAG, "Response Header -> $name: $value")
                    }
                    
                    when {
                        response.isRedirect -> {
                            val location = response.header("Location")
                            LogUtil.i(AppConfig.TAG, "Redirected to: $location")
                            if (location.isNullOrEmpty()) {
                                throw IOException("Redirect location not found")
                            }
                            currentUrl = resolveLocation(currentUrl, location)
                            if (currentUrl.isNullOrEmpty()) {
                                throw IOException("Failed to resolve redirect location")
                            }
                            continue
                        }

                        response.isSuccessful -> {
                            val responseBody = response.body
                            val contentEncoding = response.header("Content-Encoding")

                            val bodyStr = if (responseBody != null) {
                                // If the user explicitly sets Accept-Encoding, OkHttp disables transparent decompression.
                                // We must manually decompress gzip here.
                                if ("gzip".equals(contentEncoding, ignoreCase = true)) {
                                    LogUtil.i(AppConfig.TAG, "Manually decompressing gzip response...")
                                    val gzipSource = GzipSource(responseBody.source())
                                    gzipSource.buffer().readUtf8()
                                } else {
                                    responseBody.string()
                                }
                            } else {
                                ""
                            }

                            LogUtil.i(AppConfig.TAG, "Parsed Body length: ${bodyStr.length}.")
                            if (bodyStr.length > 200) {
                                LogUtil.d(AppConfig.TAG, "Body Preview: ${bodyStr.take(200).replace("\n", "\\n")}")
                            } else {
                                LogUtil.d(AppConfig.TAG, "Body Content: ${bodyStr.replace("\n", "\\n")}")
                            }
                            return bodyStr
                        }

                        else -> {
                            val errorBody = response.body?.string() ?: ""
                            LogUtil.e(AppConfig.TAG, "Request failed with status code ${response.code}. Body preview: ${errorBody.take(100).replace("\n", "\\n")}")
                            throw IOException("Request failed with status code ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "HTTP Request Exception: ${e.message}", e)
                throw e
            }
        }
        throw IOException("Too many redirects")
    }

    private fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request.newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                            .build()
                    }
                }
            }
        }

        return builder.build()
    }

    private fun resolveLocation(baseUrl: String, raw: String): String? {
        return try {
            val locUri = URI(raw)
            val baseUri = URI(baseUrl)
            val resolved = if (locUri.isAbsolute) locUri else baseUri.resolve(locUri)
            resolved.toURL().toString()
        } catch (_: Exception) {
            try {
                URL(raw).toString()
            } catch (_: MalformedURLException) {
                try {
                    URL(URL(baseUrl), raw).toString()
                } catch (_: MalformedURLException) {
                    null
                }
            }
        }
    }

    fun downloadToFile(
        url: String,
        targetFile: File,
        timeout: Int = 15000,
        httpPort: Int = 0,
        proxyUsername: String? = null,
        proxyPassword: String? = null
    ): Boolean {
        val client = buildOkHttpClient(timeout, httpPort, proxyUsername, proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        if (httpPort != 0 && !proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
            requestBuilder.header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Failed to download file, code=${response.code}, url=$url")
                    return false
                }
                val body = response.body ?: return false
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download file: $url", e)
            false
        }
    }
}

