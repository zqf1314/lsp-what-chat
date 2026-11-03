package com.whatchat.mcp

import android.app.Application
import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.SqliteHook
import com.whatchat.mcp.server.ServerController
import com.whatchat.mcp.tools.Tools

/**
 * 模块启动编排。
 *
 * 顺序刻意保持「轻」：SqliteHook 先装（必须早于微信打开 EnMicroMsg.db），
 * 再起 HTTP Server（让 Eta 能连上并看到 degraded 状态），数据库探测放后台线程。
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

        // 0. 必须最早装：微信在 Application.onCreate / 登录流程里才打开 EnMicroMsg.db
        runCatching { SqliteHook.install(cl) }
            .onFailure { WxLog.e("install sqlite hook failed", it) }

        // 1. 装配工具
        runCatching { Tools.install() }
            .onFailure { WxLog.e("install tools failed", it) }

        // 2. 起 MCP Server
        runCatching { ServerController.start() }
            .onFailure { WxLog.e("start server failed", it) }

        // 3. 后台分阶段探测：DB 是稍后才打开的，只探一次必然探空
        Thread({
            var elapsed = 0L
            for (delay in PROBE_DELAYS) {
                val wait = delay - elapsed
                if (wait > 0) runCatching { Thread.sleep(wait) }
                elapsed = delay
                runCatching { DbGateway.probe() }
                    .onFailure { WxLog.e("db probe failed", it) }
                if (DbGateway.isReady()) break
            }
        }, "whatchat-dbprobe").apply { isDaemon = true }.start()
    }

    private val PROBE_DELAYS = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L)
}