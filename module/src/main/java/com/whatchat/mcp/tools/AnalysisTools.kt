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
                // message.createTime 实测是毫秒时间戳（如 1791352171000），
                // 所以所有时间比较/格式化都必须按毫秒来，否则 where 恒真、strftime 返回 NULL。
                val sinceMs = System.currentTimeMillis() - days * 86400_000L

                val where = StringBuilder("createTime>=?")
                val whereArgs = ArrayList<String>(listOf(sinceMs.toString()))
                if (talker.isNotEmpty()) {
                    where.append(" AND talker=?")
                    whereArgs.add(talker)
                }

                // 按天分桶：createTime 是毫秒，先 /1000 变秒，再 +8h 偏移后按 UTC 格式化。
                // 不用 'localtime' 修饰符（微信内置 sqlite 3.27.2 对修饰符链敏感）。
                val byDay = DbGateway.query(
                    "SELECT strftime('%Y-%m-%d', createTime/1000 + 28800, 'unixepoch') AS day," +
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
                        arrayOf(sinceMs.toString(), top.toString()),
                        top
                    ).forEach { r ->
                        topArr.put(
                            JSONObject()
                                .put("talker", r["talker"] ?: "")
                                .put("isChatroom", (r["talker"] ?: "").endsWith("@chatroom"))
                                .put("total", r["total"]?.toIntOrNull() ?: 0)
                        )
                    }
                }

                Jsons.ok(
                    JSONObject()
                        .put("talker", talker)
                        .put("days", days)
                        .put("sinceMs", sinceMs)
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

/** 英文/技术类噪音：URL 域名残片、文件名后缀、前端标记等，统计热词时直接丢掉。 */
private val AN_NOISE = setOf(
    "com", "cn", "net", "org", "io", "co", "http", "https", "www", "html", "htm",
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "mp4", "mov", "mp3", "wav",
    "pdf", "zip", "rar", "7z", "apk", "json", "xml", "txt", "csv", "log", "xlog",
    "utf", "utf8", "gbk", "div", "span", "href", "src", "img", "api", "url", "php", "asp", "jsp"
)

/** 微信表情占位符，如 `[捂脸]` `[强]` `[OK]`：整段剔掉，否则会污染成「捂脸」这类热词。 */
private val AN_EMOJI = Regex("\\[[^\\[\\]]{1,10}\\]")

/** 裸链接：整段剔掉，避免 https / com 这类残片进场。 */
private val AN_URL = Regex("(?i)\\bhttps?://\\S+|\\bwww\\.\\S+")

private fun registerHotWords() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_hot_words",
            title = "聊天热词",
            description = "对最近若干天的文本消息做简单词频统计（中文按 2~4 字滑窗 + 停用词过滤，英文按整词）。" +
                "会自动剔掉 [表情] 占位符、裸链接和文件名后缀类噪音（com/jpg/https 等），结果只用于快速看话题，不是严格分词。",
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
                val sinceMs = System.currentTimeMillis() - days * 86400_000L

                val sql = StringBuilder("SELECT content FROM message WHERE type=1 AND createTime>=?")
                val sqlArgs = ArrayList<String>(listOf(sinceMs.toString()))
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
                    // 先剔表情占位符和裸链接，再切词，否则 [捂脸] 会变成「捂脸」、URL 会留下 https/com
                    text = AN_EMOJI.replace(AN_URL.replace(text, " "), " ")
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
                            if (w !in AN_NOISE) counter[w] = (counter[w] ?: 0) + 1
                        }
                    }
                }

                val words = JSONArray()
                // 同频按词字典序，保证同样的输入每次输出顺序一致（不然 AI 拿到的热词会来回跳）。
                counter.entries
                    .filter { it.value >= minCount }
                    .sortedWith(Comparator { x, y ->
                        val c = y.value.compareTo(x.value)
                        if (c != 0) c else x.key.compareTo(y.key)
                    })
                    .take(limit)
                    .forEach { e ->
                        words.put(JSONObject().put("word", e.key).put("count", e.value))
                    }

                Jsons.ok(
                    JSONObject()
                        .put("talker", talker)
                        .put("days", days)
                        .put("sinceMs", sinceMs)
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
                "每条带 createTime（毫秒）与 time（yyyy-MM-dd HH:mm:ss，东八区）。" +
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
                val anchorId = a["msgId"] ?: ""
                // 用 (createTime, msgId) 复合比较：微信同一条会话里同一秒常有几条消息，
                // 只按时间比较会把同秒的邻居漏掉（before/after 都受影响）。
                val prev = if (before == 0) emptyList() else DbGateway.query(
                    "SELECT msgId,type,isSend,createTime,content FROM message" +
                        " WHERE talker=? AND (createTime<? OR (createTime=? AND msgId<?))" +
                        " ORDER BY createTime DESC, msgId DESC LIMIT ?",
                    arrayOf(talker, anchorTs.toString(), anchorTs.toString(), anchorId, before.toString()),
                    before
                ).reversed()
                val next = if (after == 0) emptyList() else DbGateway.query(
                    "SELECT msgId,type,isSend,createTime,content FROM message" +
                        " WHERE talker=? AND (createTime>? OR (createTime=? AND msgId>?))" +
                        " ORDER BY createTime ASC, msgId ASC LIMIT ?",
                    arrayOf(talker, anchorTs.toString(), anchorTs.toString(), anchorId, after.toString()),
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
                "列：msgId,type,isSend,createTime,talker,content,time（time 为东八区可读时间）。" +
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
                val sinceMs = System.currentTimeMillis() - days * 86400_000L
                val name = args.optString("file_name", "").ifBlank {
                    "wx_export_" + System.currentTimeMillis() + ".csv"
                }

                val sql = StringBuilder(
                    "SELECT msgId,type,isSend,createTime,talker,content FROM message WHERE createTime>=?"
                )
                val sqlArgs = ArrayList<String>(listOf(sinceMs.toString()))
                if (talker.isNotEmpty()) {
                    sql.append(" AND talker=?")
                    sqlArgs.add(talker)
                }
                // 取「最近」的 limit 条（DESC），读出来再反转成时间升序写 CSV。
                sql.append(" ORDER BY createTime DESC, msgId DESC LIMIT ?")
                sqlArgs.add(limit.toString())

                val rows = DbGateway.query(sql.toString(), sqlArgs.toTypedArray(), limit).reversed()
                val ctx = anAppContext()
                    ?: return@safe Jsons.err("拿不到微信 Context，无法写文件", "NO_CONTEXT")
                val dir = ctx.filesDir
                val file = java.io.File(dir, name)
                val sb = StringBuilder("msgId,type,isSend,createTime,talker,content,time\n")
                rows.forEach { r ->
                    sb.append(csv(r["msgId"])).append(',')
                        .append(csv(r["type"])).append(',')
                        .append(csv(r["isSend"])).append(',')
                        .append(csv(r["createTime"])).append(',')
                        .append(csv(r["talker"])).append(',')
                        .append(csv(r["content"])).append(',')
                        .append(csv(anTime(r["createTime"]?.toLongOrNull() ?: 0L))).append('\n')
                }
                file.writeText(sb.toString(), Charsets.UTF_8)

                // 再往微信自己的外部私有目录（/sdcard/Android/data/com.tencent.mm/files）落一份，
                // 方便用 MT / 文件管理器直接取；外部目录不可用时只给私有路径，不算失败。
                val extPath = runCatching {
                    val d = ctx.getExternalFilesDir(null) ?: return@runCatching ""
                    if (!d.exists() && !d.mkdirs()) return@runCatching ""
                    val f = java.io.File(d, name)
                    f.writeText(sb.toString(), Charsets.UTF_8)
                    f.absolutePath
                }.getOrNull().orEmpty()

                Jsons.ok(
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("extPath", extPath)
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
    val tsMs = r["createTime"]?.toLongOrNull() ?: 0L
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
        .put("createTime", tsMs)
        .put("time", anTime(tsMs))
        .put("sender", sender)
        .put("text", if (text.length > maxChars) text.substring(0, maxChars) else text)
        .put("content", content)
}

/** 东八区；只用于把毫秒 createTime 变成人能读的时间串。 */
private val AN_TZ: java.util.TimeZone = java.util.TimeZone.getTimeZone("GMT+08:00")

/** 毫秒时间戳 → `yyyy-MM-dd HH:mm:ss`（东八区）。0/负数返回空串。 */
private fun anTime(tsMs: Long): String {
    if (tsMs <= 0L) return ""
    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
    f.timeZone = AN_TZ
    return f.format(java.util.Date(tsMs))
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