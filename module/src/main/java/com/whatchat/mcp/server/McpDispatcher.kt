package com.whatchat.mcp.server

import com.whatchat.mcp.WxLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MCP JSON-RPC 分发器。
 *
 * 同时兼容 Eta 的两种协议语义：
 *  - LATEST = 2026-07-28：无状态；请求 params 里一定带 `_meta`（含 protocolVersion），
 *    并且**每一个 result（tools/list 与 tools/call）都必须带 `resultType`**，
 *    值为 "complete" 才被接受 —— 这是 Eta `McpRunContext.adaptResult()` 的硬校验：
 *      if (lastProtocolVersion == LATEST && resultType != "complete") -> MCP_RESULT_TYPE_UNSUPPORTED
 *    早期版本只给 tools/list 加了 resultType，导致 tools/call 一律被拒。
 *  - LEGACY = 2025-11-25 / 2024-11-05 / 2025-03-26 / 2025-06-18：initialize 下发
 *    Mcp-Session-Id，后续请求透传。
 *
 * 判定顺序：params._meta → headers[Mcp-Protocol-Version] → initialize 的 protocolVersion
 * → 本次会话已协商的模式。
 */
class McpDispatcher {

    @Volatile
    private var legacySessionId: String? = null

    @Volatile
    private var negotiated: Boolean? = null

    /** initialize 需要给 legacy 客户端下发 Mcp-Session-Id，暂存在这里由 dispatch 取出。 */
    @Volatile
    private var pendingSessionHeader: String? = null

    fun dispatch(body: String, headers: Map<String, String>): HttpResp {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return jsonResp(400, rpcError(null, -32700, "empty body"))
        }

