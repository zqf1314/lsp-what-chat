package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.DisplayName
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/** 消息查询。所有 SQL 都走 message 表，字段以 EnMicroMsg_schema 为准。 */
fun registerMessageTools() {

    val columns =
        "msgId, msgSvrId, type, isSend, createTime, talker, content, imgPath, status, reserved"

    ToolRegistry.register(
        ToolDef(
            name = "wx_get_messages",
            title = "读取会话消息",
            description = "读取指定 talker 的最近消息，按时间倒序。talker 为对方的 wxid，群聊为 xxx@chatroom。返回字段：msgId、msgSvrId、type、isSend、createTime、talker、content、sender（群消息时的实际发送人）、text（去掉群前缀的正文）。",
            inputSchema = Schema.obj(
                required = listOf("talker"),
                props = listOf(
                    "talker" to Schema.str("会话标识：wxid 或 xxx@chatroom"),
                    "limit" to Schema.int("返回条数，默认 30，最大 200", 30, 1, 200),
                    "beforeTs" to Schema.int("只返回早于该毫秒时间戳的消息（用于翻页），可选"),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val limit = args.optInt("limit", 30).coerceIn(1, 200)
                val beforeTs = if (args.has("beforeTs")) args.optLong("beforeTs", 0L) else 0L

                val sql: String
                val sqlArgs: Array<String>
                if (beforeTs > 0) {
                    sql = "SELECT $columns FROM message WHERE talker = ? AND createTime < ? ORDER BY createTime DESC LIMIT ?"
                    sqlArgs = arrayOf(talker, beforeTs.toString(), limit.toString())
                } else {
                    sql = "SELECT $columns FROM message WHERE talker = ? ORDER BY createTime DESC LIMIT ?"
                    sqlArgs = arrayOf(talker, limit.toString())
                }

                val rows = DbGateway.query(sql, sqlArgs, limit)
                val arr = JSONArray()
                rows.forEach { row ->
                    val (sender, text) = DisplayName.splitGroupContent(row["content"], talker)
                    arr.put(
                        JSONObject()
                            .put("msgId", row["msgId"] ?: "")
                            .put("msgSvrId", row["msgSvrId"] ?: "")
                            .put("type", row["type"] ?: "")
                            .put("isSend", row["isSend"] ?: "")
                            .put("createTime", row["createTime"] ?: "")
                            .put("talker", talker)
                            .put("content", row["content"] ?: "")
                            .put("sender", sender)
                            .put("text", text)
                            .put("imgPath", row["imgPath"] ?: "")
                    )
                }
                Jsons.ok(JSONObject().put("talker", talker).put("count", arr.length()).put("items", arr))
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_search_messages",
            title = "搜索聊天记录",
            description = "按关键词模糊搜索消息（content LIKE %keyword%）。可限定 talker，不传则全库搜索。返回与 wx_get_messages 相同结构，另带 total 命中数（受 limit 限制）。",
            inputSchema = Schema.obj(
                required = listOf("keyword"),
                props = listOf(
                    "keyword" to Schema.str("关键词，支持中文"),
                    "talker" to Schema.str("限定会话，可选"),
                    "limit" to Schema.int("返回条数，默认 20，最大 200", 20, 1, 200),
                    "sinceTs" to Schema.int("只搜该毫秒时间戳之后的消息，可选"),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val keyword = args.optString("keyword", "")
                if (keyword.isBlank()) return@safe Jsons.err("keyword 不能为空", "BAD_ARGUMENT")
                val talker = args.optString("talker", "")
                val limit = args.optInt("limit", 20).coerceIn(1, 200)
                val sinceTs = if (args.has("sinceTs")) args.optLong("sinceTs", 0L) else 0L

                val conditions = ArrayList<String>()
                val sqlArgs = ArrayList<String>()
                conditions.add("content LIKE ?")
                sqlArgs.add("%$keyword%")
                if (talker.isNotBlank()) {
                    conditions.add("talker = ?")
                    sqlArgs.add(talker)
                }
                if (sinceTs > 0) {
                    conditions.add("createTime > ?")
                    sqlArgs.add(sinceTs.toString())
                }
                sqlArgs.add(limit.toString())

                val sql = "SELECT $columns FROM message WHERE " +
                    conditions.joinToString(" AND ") +
                    " ORDER BY createTime DESC LIMIT ?"

                val rows = DbGateway.query(sql, sqlArgs.toTypedArray(), limit)
                val arr = JSONArray()
                rows.forEach { row ->
                    val rowTalker = row["talker"] ?: ""
                    val (sender, text) = DisplayName.splitGroupContent(row["content"], rowTalker)
                    arr.put(
                        JSONObject()
                            .put("msgId", row["msgId"] ?: "")
                            .put("type", row["type"] ?: "")
                            .put("isSend", row["isSend"] ?: "")
                            .put("createTime", row["createTime"] ?: "")
                            .put("talker", rowTalker)
                            .put("sender", sender)
                            .put("text", text)
                    )
                }
                Jsons.ok(
                    JSONObject()
                        .put("keyword", keyword)
                        .put("count", arr.length())
                        .put("items", arr)
                )
            }
        }
    )
}