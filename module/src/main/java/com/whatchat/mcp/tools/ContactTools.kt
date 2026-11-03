package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.DisplayName
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/** 联系人与群。 */
fun registerContactTools() {

    ToolRegistry.register(
        ToolDef(
            name = "wx_search_contacts",
            title = "搜索联系人",
            description = "按关键词搜索联系人（匹配 wxid / 昵称 / 备注 / 微信号）。返回 username、displayName、nickname、remark、alias、type；type 为群时 isChatroom=true。",
            inputSchema = Schema.obj(
                required = listOf("keyword"),
                props = listOf(
                    "keyword" to Schema.str("关键词"),
                    "limit" to Schema.int("返回条数，默认 20，最大 200", 20, 1, 200),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val keyword = args.optString("keyword", "")
                if (keyword.isBlank()) return@safe Jsons.err("keyword 不能为空", "BAD_ARGUMENT")
                val limit = args.optInt("limit", 20).coerceIn(1, 200)
                val like = "%$keyword%"

                val rows = DbGateway.query(
                    "SELECT username, nickname, conRemark, alias, type FROM rcontact " +
                        "WHERE username LIKE ? OR nickname LIKE ? OR conRemark LIKE ? OR alias LIKE ? " +
                        "ORDER BY type DESC LIMIT ?",
                    arrayOf(like, like, like, like, limit.toString()),
                    limit
                )

                val arr = JSONArray()
                rows.forEach { row ->
                    val username = row["username"] ?: ""
                    arr.put(
                        JSONObject()
                            .put("username", username)
                            .put("displayName", DisplayName.of(username, row["conRemark"], row["nickname"]))
                            .put("nickname", row["nickname"] ?: "")
                            .put("remark", row["conRemark"] ?: "")
                            .put("alias", row["alias"] ?: "")
                            .put("type", row["type"] ?: "")
                            .put("isChatroom", DisplayName.isChatroom(username))
                    )
                }
                Jsons.ok(JSONObject().put("keyword", keyword).put("count", arr.length()).put("items", arr))
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_group_members",
            title = "群成员",
            description = "根据群 id（xxx@chatroom）返回群信息与成员 wxid 列表。memberlist 是微信的分号分隔字符串，这里已拆成数组；成员昵称需要配合 wx_search_contacts 查询。",
            inputSchema = Schema.obj(
                required = listOf("chatroomId"),
                props = listOf(
                    "chatroomId" to Schema.str("群 id，形如 12345678@chatroom"),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val chatroomId = args.optString("chatroomId", "")
                if (chatroomId.isBlank()) return@safe Jsons.err("chatroomId 不能为空", "BAD_ARGUMENT")

                val rows = DbGateway.query(
                    "SELECT chatroomname, memberlist, roomowner, displayname FROM chatroom WHERE chatroomname = ? LIMIT 1",
                    arrayOf(chatroomId),
                    1
                )
                if (rows.isEmpty()) {
                    return@safe Jsons.err("群不存在或该表不可读：$chatroomId", "NOT_FOUND")
                }
                val row = rows[0]
                val rawMembers = row["memberlist"] ?: ""
                val members = JSONArray()
                rawMembers.split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { members.put(it) }

                Jsons.ok(
                    JSONObject()
                        .put("chatroomId", chatroomId)
                        .put("displayName", row["displayname"] ?: "")
                        .put("owner", row["roomowner"] ?: "")
                        .put("memberCount", members.length())
                        .put("members", members)
                )
            }
        }
    )
}