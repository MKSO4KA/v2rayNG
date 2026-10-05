package com.v2ray.ang.smartpool

import com.v2ray.ang.util.LogUtil
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SmartPoolDispatcher(
    private val balancer: SmartPoolBalancer,
    private val listenPort: Int = SmartPoolConstants.DISPATCHER_PORT
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            pool.execute { listenLoop() }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            runCatching { serverSocket?.close() }
            activeSockets.forEach { runCatching { it.close() } }
            activeSockets.clear()
            pool.shutdownNow()
        }
    }

    private fun listenLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort), 256)
            serverSocket = ss
            LogUtil.i(SmartPoolConstants.TAG, "SmartPoolDispatcher listening on 127.0.0.1:$listenPort")
            while (isRunning.get()) {
                val client = serverSocket?.accept() ?: break
                activeSockets.add(client)
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                LogUtil.e(SmartPoolConstants.TAG, "Dispatcher server socket error", e)
            }
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    private fun handleClient(client: Socket) {
        balancer.activeConnections.incrementAndGet()
        var targetNode: SmartNodeState? = null
        var isHttpConnect = false
        try {
            client.soTimeout = 10000
            client.tcpNoDelay = true
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            val ver = cin.readByte().toInt() and 0xFF
            isHttpConnect = (ver == 0x43)
            var rawReq: ByteArray
            if (ver == 0x05) {
                val nMethods = cin.readByte().toInt() and 0xFF
                val methods = ByteArray(nMethods)
                cin.readFully(methods)

                cout.write(byteArrayOf(0x05, 0x00))
                cout.flush()

                val reqVer = cin.readByte().toInt() and 0xFF
                val cmd = cin.readByte().toInt() and 0xFF
                val rsv = cin.readByte().toInt() and 0xFF
                val atyp = cin.readByte().toInt() and 0xFF

                if (reqVer != 0x05 || (cmd != 0x01 && cmd != 0x03)) {
                    cout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    cout.flush()
                    return
                }

                val addrBytes = when (atyp) {
                    0x01 -> ByteArray(4).also { cin.readFully(it) }
                    0x03 -> {
                        val len = cin.readByte().toInt() and 0xFF
                        ByteArray(1 + len).apply { this[0] = len.toByte(); cin.readFully(this, 1, len) }
                    }
                    0x04 -> ByteArray(16).also { cin.readFully(it) }
                    else -> {
                        cout.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        cout.flush()
                        return
                    }
                }
                val portBytes = ByteArray(2)
                cin.readFully(portBytes)

                val req = ByteArray(4 + addrBytes.size + 2)
                req[0] = 0x05
                req[1] = cmd.toByte()
                req[2] = 0x00
                req[3] = atyp.toByte()
                System.arraycopy(addrBytes, 0, req, 4, addrBytes.size)
                System.arraycopy(portBytes, 0, req, 4 + addrBytes.size, 2)
                rawReq = req
            } else if (isHttpConnect) {
                val lineSb = java.lang.StringBuilder("C")
                var b: Int
                while (cin.read().also { b = it } != -1) {
                    val ch = b.toChar()
                    lineSb.append(ch)
                    if (ch == '\n') break
                }
                var prev = 0
                while (cin.read().also { b = it } != -1) {
                    if (prev == '\n'.code && b == '\r'.code) {
                        cin.read()
                        break
                    }
                    if (prev == '\n'.code && b == '\n'.code) break
                    prev = b
                }
                val parts = lineSb.toString().trim().split(" ")
                val hostPort = if (parts.size >= 2) parts[1] else return
                val host = hostPort.substringBeforeLast(":")
                val port = hostPort.substringAfterLast(":").toIntOrNull() ?: 443
                val hostBytes = host.toByteArray(Charsets.UTF_8)
                val req = ByteArray(4 + 1 + hostBytes.size + 2)
                req[0] = 0x05
                req[1] = 0x01
                req[2] = 0x00
                req[3] = 0x03
                req[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
                req[req.size - 2] = (port shr 8).toByte()
                req[req.size - 1] = (port and 0xFF).toByte()
                rawReq = req
            } else {
                return
            }

            targetNode = balancer.getActiveLeader()
            var xraySocket: Socket? = null
            var connectSuccess = false

            for (attempt in 0..1) {
                val currentNode = targetNode ?: break
                try {
                    val s = Socket()
                    s.reuseAddress = true
                    s.connect(InetSocketAddress("127.0.0.1", currentNode.localPort), 8000)
                    s.soTimeout = 8000
                    s.tcpNoDelay = true
                    s.sendBufferSize = SmartPoolBufferPool.BUFFER_SIZE
                    s.receiveBufferSize = SmartPoolBufferPool.BUFFER_SIZE
                    activeSockets.add(s)
                    val xin = DataInputStream(s.getInputStream())
                    val xout = s.getOutputStream()

                    val (authUser, authPass) = SmartPoolManager.getPoolAuthCredentials()
                    xout.write(byteArrayOf(0x05, 0x01, 0x02))
                    xout.flush()

                    val gResp = ByteArray(2)
                    xin.readFully(gResp)
                    if (gResp[0] != 0x05.toByte() || gResp[1] != 0x02.toByte()) {
                        s.close()
                        throw IllegalStateException("Xray rejected SOCKS5 auth method negotiation")
                    }

                    val userBytes = authUser.toByteArray(Charsets.UTF_8)
                    val passBytes = authPass.toByteArray(Charsets.UTF_8)
                    val authBuf = ByteArray(1 + 1 + userBytes.size + 1 + passBytes.size)
                    authBuf[0] = 0x01
                    authBuf[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, authBuf, 2, userBytes.size)
                    authBuf[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, authBuf, 3 + userBytes.size, passBytes.size)
                    xout.write(authBuf)
                    xout.flush()

                    val aResp = ByteArray(2)
                    xin.readFully(aResp)
                    if (aResp[0] != 0x01.toByte() || aResp[1] != 0x00.toByte()) {
                        s.close()
                        throw IllegalStateException("Xray internal SOCKS5 authentication failed")
                    }

                    xout.write(rawReq)
                    xout.flush()

                    val xRespVer = xin.readByte().toInt() and 0xFF
                    val rep = xin.readByte().toInt() and 0xFF
                    val xRsv = xin.readByte().toInt() and 0xFF
                    val xAtyp = xin.readByte().toInt() and 0xFF

                    val xAddrBytes = when (xAtyp) {
                        0x01 -> ByteArray(4).also { xin.readFully(it) }
                        0x03 -> {
                            val l = xin.readByte().toInt() and 0xFF
                            ByteArray(1 + l).apply { this[0] = l.toByte(); xin.readFully(this, 1, l) }
                        }
                        0x04 -> ByteArray(16).also { xin.readFully(it) }
                        else -> ByteArray(4).also { xin.readFully(it) }
                    }
                    val xPortBytes = ByteArray(2).also { xin.readFully(it) }

                    if (rep != 0x00) {
                        s.close()
                        balancer.penalize(currentNode)
                        targetNode = balancer.getActiveLeader()
                        continue
                    }

                    if (isHttpConnect) {
                        cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        cout.flush()
                    } else {
                        cout.write(byteArrayOf(0x05, 0x00, 0x00, xAtyp.toByte()))
                        cout.write(xAddrBytes)
                        cout.write(xPortBytes)
                        cout.flush()
                    }

                    xraySocket = s
                    connectSuccess = true
                    break
                } catch (e: ConnectException) {
                    LogUtil.d(SmartPoolConstants.TAG, "Xray port ${currentNode.localPort} warming up / connection refused, skipping penalty")
                    targetNode = balancer.getActiveLeader()
                } catch (e: Exception) {
                    balancer.penalize(currentNode)
                    targetNode = balancer.getActiveLeader()
                }
            }

            if (!connectSuccess || xraySocket == null) {
                cout.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cout.flush()
                return
            }

            client.soTimeout = 120000
            xraySocket.soTimeout = 120000

            val cinStream = client.getInputStream()
            val coutStream = client.getOutputStream()
            val xinStream = xraySocket.getInputStream()
            val xoutStream = xraySocket.getOutputStream()

            var proxyFailed = false
            val upstreamTask = pool.submit {
                try {
                    pipe(cinStream, xoutStream)
                    runCatching { xraySocket.shutdownOutput() }
                } catch (_: Exception) {
                    // Client closed tab/socket - normal TCP behavior, not a proxy failure
                } finally {
                    runCatching { xraySocket.close() }
                }
            }

            try {
                pipe(xinStream, coutStream)
                runCatching { client.shutdownOutput() }
            } catch (e: Exception) {
                proxyFailed = true
            } finally {
                runCatching { client.close() }
            }

            runCatching { upstreamTask.get() }
            runCatching { xraySocket.close() }
            if (proxyFailed && targetNode != null) {
                balancer.penalize(targetNode)
            }
        } catch (e: Exception) {
            targetNode?.let { balancer.penalize(it) }
        } finally {
            activeSockets.remove(client)
            balancer.activeConnections.decrementAndGet()
            runCatching { client.close() }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = SmartPoolBufferPool.acquire()
        try {
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            SmartPoolBufferPool.release(buffer)
        }
    }
}
