package com.whatchat.mcp.server

import com.whatchat.mcp.WxLog
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 极简 HTTP/1.1 服务器：只服务 MCP 需要的那几条路径。
 *
 * 刻意不用 NanoHTTPD/Ktor：零依赖、不吃 R8、header 行为完全可控。
 * 只 bind 127.0.0.1，外部不可达。
 */
class HttpServer(
    private val port: Int,
    private val dispatcher: McpDispatcher,
) {

    @Volatile
    var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "whatchat-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port), 16)
            serverSocket = ss
            WxLog.i("MCP http listening on 127.0.0.1:$port")
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (t: Throwable) {
                    if (running) WxLog.w("accept failed: ${t.message}")
                    null
                } ?: continue
                Thread({ serve(socket) }, "whatchat-conn").apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
            WxLog.e("http server loop died", t)
        } finally {
            running = false
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input)
            if (requestLine.isNullOrEmpty()) return
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "GET" }.uppercase()
            val path = parts.getOrElse(1) { "/" }

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            var body = ""
            if (method == "POST") {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 0) {
                    val buf = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(buf, read, length - read)
                        if (n <= 0) break
                        read += n
                    }
                    body = String(buf, 0, read, Charsets.UTF_8)
                }
            }

            val resp: HttpResp = when {
                method == "GET" && (path == "/health" || path == "/") -> HttpResp(
                    code = 200,
                    contentType = "application/json",
                    body = """{"ok":true,"server":"whatchat-mcp","port":$port}"""
                )

                method == "POST" -> dispatcher.dispatch(body, headers)
                method == "DELETE" -> HttpResp(200, "application/json", """{"ok":true}""")
                method == "GET" -> HttpResp(404, "application/json", """{"error":"not found"}""")
                else -> HttpResp(405, "application/json", """{"error":"method not allowed"}""")
            }

            writeResponse(output, resp)
        } catch (t: Throwable) {
            WxLog.e("serve failed", t)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.length == 0) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun writeResponse(output: OutputStream, resp: HttpResp) {
        val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ${resp.code} ${statusText(resp.code)}\r\n")
        head.append("Content-Type: ${resp.contentType}; charset=utf-8\r\n")
        head.append("Content-Length: ${bodyBytes.size}\r\n")
        head.append("Connection: close\r\n")
        head.append("Cache-Control: no-store\r\n")
        resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}