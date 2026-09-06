package com.sulav.proxy

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Local HTTP debugging proxy for traffic from apps you control. */
class ProxyServer(
    private val listenHost: String,
    private val listenPort: Int,
    private val targetUrl: String,
    private val onEvent: (ProxyEvent) -> Unit,
    private val onError: (String) -> Unit
) {
    data class ProxyEvent(
        val method: String,
        val endpoint: String,
        val status: Int,
        val requestHex: String,
        val responseHex: String,
        val headers: List<Pair<String, String>>,
        val requestSize: Int,
        val responseSize: Int
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        acceptThread = Thread {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(listenHost, listenPort))
                serverSocket = socket
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        pool.execute { handleClient(client) }
                    } catch (e: SocketException) {
                        if (running.get()) onError("Accept failed: ${e.message ?: "socket error"}")
                    }
                }
            } catch (e: IOException) {
                running.set(false)
                onError("Could not bind $listenHost:$listenPort — ${e.message ?: "I/O error"}")
            }
        }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 15_000
                val request = readHttpMessage(BufferedInputStream(c.getInputStream())) ?: return
                val firstLine = request.decodeToString().substringBefore("\r\n")
                val parts = firstLine.split(" ")
                val method = parts.getOrElse(0) { "?" }.uppercase()
                val path = parts.getOrElse(1) { "/" }
                val requestHeaders = parseHeaders(request)

                val base = targetUrl.trimEnd('/')
                val finalUrl = if (path.startsWith("http://") || path.startsWith("https://")) path else base + if (path.startsWith("/")) path else "/$path"

                var status = 502
                var responseBytes = buildErrorResponse("Bad Gateway")
                var responseHeaders = emptyList<Pair<String, String>>()

                try {
                    val url = URL(finalUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        instanceFollowRedirects = false
                        doInput = true
                    }

                    for ((name, value) in requestHeaders) {
                        if (!isHopByHop(name) && !name.equals("Host", true) && !name.equals("Content-Length", true)) {
                            conn.setRequestProperty(name, value)
                        }
                    }

                    val body = extractBody(request)
                    if (body.isNotEmpty() && method !in setOf("GET", "HEAD")) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body) }
                    }

                    status = try { conn.responseCode } catch (_: IOException) { 502 }
                    val input = if (status >= 400) conn.errorStream else conn.inputStream
                    val bodyBytes = input?.use { it.readBytes() } ?: ByteArray(0)
                    responseHeaders = conn.headerFields.entries
                        .filter { it.key != null }
                        .flatMap { entry -> entry.value.orEmpty().map { v -> entry.key!! to v } }
                        .filterNot { isHopByHop(it.first) }
                    responseBytes = buildHttpResponse(status, conn.responseMessage ?: "", responseHeaders, bodyBytes)
                    conn.disconnect()
                } catch (e: Exception) {
                    responseBytes = buildErrorResponse("Upstream error: ${e.message ?: "unknown error"}")
                    status = 502
                }

                c.getOutputStream().use { out -> out.write(responseBytes); out.flush() }

                onEvent(
                    ProxyEvent(
                        method = method,
                        endpoint = path,
                        status = status,
                        requestHex = request.take(512).toHexString(),
                        responseHex = responseBytes.take(512).toByteArray().toHexString(),
                        headers = requestHeaders.filterNot { it.first.equals("Authorization", true) }
                            .map { it.first to redact(it.second) },
                        requestSize = request.size,
                        responseSize = responseBytes.size
                    )
                )
            } catch (e: Exception) {
                onError("Connection error: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun readHttpMessage(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var a = -1; var b = -1; var c = -1; var d: Int
        while (input.read().also { d = it } != -1) {
            buffer.write(d)
            a = b; b = c; c = d
            if (a == 13 && b == 10 && c == 13 && d == 10) break
        }
        if (buffer.size() == 0) return null
        val headerBytes = buffer.toByteArray()
        val headerText = headerBytes.decodeToString()
        val contentLength = Regex("(?im)^content-length:\\s*(\\d+)").find(headerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var remaining = contentLength
        while (remaining > 0) {
            val chunk = ByteArray(minOf(remaining, 8192))
            val n = input.read(chunk)
            if (n <= 0) break
            buffer.write(chunk, 0, n)
            remaining -= n
        }
        return buffer.toByteArray()
    }

    private fun extractBody(request: ByteArray): ByteArray {
        val marker = byteArrayOf(13, 10, 13, 10)
        val index = request.indexOf(marker)
        return if (index >= 0) request.copyOfRange(index + 4, request.size) else ByteArray(0)
    }

    private fun parseHeaders(request: ByteArray): List<Pair<String, String>> {
        val text = request.decodeToString()
        val headerText = text.substringBefore("\r\n\r\n")
        return headerText.split("\r\n").drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
    }

    private fun buildHttpResponse(status: Int, message: String, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("HTTP/1.1 $status ${if (message.isBlank()) "OK" else message}\r\n".encodeToByteArray())
        headers.filterNot { it.first.equals("Content-Length", true) || it.first.equals("Connection", true) }
            .forEach { (k, v) -> out.write("$k: $v\r\n".encodeToByteArray()) }
        out.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
        out.write(body)
        return out.toByteArray()
    }

    private fun buildErrorResponse(message: String): ByteArray {
        val body = message.encodeToByteArray()
        return buildHttpResponse(502, "Bad Gateway", listOf("Content-Type" to "text/plain; charset=utf-8"), body)
    }

    private fun isHopByHop(name: String): Boolean = name.lowercase() in setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"
    )

    private fun redact(value: String): String {
        if (value.length <= 8) return "••••"
        return value.take(3) + "••••" + value.takeLast(3)
    }

    private fun ByteArray.take(n: Int): ByteArray = copyOfRange(0, minOf(size, n))
    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
