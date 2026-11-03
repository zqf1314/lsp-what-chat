package com.whatchat.mcp.tools

import android.os.Build
import com.whatchat.mcp.WxEnv
import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ServerController
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/** 状态与自检类工具。Eta 第一件事就应当调用 wx_status。 */
fun registerStatusTools() {

    ToolRegistry.register(
        ToolDef(
            name = "wx_status",
            title = "微信桥接状态",
            description = "返回模块运行状态：微信版本、数据库是否可读、工具数量、探测报告。调用其它工具前先确认 ok=true 且 data.dbReady=true。",
            inputSchema = Schema.obj(emptyList(), emptyList()),
            readOnly = true,
        ) { _ ->
            Tools.safe {
                val tools = JSONArray()
                ToolRegistry.all().forEach { tools.put(it.name) }
                Jsons.ok(
                    JSONObject()
                        .put("wxVersionName", WxEnv.wxVersionName)
                        .put("wxVersionCode", WxEnv.wxVersionCode)
                        .put("androidSdk", Build.VERSION.SDK_INT)
                        .put("moduleStartedAt", WxEnv.startedAt)
                        .put("mcpPort", ServerController.PORT)
                        .put("dbReady", DbGateway.isReady())
                        .put("dbProbe", DbGateway.probeReport)
                        .put("toolCount", tools.length())
                        .put("tools", tools)
                )
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_self",
            title = "当前微信账号",
            description = "返回当前登录的微信账号信息（userinfo 表首行，字段随微信版本变化，原样返回）。",
            inputSchema = Schema.obj(emptyList(), emptyList()),
            readOnly = true,
        ) { _ ->
            Tools.safe {
                val rows = DbGateway.query("SELECT * FROM userinfo LIMIT 1", null, 1)
                if (rows.isEmpty()) {
                    Jsons.ok(JSONObject().put("empty", true))
                } else {
                    Jsons.ok(JSONObject(rows[0]))
                }
            }
        }
    )
}