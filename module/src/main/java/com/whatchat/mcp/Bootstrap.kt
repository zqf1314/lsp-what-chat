package com.whatchat.mcp

import android.app.Application
import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.server.ServerController
import com.whatchat.mcp.tools.Tools

/**
 * 模块启动编排。
 *
 * 顺序刻意保持「轻」：HTTP Server 先起（让 Eta 能连上并看到 degraded 状态），
 * 数据库探测放后台线程，避免拖慢微信冷启动。
 */
object Bootstrap {

    @Volatile
    private var started = false

    fun start(app: Application, cl: ClassLoader) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }

        WxLog.i("Bootstrap start")
        WxEnv.init(app, cl)

        // 1. 装配工具
        runCatching { Tools.install() }
            .onFailure { WxLog.e("install tools failed", it) }

        // 2. 起 MCP Server
        runCatching { ServerController.start() }
            .onFailure { WxLog.e("start server failed", it) }

        // 3. 后台探测数据库（M0 核心任务：定位 8.0.78 的 db 对象）
        Thread({
            runCatching { DbGateway.probe() }
                .onFailure { WxLog.e("db probe failed", it) }
        }, "whatchat-dbprobe").apply { isDaemon = true }.start()
    }
}