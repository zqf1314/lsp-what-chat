package com.whatchat.mcp

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.whatchat.mcp.server.ServerController

/**
 * 模块主页：只展示运行状态与接入信息，不提供任何写操作入口（写开关放在 Eta 侧 dry_run）。
 *
 * 注意：这个界面跑在**模块自己的进程**里，读不到微信进程内的静态变量，
 * 所以「服务是否在跑」必须靠 TCP 探测 127.0.0.1:18765 判断，不能看 ServerController.isRunning()。
 */
class ConfigActivity : Activity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        body = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(56, 72, 56, 32)
        }

        val refresh = Button(this).apply {
            text = "重新检测"
            setOnClickListener { render() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                refresh,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = 56 }
            )
        }

        setContentView(ScrollView(this).apply { addView(container) })
        render()
    }

    private fun render() {
        body.text = "检测中…"
        // Socket.connect 在主线程会抛 NetworkOnMainThreadException，必须放后台
        Thread({
            val alive = ServerController.probeLocal()
            val wx = wxVersion()
            val info = buildInfo(alive, wx)
            runOnUiThread { body.text = info }
        }, "whatchat-ui-probe").apply { isDaemon = true }.start()
    }

    private fun buildInfo(alive: Boolean, wx: String): String {
        val port = ServerController.PORT

        return buildString {
            appendLine("WhatChat MCP 0.2.8")
            appendLine("──────────────")
            appendLine("MCP 地址：http://127.0.0.1:$port/mcp")
            appendLine("健康检查：http://127.0.0.1:$port/health")
            appendLine("传输：streamable-http（同时兼容 legacy session）")
            appendLine()
            appendLine("运行状态（本页在模块进程，状态靠端口探测）")
            appendLine("──────────────")
            appendLine(
                if (alive) "服务：✅ 运行中（微信进程已起 MCP Server）"
                else "服务：❌ 未启动 —— 微信进程里没有监听 $port"
            )
            appendLine("微信版本：$wx")
            appendLine("模块作用域：com.tencent.mm（仅微信主进程启动服务）")
            appendLine("系统：Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine()
            if (!alive) {
                appendLine("排障顺序")
                appendLine("1. LSPosed → 模块 → 启用 WhatChat MCP")
                appendLine("2. 作用域勾选 微信")
                appendLine("3. 强制停止微信后重新打开（必须冷启，热启不会重新加载模块）")
                appendLine("4. 回到本页点「重新检测」")
                appendLine("5. 仍失败看日志：logcat -s WhatChatMCP")
                appendLine()
            }
            appendLine("在 Eta 中接入")
            appendLine("──────────────")
            appendLine("类型：streamable-http")
            appendLine("URL：http://127.0.0.1:$port/mcp")
            appendLine("鉴权：none（仅监听 127.0.0.1）")
            appendLine("协议：auto / 2026-07-28 或 2025-11-25 均可")
        }
    }

    private fun wxVersion(): String = try {
        val pi = packageManager.getPackageInfo("com.tencent.mm", 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        "${pi.versionName} ($code)"
    } catch (t: Throwable) {
        "未安装 / 读取失败"
    }
}