package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.DisplayName
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/** 会话列表。不依赖 rconversation 表（字段随版本变化大），直接从 message 聚合。 */
fun registerConversationTools() {

    ToolRegistry.register(
        ToolDef(
            name = "wx_list_conversations",
            title = "列出微信会话",
            description = "按最后一条消息时间倒序返回会话列表。不依赖微信会话表，直接从 message 表聚合，因此每条都有真实消息时间。返回字段：talker、displayName、isChatroom、lastTime、msgCount、recvCount。",
            inputSchema = Schema.obj(
                props = listOf(
                    "limit" to Schema.int("返回条数，默认 50，最大 200", 50, 1, 200),
                    "withName" to Schema.bool("是否查询联系人为会话补昵称/备注，默认 true", true),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val limit = args.optInt("limit", 50).coerceIn(1, 200)
                val withName = args.optBoolean("withName", true)

                val rows = DbGateway.query(
                    """
                    SELECT talker,
                           MAX(createTime) AS lastTime,
                           COUNT(1) AS msgCount,
                           SUM(CASE WHEN isSend = 0 THEN 1 ELSE 0 END) AS recvCount
                    FROM message
                    GROUP BY talker
                    ORDER BY lastTime DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(limit.toString()),
                    limit
                )

                val names = if (withName) resolveNames(rows.map { it["talker"] ?: "" }) else emptyMap()

                val arr = JSONArray()
                rows.forEach { row ->
                    val talker = row["talker"] ?: ""
                    val name = names[talker]
                    arr.put(
                        JSONObject()
                            .put("talker", talker)
                            .put("displayName", DisplayName.of(talker, name?.first, name?.second))
                            .put("isChatroom", DisplayName.isChatroom(talker))
                            .put("lastTime", row["lastTime"] ?: "")
                            .put("msgCount", row["msgCount"] ?: "")
                            .put("recvCount", row["recvCount"] ?: "")
                    )
                }
                Jsons.ok(JSONObject().put("count", arr.length()).put("items", arr))
            }
        }
    )
}

/** 批量把 talker 解析成 备注/昵称。查不到就返回空 map，由 DisplayName 回退成 wxid。 */
internal fun resolveNames(talkers: List<String>): Map<String, Pair<String?, String?>> {
    val result = HashMap<String, Pair<String?, String?>>()
    val valid = talkers.filter { it.isNotBlank() }.distinct()
    if (valid.isEmpty()) return result

    // 分片查询，避免 SQLite 变量上限
    valid.chunked(200).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        val sql = "SELECT username, conRemark, nickname FROM rcontact WHERE username IN ($placeholders)"
        val rows = runCatching { DbGateway.query(sql, chunk.toTypedArray(), chunk.size) }.getOrDefault(emptyList())
        rows.forEach { row ->
            val username = row["username"] ?: return@forEach
            result[username] = (row["conRemark"] ?: "") to (row["nickname"] ?: "")
        }
    }
    return result
}