        // JSON-RPC 批量请求
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrElse {
                return jsonResp(400, rpcError(null, -32700, "parse error"))
            }
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val (code, respBody) = dispatchOne(obj, headers)
                if (respBody.isNotEmpty()) {
                    runCatching { out.put(JSONObject(respBody)) }
                }
                if (code >= 400) {
                    return jsonResp(code, rpcError(obj.opt("id"), -32600, "invalid request"))
                }
            }
            return jsonResp(200, out.toString())
        }

        val req = runCatching { JSONObject(trimmed) }.getOrElse {
            return jsonResp(400, rpcError(null, -32700, "parse error"))
        }
        val (code, respBody) = dispatchOne(req, headers)
        val extra = pendingSessionHeader?.let { sid ->
            pendingSessionHeader = null
            mapOf("Mcp-Session-Id" to sid)
        } ?: emptyMap()
        return HttpResp(code, "application/json", respBody, extra)
    }

    /** 返回 (httpCode, body)；body 为空表示无需响应体（通知类）。 */
    private fun dispatchOne(req: JSONObject, headers: Map<String, String>): Pair<Int, String> {
        val id = req.opt("id")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        val modern = resolveModern(params, headers)

        return when (method) {
            "initialize" -> {
                val requested = params.optString("protocolVersion", "")
                val isModern = when {
                    requested.startsWith("2026") -> true
                    requested.startsWith("2025") || requested.startsWith("2024") -> false
                    else -> modern
                }
                negotiated = isModern
                handleInitialize(id, isModern, requested, headers)
            }

            "notifications/initialized",
            "notifications/cancelled",
            "notifications/progress",
            "notifications/roots/list_changed" -> Pair(202, "")

            "tools/list" -> Pair(200, handleToolsList(id, modern))
            "tools/call" -> Pair(200, handleToolsCall(id, params, modern))
            "ping" -> Pair(200, rpcResult(id, JSONObject().put("ok", true), modern))

            "resources/list" -> Pair(
                200,
                rpcResult(id, JSONObject().put("resources", JSONArray()), modern)
            )

            "resources/templates/list" -> Pair(
                200,
                rpcResult(id, JSONObject().put("resourceTemplates", JSONArray()), modern)
            )

            "prompts/list" -> Pair(
                200,
                rpcResult(id, JSONObject().put("prompts", JSONArray()), modern)
            )

            "logging/setLevel" -> Pair(200, rpcResult(id, JSONObject(), modern))

            "completion/complete" -> Pair(
                200,
                rpcResult(
                    id,
                    JSONObject().put("completion", JSONObject().put("values", JSONArray())),
                    modern
                )
            )

            else -> Pair(200, rpcError(id, -32601, "method not found: $method"))
        }
    }

    // ---------------------------------------------------------------- handlers

    private fun handleInitialize(
        id: Any?,
        modern: Boolean,
        requested: String,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        // 回显客户端请求的版本号，避免客户端因为「服务端返回了它不认识的版本」而直接断开
        val version = when {
            requested.matches(Regex("""\d{4}-\d{2}-\d{2}""")) -> requested
            modern -> PROTO_MODERN
            else -> PROTO_LEGACY
        }

        val result = JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false))
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", "whatchat-mcp")
                    .put("version", SERVER_VERSION)
                    .put("title", "WeChat MCP for Eta")
            )
            .put(
                "instructions",
                "微信本地只读桥：wx_status 看状态，wx_list_conversations / wx_get_messages " +
                    "读会话与消息，wx_search_* 检索，wx_raw_sql 只读 SQL 直通。"
            )

        WxLog.i("initialize modern=$modern requested=$requested -> $version ua=${headers["user-agent"]}")

        // legacy 传输靠 session header 串上下文，modern（streamable-http 无状态）不需要
        if (!modern) {
            pendingSessionHeader = sessionId()
        }

        return Pair(200, rpcResult(id, result, modern))
    }

    private fun handleToolsList(id: Any?, modern: Boolean): String {
        val result = JSONObject().put("tools", ToolRegistry.toJsonArray())
        return rpcResult(id, result, modern)
    }

    private fun handleToolsCall(id: Any?, params: JSONObject, modern: Boolean): String {
        val name = params.optString("name", "")
        val tool = ToolRegistry.get(name)
            ?: return rpcError(id, -32602, "unknown tool: $name")

        val args = params.optJSONObject("arguments") ?: JSONObject()
        val started = System.currentTimeMillis()

        val payload = try {
            tool.handler(args)
        } catch (t: Throwable) {
            WxLog.e("tool $name crashed", t)
            Jsons.err("${t.javaClass.simpleName}: ${t.message}", "TOOL_CRASH")
        }

        val cost = System.currentTimeMillis() - started
        val ok = payload.optBoolean("ok", false)
        WxLog.i("tool=$name ok=$ok cost=${cost}ms")

        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", payload.toString())
                )
            )
            .put("isError", !ok)
        return rpcResult(id, result, modern)
    }

    // ------------------------------------------------------------------ utils

    private fun resolveModern(params: JSONObject, headers: Map<String, String>): Boolean {
        if (params.has("_meta")) return true
        val v = headers["mcp-protocol-version"]
        if (v != null && v.startsWith("2026")) return true
        if (v != null && (v.startsWith("2025") || v.startsWith("2024"))) return false
        return negotiated ?: false
    }

    /**
     * 组 result。
     *
     * modern 模式下必须带 resultType=complete：Eta 的 McpRunContext 会在
     * `lastProtocolVersion == 2026-07-28 && resultType != "complete"` 时直接判失败，
     * 报 MCP_RESULT_TYPE_UNSUPPORTED。tools/list 和 tools/call 都要带。
     */
    private fun rpcResult(id: Any?, result: JSONObject, modern: Boolean): String {
        if (modern) {
            result.put("resultType", "complete")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()
    }

    private fun rpcError(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject().put("code", code).put("message", message)
            )
            .toString()

    private fun jsonResp(
        code: Int,
        body: String,
        extra: Map<String, String> = emptyMap(),
    ): HttpResp = HttpResp(code, "application/json", body, extra)

    /** legacy 会话 id（initialize 时下发，之后由客户端带回）。 */
    fun sessionId(): String = legacySessionId ?: UUID.randomUUID().toString().also {
        legacySessionId = it
    }

    companion object {
        const val PROTO_MODERN = "2026-07-28"
        const val PROTO_LEGACY = "2025-11-25"
        const val SERVER_VERSION = "0.2.26"
    }
}