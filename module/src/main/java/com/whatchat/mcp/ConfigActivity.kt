package com.whatchat.mcp

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.whatchat.mcp.server.ServerController

/**
 * 模块主页：只展示运行状态与接入信息，不提供任何写操作入口（写开关放在 Eta 侧 dry_run）。
 */
class ConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val info = buildString {
            appendLine("WhatChat MCP")
            appendLine("──────────────")
            appendLine("LSPosed 作用域：com.tencent.mm")
            appendLine("MCP 地址：http://127.0.0.1:${ServerController.PORT}/mcp")
            appendLine("健康检查：http://127.0.0.1:${ServerController.PORT}/health")
            appendLine()
            appendLine("运行状态")
            appendLine("──────────────")
            appendLine("服务：${if (ServerController.isRunning()) "运行中" else "未启动（需在 LSPosed 中启用并对微信生效）"}")
            appendLine("微信版本：${WxEnv.wxVersionName} (${WxEnv.wxVersionCode})")
            appendLine("会话进程：${System.currentTimeMillis()}")
            appendLine()
            appendLine("用法")
            appendLine("──────────────")
            appendLine("Eta → 设置 → MCP 服务器 → 新增")
            appendLine("URL 填上面的 MCP 地址，协议 auto，鉴权 none")
            appendLine("刷新后勾选需要的 wx_* 工具")
        }

        val text = TextView(this).apply {
            this.text = info
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(56, 96, 56, 56)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(ScrollView(this).apply { addView(container) })
    }
}