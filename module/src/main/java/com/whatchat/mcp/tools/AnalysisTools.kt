package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * M3 分析向工具：活跃度 / 热词 / 上下文打包 / CSV 导出。
 * 全部只读，不加锁、不走发送链。
 */
fun registerAnalysisTools() {
    registerActivity()
    registerHotWords()
    registerContext()
    registerExportCsv()
}

// ------------------------------------------------------------------ wx_activity

private fun registerActivity() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_activity",
            title = "会话活跃度统计",
            description = "按天统计消息量（总数/收到/发出），并可给出该时间段内消息最多的会话 Top N。" +
                "talker 为空时返回全局按天统计 + Top 会话；指定 talker 时只统计该会话。",
            inputSchema = Schema.obj(
                props = listOf(
                    "talker" to Schema.str("可选：只统计该会话（好友 wxid / 群 xxx@chatroom）"),
                    "days" to Schema.int("统计最近多少天，默认 7"),
                    "top" to Schema.int("Top 会话数量，默认 10")
                )
            ),
            readOnly = true,
            destructive = false,
            idempotent = true,
            openWorld = false
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "").trim()
                val days = args.optInt("days", 7).coerceIn(1, 365)
                val top = args.optInt("top", 10).coerceIn(1, 50)
                val since = System.currentTimeMillis() / 1000 - days * 86400L

                val where = StringBuilder("createTime>=?")
                val whereArgs = ArrayList<String>(listOf(since.toString()))
                if (talker.isNotEmpty()) {
                    where.append(" AND talker=?")
                    whereArgs.add(talker)
                }

                // 按天分桶统一用「时间戳 + 8h 偏移后按 UTC 格式化」的纯算术写法。
                // 不写 'localtime'：微信内置 sqlite 对修饰符链比较挑，多挂一个修饰符
                // 就会让整个 strftime 返回 NULL（曾表现为 day 全是空串）。
                val byDay = DbGateway.query(
                    "SELECT strftime('%Y-%m-%d', createTime + 28800, 'unixepoch') AS day," +
                        " COUNT(*) AS total," +
                        " IFNULL(SUM(CASE WHEN isSend=1 THEN 1 ELSE 0 END),0) AS sent," +
                        " IFNULL(SUM(CASE WHEN isSend=0 THEN 1 ELSE 0 END),0) AS recv" +
                        " FROM message WHERE $where GROUP BY day ORDER BY day",
                    whereArgs.toTypedArray(),
                    days + 5
                )

                val dayArr = JSONArray()
                var totalAll = 0
                var sentAll = 0
                var recvAll = 0
                byDay.forEach { r ->
                    val t = r["total"]?.toIntOrNull() ?: 0
                    val s = r["sent"]?.toIntOrNull() ?: 0
                    val v = r["recv"]?.toIntOrNull() ?: 0
                    totalAll += t; sentAll += s; recvAll += v
                    dayArr.put(
                        JSONObject()
                            .put("day", r["day"] ?: "")
                            .put("total", t)
                            .put("sent", s)
                            .put("recv", v)
                    )
                }

                val topArr = JSONArray()
                if (talker.isEmpty()) {
                    DbGateway.query(
                        "SELECT talker, COUNT(*) AS total FROM message WHERE createTime>=?" +
                            " GROUP BY talker ORDER BY total DESC LIMIT ?",
                        arrayOf(since.toString(), top.toString()),
                        top
                    ).forEach { r ->
                        topArr.put(
                            JSONObject()
                                .put("talker", r["talker"] ?: "")
                                .put("total", r["total"]?.toIntOrNull() ?: 0)
                        )
                    }
                }

                Jsons.ok(
                    JSONObject()
                        .put("talker", talker)
                        .put("days", days)
                        .put("since", since)
                        .put("total", totalAll)
                        .put("sent", sentAll)
                        .put("recv", recvAll)
                        .put("byDay", dayArr)
                        .put("topTalkers", topArr)
                )
            }
        }
    )
}

