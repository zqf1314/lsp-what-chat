package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只读 SQL 直通。
 *
 * 这是本模块最危险的工具，所以三重限制：
 *  1. 只允许以 SELECT 开头
 *  2. 不允许分号（阻断多语句）
 *  3. 表名必须命中白名单
 * 默认不在 Eta 侧启用，需要用户显式勾选。
 */
fun registerRawSqlTool() {

    ToolRegistry.register(
        ToolDef(
            name = "wx_raw_sql",
            title = "只读 SQL 直通",
            description = "对微信主库执行只读 SELECT（白名单表）。用于 wx_* 工具覆盖不到的场景。不允许多语句、不允许写操作。默认在 Eta 侧不启用。",
            inputSchema = Schema.obj(
                required = listOf("sql"),
                props = listOf(
                    "sql" to Schema.str("SELECT 语句，必须单条；示例：SELECT talker,content,createTime FROM message WHERE type=1 ORDER BY createTime DESC LIMIT 10"),
                    "args" to Schema.str("占位符参数，JSON 数组字符串，可选；示例：[\"wxid_xxx\"]"),
                    "limit" to Schema.int("结果行数上限，默认 100，最大 500", 100, 1, 500),
                )
            ),
            readOnly = true,
            destructive = false,
            idempotent = true,
            openWorld = false,
        ) { params ->
            Tools.safe {
                val sql = params.optString("sql", "").trim()
                if (sql.isEmpty()) return@safe Jsons.err("sql 不能为空", "BAD_ARGUMENT")

                val upper = sql.uppercase()
                if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
                    return@safe Jsons.err("只允许 SELECT 查询", "FORBIDDEN")
                }
                if (sql.contains(';')) {
                    return@safe Jsons.err("不允许多语句（检测到分号）", "FORBIDDEN")
                }
                val forbidden = listOf("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "ATTACH", "PRAGMA", "REPLACE")
                forbidden.forEach { word ->
                    if (Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)) {
                        return@safe Jsons.err("禁止的关键字：$word", "FORBIDDEN")
                    }
                }
                val table = extractMainTable(sql)
                if (table != null && table !in ALLOWED_TABLES) {
                    return@safe Jsons.err("表 $table 不在白名单内", "FORBIDDEN")
                }

                val limit = params.optInt("limit", 100).coerceIn(1, 500)
                val args = parseArgs(params.optString("args", ""))

                val rows = DbGateway.query(sql, args, limit)
                val arr = JSONArray()
                rows.forEach { arr.put(JSONObject(it)) }
                Jsons.ok(JSONObject().put("count", arr.length()).put("items", arr))
            }
        }
    )
}

private fun parseArgs(raw: String): Array<String>? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return runCatching {
        val array = JSONArray(text)
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            out.add(array.optString(i, ""))
        }
        out.toTypedArray()
    }.getOrNull()
}

private fun extractMainTable(sql: String): String? {
    val regex = Regex("\\bFROM\\s+([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
    return regex.find(sql)?.groupValues?.getOrNull(1)
}

private val ALLOWED_TABLES = setOf(
    "message",
    "rcontact",
    "rconversation",
    "chatroom",
    "userinfo",
    "img_flag",
    "voiceinfo",
    "media",
    "emoticon",
    "fmessage",
    "bottleconversation",
    "sns",
    "sns_ext",
    "hardcode",
    "config_storage",
)