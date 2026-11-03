package com.whatchat.mcp.server

import com.whatchat.mcp.WxLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 极简 HTTP/1.1 服务器，只服务 MCP 需要的那几条路径。
 *
 * 刻意不用 NanoHTTPD/Ktor：零依赖、不吃 R8、header 行为完全可控。
 * 只 bind 127.0.0.1，外部不可达。
 *
 * 为适配 Eta 的 **streamable-http** 传输层，这里补齐了规范里客户端真实会做的事：
 *  - keep-alive：一条连接连发多个 JSON-RPC 请求（Ktor 客户端默认复用连接）
 *  - chunked 请求体：Transfer-Encoding: chunked 时逐块读，不依赖 Content-Length
 *  - Accept 协商：客户端只认 SSE 时，把 JSON-RPC 响应包成 `event: message` 单帧再关连接
 *  - GET /mcp 回 405（本服务不提供 server-initiated 流），DELETE /mcp 回 200 结束会话
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
            ss.bind(InetSocketAddress("127.0.0.1", port), 32)
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

    // ------------------------------------------------------------------ serving

    private data class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class Pending(val resp: HttpResp, val keepAlive: Boolean)

    /**
     * 单连接上的请求**并发执行、按序回写**。
     *
     * Eta 会复用一条 keep-alive 连接并行发多个 tools/call；之前的实现是「读一个 → 处理完 → 写一个」，
     * 一个慢调用（例如等主线程 15s）会把后面的请求全堵住，表现就是“不支持并发”。
     * HTTP/1.1 只要求响应顺序与请求顺序一致，不要求串行处理，所以这里把请求丢进线程池，
     * 用序号 + 条件变量保证回写顺序。
     */
    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val lock = Object()
            val pending = HashMap<Int, Pending>()
            var nextToWrite = 0
            var submitted = 0
            var terminate = false

            fun flushLocked() {
                while (!terminate) {
                    val item = pending.remove(nextToWrite) ?: return
                    writeResponse(output, item.resp, item.keepAlive)
                    nextToWrite++
                    if (!item.keepAlive) {
                        terminate = true
                        return
                    }
                }
            }

            var served = 0
            while (running && served < MAX_REQ_PER_CONN && !terminate) {
                val req = readRequest(input) ?: break
                val index = served
                served++
                val keepAlive = served < MAX_REQ_PER_CONN && !wantsClose(req)

                WORKERS.execute {
                    val resp = try {
                        adaptForAccept(req, route(req))
                    } catch (t: Throwable) {
                        WxLog.e("route failed: ${req.path}", t)
                        HttpResp(500, "application/json", """{"error":"internal"}""")
                    }
                    synchronized(lock) {
                        pending[index] = Pending(resp, keepAlive)
                        runCatching { flushLocked() }
                            .onFailure { terminate = true }
                        lock.notifyAll()
                    }
                }
                synchronized(lock) { submitted = served }
            }

            // 读循环结束（客户端关闭 / 达到上限 / 收到 Connection: close）：等已提交的响应写完
            val deadline = System.currentTimeMillis() + 12_000
            synchronized(lock) {
                while (nextToWrite < submitted && !terminate && System.currentTimeMillis() < deadline) {
                    lock.wait(200)
                }
            }
        } catch (t: Throwable) {
            // 客户端断开、超时都会走到这里，正常现象，不打堆栈
            WxLog.w("conn end: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(req: Req): HttpResp {
        val path = req.path.substringBefore('?')
        return when {
            path == "/health" && (req.method == "GET" || req.method == "HEAD") -> HttpResp(
                code = 200,
                contentType = "application/json",
                body = """{"ok":true,"server":"whatchat-mcp","port":$port,""" +
                    """"protocols":["${McpDispatcher.PROTO_MODERN}","${McpDispatcher.PROTO_LEGACY}"]}"""
            )

            req.method == "POST" && (path == "/mcp" || path == "/" || path == "/messages") ->
                dispatcher.dispatch(req.body, req.headers)

            // Streamable HTTP 规范：不提供 server-initiated 流时必须回 405，客户端据此降级
            req.method == "GET" && path == "/mcp" -> HttpResp(
                code = 405,
                contentType = "application/json",
                body = """{"error":"no server-initiated sse stream"}""",
                headers = mapOf("Allow" to "POST, DELETE")
            )

            req.method == "DELETE" && (path == "/mcp" || path == "/") ->
                HttpResp(200, "application/json", """{"ok":true}""")

            req.method == "OPTIONS" -> HttpResp(
                code = 204,
                contentType = "application/json",
                body = "",
                headers = mapOf("Allow" to "POST, GET, DELETE, OPTIONS")
            )

            req.method == "GET" -> HttpResp(404, "application/json", """{"error":"not found"}""")
            else -> HttpResp(405, "application/json", """{"error":"method not allowed"}""")
        }
    }

    private fun wantsClose(req: Req): Boolean =
        req.headers["connection"]?.contains("close", ignoreCase = true) == true

    /**
     * 客户端只接受 SSE（Accept 里没有 application/json）时，把 JSON-RPC 响应包成单帧 SSE。
     * 规范允许服务端在单次响应后关流，这里就是这么做的。
     */
    private fun adaptForAccept(req: Req, resp: HttpResp): HttpResp {
        val accept = req.headers["accept"] ?: return resp
        if (resp.contentType != "application/json") return resp
        if (resp.body.isEmpty()) return resp
        if (!accept.contains("text/event-stream", ignoreCase = true)) return resp
        if (accept.contains("application/json", ignoreCase = true) || accept.contains("*/*")) return resp

        return HttpResp(
            code = resp.code,
            contentType = "text/event-stream",
            body = "event: message\ndata: ${resp.body}\n\n",
            headers = resp.headers
        )
    }

    // ------------------------------------------------------------- read request

    private fun readRequest(input: BufferedInputStream): Req? {
        var line = readLine(input) ?: return null
        while (line.isEmpty()) {
            // keep-alive 连接上可能残留空行
            line = readLine(input) ?: return null
        }

        val parts = line.split(' ')
        val method = parts.getOrElse(0) { "GET" }.uppercase()
        val path = parts.getOrElse(1) { "/" }

        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) {
                headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
            if (headers.size > 64) break
        }

        val body = if (method == "POST") readBody(input, headers) else ""

        return Req(method, path, headers, body)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): String {
        val te = headers["transfer-encoding"]
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        if (length > MAX_BODY) {
            WxLog.w("body too large: $length")
            return ""
        }
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun readChunked(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = readLine(input) ?: break
            if (sizeLine.isEmpty()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 吃掉 trailer
                while (true) {
                    val t = readLine(input) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            if (sb.length + size > MAX_BODY) {
                WxLog.w("chunked body too large")
                break
            }
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n <= 0) return sb.toString()
                read += n
            }
            sb.append(String(buf, 0, read, Charsets.UTF_8))
            readLine(input) // 块尾 CRLF
        }
        return sb.toString()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16_384) break
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ write response

    private fun writeResponse(output: OutputStream, resp: HttpResp, keepAlive: Boolean) {
        val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
        head.append("HTTP/1.1 ${resp.code} ${statusText(resp.code)}\r\n")
        head.append("Content-Type: ${resp.contentType}; charset=utf-8\r\n")
        head.append("Content-Length: ${bodyBytes.size}\r\n")
        head.append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
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
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    companion object {
        private const val MAX_REQ_PER_CONN = 64
        private const val MAX_BODY = 8 * 1024 * 1024

        /**
         * 单连接内并行执行请求的工作线程池。
         * Eta 在一条 keep-alive 连接上并发发多个 tools/call，串行处理会让慢查询把后面的全堵死。
         */
        private val WORKERS: ExecutorService = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "whatchat-work").apply { isDaemon = true }
        }
    }
}