// ------------------------------------------------------------------ wx_hot_words

private val AN_STOP = setOf(
    "的", "了", "是", "在", "我", "你", "他", "她", "它", "我们", "你们", "他们",
    "这", "那", "就", "也", "都", "和", "与", "吗", "呢", "吧", "啊", "哦", "嗯",
    "一个", "什么", "怎么", "可以", "不是", "没有", "还是", "但是", "因为", "所以",
    "如果", "现在", "今天", "明天", "昨天", "哈哈", "这个", "那个", "一下", "知道",
    "自己", "时候", "已经", "还有", "这样", "那样", "然后", "感觉", "应该", "真的"
)

private fun registerHotWords() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_hot_words",
            title = "聊天热词",
            description = "对最近若干天的文本消息做简单词频统计（中文按 2~4 字滑窗 + 停用词过滤，英文按整词）。" +
                "结果只用于快速看话题，不是严格分词。",
            inputSchema = Schema.obj(
                props = listOf(
                    "talker" to Schema.str("可选：只统计该会话"),
                    "days" to Schema.int("统计最近多少天，默认 7"),
                    "limit" to Schema.int("返回词数，默认 20"),
                    "min_len" to Schema.int("最短词长（英文/数字），默认 2"),
                    "sample" to Schema.int("最多采样多少条文本消息，默认 2000"),
                    "min_count" to Schema.int("最少出现次数，默认 2")
                )
            ),
            readOnly = true,
            destructive = false,
            idempotent = true,
            openWorld = false
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "").trim()
                val days = args.optInt("days", 7).coerceIn(1, 365)
                val limit = args.optInt("limit", 20).coerceIn(1, 100)
                val minLen = args.optInt("min_len", 2).coerceIn(1, 8)
                val sample = args.optInt("sample", 2000).coerceIn(50, 20000)
                val minCount = args.optInt("min_count", 2).coerceIn(1, 100)
                val since = System.currentTimeMillis() / 1000 - days * 86400L

                val sql = StringBuilder("SELECT content FROM message WHERE type=1 AND createTime>=?")
                val sqlArgs = ArrayList<String>(listOf(since.toString()))
                if (talker.isNotEmpty()) {
                    sql.append(" AND talker=?")
                    sqlArgs.add(talker)
                }
                sql.append(" ORDER BY createTime DESC LIMIT ?")
                sqlArgs.add(sample.toString())

                val rows = DbGateway.query(sql.toString(), sqlArgs.toTypedArray(), sample)
                val counter = HashMap<String, Int>()

                rows.forEach { r ->
                    var text = r["content"] ?: return@forEach
                    // 群消息前缀 "wxid:\n"
                    val nl = text.indexOf('\n')
                    if (nl in 1..60 && text.substring(0, nl).contains(":")) text = text.substring(nl + 1)
                    if (text.contains("<") && text.contains(">")) return@forEach // XML 卡片跳过
                    text.split(Regex("[^\\u4e00-\\u9fffA-Za-z0-9]+")).forEach { seg ->
                        if (seg.isEmpty()) return@forEach
                        val cjk = seg.all { it.code in 0x4e00..0x9fff }
                        if (cjk) {
                            if (seg.length <= 4) {
                                if (seg.length >= minLen && seg !in AN_STOP) {
                                    counter[seg] = (counter[seg] ?: 0) + 1
                                }
                            } else {
                                for (n in 2..4) {
                                    for (i in 0..seg.length - n) {
                                        val w = seg.substring(i, i + n)
                                        if (w in AN_STOP) continue
                                        counter[w] = (counter[w] ?: 0) + 1
                                    }
                                }
                            }
                        } else if (seg.length >= minLen && seg.any { it.isLetter() }) {
                            val w = seg.lowercase()
                            counter[w] = (counter[w] ?: 0) + 1
                        }
                    }
                }

                val words = JSONArray()
                counter.entries
                    .filter { it.value >= minCount }
                    .sortedByDescending { it.value }
                    .take(limit)
                    .forEach { e ->
                        words.put(JSONObject().put("word", e.key).put("count", e.value))
                    }

                Jsons.ok(
                    JSONObject()
                        .put("talker", talker)
                        .put("days", days)
                        .put("sampled", rows.size)
                        .put("distinct", counter.size)
                        .put("words", words)
                )
            }
        }
    )
}

