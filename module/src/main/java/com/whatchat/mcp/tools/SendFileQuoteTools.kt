package com.whatchat.mcp.tools

import com.whatchat.mcp.bridge.DbGateway
import com.whatchat.mcp.bridge.WxSender
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONObject

/**
 * 文件 / 引用 这两个写工具单独放一个文件，避免和 [registerSendTools] 的大函数纠缠。
 */
fun registerSendFileQuoteTools() {
    registerSendFile()
    registerSendQuote()
}

private fun registerSendFile() {
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
                    "method" to Schema.str("可选：指定 lt5.d5 上的方法名，默认 Kj"),
                    "dry_run" to Schema.bool("仅探测不发送，默认 true")
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val path = args.optString("path", "")
                if (path.isBlank()) return@safe Jsons.err("path 不能为空", "BAD_ARGUMENT")
                val name = args.optString("file_name", "")
                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val before = if (dryRun) 0L else fqLatestMsgId(talker)
                val explicit = args.optString("method", "").trim()
                val candidates = if (explicit.isEmpty()) listOf("Kj", "Tj", "Jj") else listOf(explicit)
                var used = candidates.first()
                var r = WxSender.sendFile(talker, path, name, dryRun, used)
                var hit = if (r.ok && !r.dryRun) fqWaitNewMessage(talker, before, 2500L) else null
                if (!dryRun && hit == null) {
                    // lt5.d5 上文件名/路径三参的候选不止一个，只有真落库才算命中：
                    // 逐个补试，命中即停（每次之间留出击穿限流的最小间隔）。
                    for (candidate in candidates.drop(1)) {
                        runCatching { Thread.sleep(1600) }
                        used = candidate
                        r = WxSender.sendFile(talker, path, name, false, candidate)
                        if (!r.ok) continue
                        hit = fqWaitNewMessage(talker, before, 2500L)
                        if (hit != null) break
                    }
                }
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("path", r.path)
                    .put("sizeBytes", r.sizeBytes)
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("methodUsed", used)
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

private fun registerSendQuote() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_send_quote",
            title = "引用回复",
            description = "引用某条历史消息发回复（自拼 refermsg appmsg，外层 type=49 走发送链）。" +
                "quote_id 传被引用消息的 msgId 或 msgSvrId，其余字段自动从 message 表补齐，可用 quote_* 覆盖。默认 dry_run=true。",
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
                    "dry_run" to Schema.bool("仅组装不发送，默认 true")
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = false,
            openWorld = true
        ) { args ->
            Tools.safe {
                val talker = args.optString("talker", "")
                if (talker.isBlank()) return@safe Jsons.err("talker 不能为空", "BAD_ARGUMENT")
                val text = args.optString("text", "")
                if (text.isBlank()) return@safe Jsons.err("text 不能为空", "BAD_ARGUMENT")
                val qid = args.optString("quote_id", "").trim()
                if (qid.isEmpty()) return@safe Jsons.err("quote_id 不能为空", "BAD_ARGUMENT")
                val dryRun = if (args.has("dry_run")) args.optBoolean("dry_run", true) else true

                val row = fqLookupMessage(qid)
                    ?: return@safe Jsons.err("找不到被引用消息：$qid（msgId/msgSvrId 都不匹配）", "NOT_FOUND")

                val isSend = row["isSend"] == "1"
                val qType = args.optInt("quote_type", row["type"]?.toIntOrNull() ?: 1)
                val fromUsr = args.optString("quote_from_usr", "")
                    .ifBlank { if (isSend) fqSelfWxid() else fqSenderOf(row) }
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
                    createTimeMs = row["createTime"]?.toLongOrNull() ?: 0L
                )

                val before = if (dryRun) 0L else fqLatestMsgId(talker)
                val r = WxSender.sendQuote(talker, text, ref, fqParseAtList(args), dryRun)
                val hit = if (r.ok && !r.dryRun) fqWaitNewMessage(talker, before, 3000L) else null
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
                // 引用必须落成 appmsg（type=49）。落成 1 说明 netScene 的类型没生效，
                // 对方看到的是原始 XML —— 这种情况算失败，不能假装成功。
                val downgraded = hit != null && hit["type"] != "49"
                if (downgraded) {
                    Jsons.err(
                        "引用回复被微信降级成纯文本（type=" + hit?.get("type") + "），appmsg 类型未生效；" +
                            "已发出的这条会显示成原始 XML，请忽略。需要换 appmsg 专用入口。",
                        "QUOTE_DOWNGRADED"
                    ).put("data", data)
                } else if (r.ok) {
                    Jsons.ok(data)
                } else {
                    Jsons.err(r.message, "SEND_FAILED").put("data", data)
                }
            }
        }
    )
}

// ------------------------------------------------------------------ 内部辅助

private fun fqQuote(value: String): String = "'" + value.replace("'", "''") + "'"

private fun fqLookupMessage(id: String): Map<String, String>? {
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

private fun fqSelfWxid(): String = runCatching {
    DbGateway.query("SELECT username FROM userinfo LIMIT 1", null, 1)
        .firstOrNull()?.get("username") ?: ""
}.getOrDefault("")

private fun fqSenderOf(row: Map<String, String>): String {
    val talker = row["talker"] ?: ""
    if (!talker.endsWith("@chatroom")) return talker
    val content = row["content"] ?: ""
    val idx = content.indexOf(":\n")
    return if (idx in 1..64) content.substring(0, idx) else ""
}

private fun fqLatestMsgId(talker: String): Long = runCatching {
    DbGateway.query(
        "SELECT IFNULL(MAX(msgId),0) AS m FROM message WHERE talker=" + fqQuote(talker),
        null,
        1
    ).firstOrNull()?.get("m")?.toLongOrNull() ?: 0L
}.getOrDefault(0L)

private fun fqWaitNewMessage(talker: String, afterMsgId: Long, budgetMs: Long): Map<String, String>? {
    val deadline = System.currentTimeMillis() + budgetMs
    while (System.currentTimeMillis() < deadline) {
        val row = runCatching {
            DbGateway.query(
                "SELECT msgId,type,isSend FROM message WHERE talker=" + fqQuote(talker) +
                    " AND msgId>" + afterMsgId + " ORDER BY msgId DESC LIMIT 1",
                null,
                1
            )
        }.getOrNull()?.firstOrNull()
        if (row != null) return row
        runCatching { Thread.sleep(250) }
    }
    return null
}

private fun fqParseAtList(args: JSONObject): List<String> {
    val raw = args.optString("at_wxids", "").trim()
    if (raw.isEmpty()) return emptyList()
    return raw.split(",", "，", ";", "；")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}