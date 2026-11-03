package com.whatchat.mcp.server

import com.whatchat.mcp.WxLog
import java.net.InetSocketAddress
import java.net.Socket

/** MCP Server 的生命周期门面。 */
object ServerController {

    const val PORT = 18765

    @Volatile
    private var server: HttpServer? = null

    @Synchronized
    fun start() {
        val current = server
        if (current != null && current.running) return
        val instance = HttpServer(PORT, McpDispatcher())
        instance.start()
        server = instance
        WxLog.i("ServerController started on $PORT")
    }

    fun isRunning(): Boolean = server?.running == true

    /** 从任意进程探测本机是否已有服务在监听。 */
    fun probeLocal(timeoutMs: Int = 400): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs)
            true
        }
    } catch (t: Throwable) {
        false
    }
}