// ------------------------------------------------------------------ wx_context

private fun registerContext() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_context",
            title = "上下文打包",
            description = "把某条消息前后的聊天打包成上下文（默认前后各 20 条，按时间升序），便于直接喂给模型。" +
                "around 传 msgId；不传则取该会话最新消息作为锚点。",
            inputSchema = Schema.obj(
                required = listOf("talker"),
                props = listOf(
                    "talker" to Schema.str("会话标识：好友 wxid，或群聊 xxx@chatroom"),
                    "around" to Schema.str("可选：锚点 msgId，缺省用最新一条"),
                    "before" to Schema.int("锚点之前取多少条，默认 20"),
                    "after" to Schema.int("锚点之后取多少条，默认 20"),
                    "max_chars" to Schema.int("单条 content 截断长度，默认 500")
                )
            ),
            readOnly = true,
            destructive = false,
            idempotent = true,
            openWorld = false
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "").trim()
                if (talker.isEmpty()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val around = args.optString("around", "").trim()
                val before = args.optInt("before", 20).coerceIn(0, 200)
                val after = args.optInt("after", 20).coerceIn(0, 200)
                val maxChars = args.optInt("max_chars", 500).coerceIn(50, 4000)

                val anchor = if (around.isNotEmpty() && around.all { it.isDigit() }) {
                    DbGateway.query(
                        "SELECT msgId,type,isSend,createTime,content FROM message WHERE talker=? AND msgId=? LIMIT 1",
                        arrayOf(talker, around), 1
                    ).firstOrNull()
                } else null
                val a = anchor ?: DbGateway.query(
                    "SELECT msgId,type,isSend,createTime,content FROM message WHERE talker=?" +
                        " ORDER BY createTime DESC, msgId DESC LIMIT 1",
                    arrayOf(talker), 1
                ).firstOrNull() ?: return@safe Jsons.err("该会话没有消息：$talker", "NOT_FOUND")

                val anchorTs = a["createTime"]?.toLongOrNull() ?: 0L
                val prev = if (before == 0) emptyList() else DbGateway.query(
                    "SELECT msgId,type,isSend,createTime,content FROM message" +
                        " WHERE talker=? AND createTime<=? ORDER BY createTime DESC, msgId DESC LIMIT ?",
                    arrayOf(talker, anchorTs.toString(), (before + 1).toString()),
                    before + 1
                ).reversed()
                val next = if (after == 0) emptyList() else DbGateway.query(
                    "SELECT msgId,type,isSend,createTime,content FROM message" +
                        " WHERE talker=? AND createTime>? ORDER BY createTime ASC, msgId ASC LIMIT ?",
                    arrayOf(talker, anchorTs.toString(), after.toString()),
                    after
                )

                val isRoom = talker.endsWith("@chatroom")
                val arr = JSONArray()
                (prev + next).forEach { r ->
                    if (r["msgId"] == a["msgId"]) return@forEach
                    arr.put(anRow(r, isRoom, maxChars))
                }
                arr.put(anRow(a, isRoom, maxChars))

                // 按 createTime+msgId 升序重排
                val list = ArrayList<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                list.sortWith(Comparator { x, y ->
                    val c = x.optLong("createTime").compareTo(y.optLong("createTime"))
                    if (c != 0) c else (x.optString("msgId").toLongOrNull() ?: 0L)
                        .compareTo(y.optString("msgId").toLongOrNull() ?: 0L)
                })
                val sorted = JSONArray()
                list.forEach { sorted.put(it) }

                Jsons.ok(
                    JSONObject()
                        .put("talker", talker)
                        .put("isChatroom", isRoom)
                        .put("anchorMsgId", a["msgId"] ?: "")
                        .put("count", sorted.length())
                        .put("messages", sorted)
                )
            }
        }
    )
}

