package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.WxSender
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 发送消息。
 *
 * 安全默认值：`dry_run = true`。也就是说不传这个参数时只构造 netScene 对象、不提交网络层，
 * 可以用来确认坐标对不对、正文长度、@ 列表是否解析正常。要真发必须显式 `dry_run=false`。
 */
fun registerSendTools() {

    ToolRegistry.register(
        ToolDef(
            name = "wx_send_text",
            title = "发送文本消息",
            description = "给指定会话发送一条文本消息（走微信内部消息对象，不模拟点击）。" +
                "默认 dry_run=true：只做反射能力探测（消息类/构造器/网络核心是否还在），" +
                "不实例化、不发送 —— 微信 8.0.80 的 q61.s0 构造器本身就会把消息入队，所以 dry-run 不能靠“构造但不提交”实现。" +
                "要真发必须显式 dry_run=false。" +
                "返回 submitted=true 代表消息对象已构造并入队（构造即发送），不代表对方已收到。" +
                "群聊 @ 人需在 text 里自带「@昵称 」前缀，并把对方 wxid 放进 at_wxids。" +
                "正文上限 4000 字符，发送限流为 1.5 秒/条、每分钟 20 条。",
            inputSchema = Schema.obj(
                required = listOf("talker", "text"),
                props = listOf(
                    "talker" to Schema.str("会话标识：好友 wxid，或群聊 xxx@chatroom"),
                    "text" to Schema.str("正文，最多 4000 字符；群聊 @ 时需自带「@昵称 」前缀"),
                    "at_wxids" to Schema.arrStr("@ 的 wxid 列表（仅群聊生效），可选", maxItems = 50),
                    "dry_run" to Schema.bool("true = 只做能力探测、不发送（默认）；false = 真正发送", true),
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true,
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val text = args.optString("text", "")
                if (text.isBlank()) return@safe Jsons.err("text 不能为空", "BAD_ARGUMENT")

                // 兼容三种写法：JSON 数组、单个字符串（逗号分隔）、缺省
                val atList = parseAtList(args)
                if (atList.size > 50) {
                    return@safe Jsons.err("@ 列表过长：${atList.size}，最多 50 个", "BAD_ARGUMENT")
                }

                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val r = WxSender.sendText(talker = talker, text = text, atWxIds = atList, dryRun = dryRun)
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("textLength", r.textLength)
                    .put("atCount", r.atCount)
                    .put("atWxIds", JSONArray(atList))
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("costMs", r.costMs)
                    .put("message", r.message)

                if (r.ok) {
                    Jsons.ok(data)
                } else {
                    // 失败也把上下文带回去，便于定位坐标问题
                    Jsons.err(r.message, "SEND_FAILED").put("data", data)
                }
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_send_image",
            title = "发送图片",
            description = "给指定会话发一张本地图片（走微信发送核心，不经 UI）。" +
                "默认 dry_run=true：只解析发送核心（枚举 lt5.o5 → get() → lt5.d5）与 9 参方法 Lj，不发送；" +
                "要真发必须 dry_run=false。path 必须是微信进程能读到的绝对路径。" +
                "发送限流与文本共用（1.5 秒/条、每分钟 20 条）。",
            inputSchema = Schema.obj(
                required = listOf("talker", "path"),
                props = listOf(
                    "talker" to Schema.str("会话标识：好友 wxid，或群聊 xxx@chatroom"),
                    "path" to Schema.str("图片绝对路径，例如 /sdcard/Download/a.png"),
                    "dry_run" to Schema.bool("true = 只探测不发送（默认）；false = 真正发送", true),
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true,
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val path = args.optString("path", "")
                if (path.isBlank()) return@safe Jsons.err("path 不能为空", "BAD_ARGUMENT")
                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val beforeMsgId = if (dryRun) 0L else latestMsgId(talker)
                val r = WxSender.sendImage(talker, path, dryRun)
                // 真发时做「入库校验」：微信发送核心是异步的，submitted 只代表已派发。
                // 只有消息库里出现新的 isSend=1 行，才算真的发出去了（/sdcard 权限不足时
                // 核心会静默失败，这里能立刻暴露）。
                val hit = if (r.ok && !r.dryRun) waitNewMessage(talker, beforeMsgId, 2500L) else null
                val message = when {
                    hit != null -> r.message + "；已入库 msgId=${hit["msgId"]} type=${hit["type"]}"
                    r.ok && !r.dryRun -> r.message + "；2.5s 内消息库未出现新记录，" +
                        "常见原因是文件本进程读不到或会话被折叠，请核对 path"
                    else -> r.message
                }
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("path", r.path)
                    .put("sizeBytes", r.sizeBytes)
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("verified", hit != null)
                    .put("msgId", hit?.get("msgId") ?: "")
                    .put("msgType", hit?.get("type") ?: "")
                    .put("costMs", r.costMs)
                    .put("message", message)

                if (r.ok) Jsons.ok(data) else Jsons.err(r.message, "SEND_FAILED").put("data", data)
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_send_limits",
            title = "查看发送限制",
            description = "返回发送类工具的约束：正文长度上限、两条之间的最小间隔、每分钟条数上限、" +
                "以及当前限流窗口已用条数。只读。",
            inputSchema = Schema.obj(props = emptyList()),
            readOnly = true,
        ) { _ ->
            Tools.safe {
                Jsons.ok(
                    JSONObject()
                        .put("maxTextLength", WxSender.MAX_TEXT_LENGTH)
                        .put("minIntervalMs", WxSender.MIN_INTERVAL_MS)
                        .put("windowMs", WxSender.WINDOW_MS)
                        .put("windowMax", WxSender.WINDOW_MAX)
                        .put("windowUsed", WxSender.windowUsed())
                        .put("dryRunDefault", true)
                )
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_probe_send",
            title = "勘察发送核方法表",
            description = "诊断用：在微信进程内反射列出某个类的全部方法签名（名字/参数/返回值/是否静态），" +
                "用于确认发送核（默认 lt5.d5）上有哪些可用的发送入口。只读，不发送任何消息。",
            inputSchema = Schema.obj(
                props = listOf(
                    "cls" to Schema.str("要勘察的类名，默认 lt5.d5（图片/媒体发送核）"),
                    "max" to Schema.int("最多返回多少条方法，默认 200"),
                )
            ),
            readOnly = true,
        ) { args ->
            Tools.safe {
                val cls = args.optString("cls", "").ifBlank { "lt5.d5" }
                val max = args.optInt("max", 200).coerceIn(1, 600)
    ToolRegistry.register(
        ToolDef(
            name = "wx_send_file",
            title = "发送文件",
            description = "给指定会话发一个本地文件（走微信发送核心 lt5.d5.Kj，不经 UI）。" +
                "默认 dry_run=true 只探测方法是否存在；要真发必须 dry_run=false。",
            inputSchema = Schema.obj(
                required = listOf("talker", "path"),
                props = listOf(
                    "talker" to Schema.str("会话标识：好友 wxid，或群聊 xxx@chatroom"),
                    "path" to Schema.str("文件绝对路径（必须微信进程可读）"),
                    "file_name" to Schema.str("可选：展示用文件名，缺省取文件本身的 name"),
                    "dry_run" to Schema.bool("仅探测不发送，默认 true"),
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true,
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val path = args.optString("path", "")
                if (path.isBlank()) return@safe Jsons.err("path 不能为空", "BAD_ARGUMENT")
                val name = args.optString("file_name", "")
                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val before = if (dryRun) 0L else latestMsgId(talker)
                val r = WxSender.sendFile(talker, path, name, dryRun)
                val hit = if (r.ok && !r.dryRun) waitNewMessage(talker, before, 3000L) else null
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("path", r.path)
                    .put("sizeBytes", r.sizeBytes)
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("verified", hit != null)
                    .put("msgId", hit?.get("msgId") ?: "")
                    .put("msgType", hit?.get("type") ?: "")
                    .put("costMs", r.costMs)
                    .put("message", if (hit != null) r.message + "；已入库 msgId=" + hit["msgId"] + " type=" + hit["type"] else r.message)
                if (r.ok) Jsons.ok(data) else Jsons.err(r.message, "SEND_FAILED").put("data", data)
            }
        }
    )

    ToolRegistry.register(
        ToolDef(
            name = "wx_send_quote",
            title = "引用回复",
            description = "引用某条历史消息发回复（自拼 refermsg appmsg，外层 type=49 走发送链）。" +
                "quote_id 传被引用消息的 msgId 或 msgSvrId，其余字段自动从 message 表补齐，可用 quote_* 参数覆盖。" +
                "默认 dry_run=true。",
            inputSchema = Schema.obj(
                required = listOf("talker", "text", "quote_id"),
                props = listOf(
                    "talker" to Schema.str("会话标识：好友 wxid，或群聊 xxx@chatroom"),
                    "text" to Schema.str("回复正文"),
                    "quote_id" to Schema.str("被引用消息的 msgId 或 msgSvrId"),
                    "quote_from_usr" to Schema.str("可选：覆盖 refermsg/fromusr"),
                    "quote_chat_usr" to Schema.str("可选：覆盖 refermsg/chatusr，缺省等于 talker"),
                    "quote_display_name" to Schema.str("可选：被引用消息发送者显示名"),
                    "quote_content" to Schema.str("可选：覆盖 refermsg/content，缺省取库里原文（截断 1200 字）"),
                    "quote_type" to Schema.int("可选：覆盖被引用消息类型，缺省取库里 type"),
                    "at_wxids" to Schema.str("需要 @ 的 wxid，逗号分隔；不需要留空"),
                    "dry_run" to Schema.bool("仅组装不发送，默认 true"),
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true,
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val text = args.optString("text", "")
                if (text.isBlank()) return@safe Jsons.err("text 不能为空", "BAD_ARGUMENT")
                val qid = args.optString("quote_id", "").trim()
                if (qid.isEmpty()) return@safe Jsons.err("quote_id 不能为空", "BAD_ARGUMENT")
                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val row = lookupMessage(qid)
                    ?: return@safe Jsons.err("找不到被引用消息：$qid（msgId/msgSvrId 都不匹配）", "NOT_FOUND")
                val qType = args.optInt("quote_type", row["type"]?.toIntOrNull() ?: 1)
                val isSend = row["isSend"] == "1"
                val fromUsr = args.optString("quote_from_usr", "")
                    .ifBlank { if (isSend) selfWxid() else senderOf(row) }
                val chatUsr = args.optString("quote_chat_usr", "").ifBlank { talker }
                val display = args.optString("quote_display_name", "")
                val content = args.optString("quote_content", "").ifBlank { row["content"] ?: "" }

                val ref = WxSender.QuoteRef(
                    svrId = row["msgSvrId"]?.toLongOrNull() ?: 0L,
                    chatUsr = chatUsr,
                    fromUsr = fromUsr,
                    type = qType,
                    content = content,
                    displayName = display,
                    createTimeMs = row["createTime"]?.toLongOrNull() ?: 0L,
                )
                val before = if (dryRun) 0L else latestMsgId(talker)
                val r = WxSender.sendQuote(talker, text, ref, parseAtList(args), dryRun)
                val hit = if (r.ok && !r.dryRun) waitNewMessage(talker, before, 3000L) else null
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("quoteSvrId", ref.svrId)
                    .put("quoteType", ref.type)
                    .put("quoteFromUsr", ref.fromUsr)
                    .put("quoteChatUsr", ref.chatUsr)
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("verified", hit != null)
                    .put("msgId", hit?.get("msgId") ?: "")
                    .put("msgType", hit?.get("type") ?: "")
                    .put("costMs", r.costMs)
                    .put("message", if (hit != null) r.message + "；已入库 msgId=" + hit["msgId"] + " type=" + hit["type"] else r.message)
                if (r.ok) Jsons.ok(data) else Jsons.err(r.message, "SEND_FAILED").put("data", data)
            }
        }
    )
}


// ---------------------------------------------------------------- 引用辅助

/** 按 msgId 或 msgSvrId 取一条消息（只允许数字，避免拼接注入）。 */
private fun lookupMessage(id: String): Map<String, String>? {
    val safe = id.trim()
    if (safe.isEmpty() || !safe.all { it.isDigit() }) return null
    return runCatching {
        DbGateway.query(
            "SELECT msgId,msgSvrId,talker,type,content,createTime,isSend FROM message" +
                " WHERE msgId=$safe OR msgSvrId=$safe ORDER BY createTime DESC LIMIT 1",
            null,
            1
        )
    }.getOrNull()?.firstOrNull()
}

/** 当前登录账号 wxid（userinfo 首行）；拿不到就空串。 */
private fun selfWxid(): String = runCatching {
    DbGateway.query("SELECT username FROM userinfo LIMIT 1", null, 1)
        .firstOrNull()?.get("username") ?: ""
}.getOrDefault("")

/** 解析发送者：群聊原文以 "wxid:\n" 开头，私聊即会话 id。 */
private fun senderOf(row: Map<String, String>): String {
    val talker = row["talker"] ?: ""
    if (!talker.endsWith("@chatroom")) return talker
    val content = row["content"] ?: ""
    val idx = content.indexOf(":\n")
    return if (idx in 1..64) content.substring(0, idx) else ""
}

/** at_wxids 支持 JSON 数组 / 逗号分隔字符串 / 缺省。 */
private fun parseAtList(args: JSONObject): List<String> {
    if (!args.has("at_wxids")) return emptyList()
    args.opt("at_wxids")?.let { raw ->
        val out = ArrayList<String>()
        when (raw) {
            is JSONArray -> for (i in 0 until raw.length()) {
                val s = raw.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
            is String -> raw.split(",", "，", ";", "；")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { out.add(it) }
            else -> {
                val s = raw.toString().trim()
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out.distinct()
    }
    return emptyList()
}

// ---------------------------------------------------------------- 发送结果校验

/** SQL 字符串字面量转义，避免 talker 里的单引号破坏语句。 */
private fun sqlStr(value: String): String = "'" + value.replace("'", "''") + "'"

/** 会话当前最大 msgId；读不到（库未就绪等）时返回 0，校验会退化为「不判断」。 */
private fun latestMsgId(talker: String): Long = runCatching {
    DbGateway.query(
        "SELECT IFNULL(MAX(msgId),0) AS m FROM message WHERE talker=${sqlStr(talker)}",
        null,
        1
    ).firstOrNull()?.get("m")?.toLongOrNull() ?: 0L
}.getOrDefault(0L)

/**
 * 等一条新消息落库。微信发送是异步的：核心调用返回只代表「已派发」，
 * 真正入库要等发送流水线跑完。这里按 250ms 轮询，返回新行（msgId/type/isSend）。
 */
private fun waitNewMessage(talker: String, afterMsgId: Long, budgetMs: Long): Map<String, String>? {
    val deadline = System.currentTimeMillis() + budgetMs
    while (System.currentTimeMillis() < deadline) {
        val row = runCatching {
            DbGateway.query(
                "SELECT msgId,type,isSend FROM message" +
                    " WHERE talker=${sqlStr(talker)} AND msgId>${afterMsgId}" +
                    " ORDER BY msgId DESC LIMIT 1",
                null,
                1
            )
        }.getOrNull()?.firstOrNull()
        if (row != null) return row
        runCatching { Thread.sleep(250) }
    }
    return null
}