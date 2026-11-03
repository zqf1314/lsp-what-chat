package com.whatchat.mcp.tools

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

                val r = WxSender.sendImage(talker, path, dryRun)
                val data = JSONObject()
                    .put("talker", r.talker)
                    .put("path", r.path)
                    .put("sizeBytes", r.sizeBytes)
                    .put("dryRun", r.dryRun)
                    .put("submitted", r.submitted)
                    .put("costMs", r.costMs)
                    .put("message", r.message)

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