// ------------------------------------------------------------------ wx_export_csv

private fun registerExportCsv() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_export_csv",
            title = "导出消息 CSV",
            description = "把某个会话（或全库）的消息导出成 CSV，写到微信自己可写的目录（默认应用私有 files 目录）。" +
                "返回实际写入路径与行数，便于再拷出去。",
            inputSchema = Schema.obj(
                props = listOf(
                    "talker" to Schema.str("可选：只导出该会话；留空导出全库"),
                    "days" to Schema.int("导出最近多少天，默认 30"),
                    "limit" to Schema.int("最多导出多少行，默认 5000"),
                    "file_name" to Schema.str("可选：文件名，默认 wx_export_<时间戳>.csv")
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = false
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "").trim()
                val days = args.optInt("days", 30).coerceIn(1, 3650)
                val limit = args.optInt("limit", 5000).coerceIn(1, 50000)
                val since = System.currentTimeMillis() / 1000 - days * 86400L
                val name = args.optString("file_name", "").ifBlank {
                    "wx_export_" + System.currentTimeMillis() + ".csv"
                }

                val sql = StringBuilder(
                    "SELECT msgId,type,isSend,createTime,talker,content FROM message WHERE createTime>=?"
                )
                val sqlArgs = ArrayList<String>(listOf(since.toString()))
                if (talker.isNotEmpty()) {
                    sql.append(" AND talker=?")
                    sqlArgs.add(talker)
                }
                sql.append(" ORDER BY createTime ASC LIMIT ?")
                sqlArgs.add(limit.toString())

                val rows = DbGateway.query(sql.toString(), sqlArgs.toTypedArray(), limit)
                val dir = anAppContext()?.filesDir
                    ?: return@safe Jsons.err("拿不到微信 Context，无法写文件", "NO_CONTEXT")
                val file = java.io.File(dir, name)
                val sb = StringBuilder("msgId,type,isSend,createTime,talker,content\n")
                rows.forEach { r ->
                    sb.append(csv(r["msgId"])).append(',')
                        .append(csv(r["type"])).append(',')
                        .append(csv(r["isSend"])).append(',')
                        .append(csv(r["createTime"])).append(',')
                        .append(csv(r["talker"])).append(',')
                        .append(csv(r["content"])).append('\n')
                }
                file.writeText(sb.toString(), Charsets.UTF_8)

                Jsons.ok(
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("rows", rows.size)
                        .put("bytes", file.length())
                        .put("talker", talker)
                        .put("days", days)
                )
            }
        }
    )
}

// ------------------------------------------------------------------ 内部辅助

private fun anRow(r: Map<String, String>, isRoom: Boolean, maxChars: Int): JSONObject {
    val type = r["type"] ?: ""
    var content = r["content"] ?: ""
    var sender = ""
    if (isRoom) {
        val nl = content.indexOf('\n')
        if (nl in 1..80) {
            val head = content.substring(0, nl)
            if (head.contains(":") || head.startsWith("wxid")) {
                sender = head.removeSuffix(":")
                content = content.substring(nl + 1)
            }
        }
    }
    val text = if (type == "1") content else ""
    if (content.length > maxChars) content = content.substring(0, maxChars)
    return JSONObject()
        .put("msgId", r["msgId"] ?: "")
        .put("type", type)
        .put("isSend", r["isSend"] ?: "")
        .put("createTime", r["createTime"]?.toLongOrNull() ?: 0L)
        .put("sender", sender)
        .put("text", if (text.length > maxChars) text.substring(0, maxChars) else text)
        .put("content", content)
}

private fun csv(v: String?): String {
    val s = v ?: ""
    return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else s
}

/** 微信进程内的 Application Context（导出文件时用来拿私有目录）。 */
private fun anAppContext(): android.content.Context? = runCatching {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? android.content.Context
}.getOrNull()