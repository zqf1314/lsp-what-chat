package com.whatchat.mcp.server

import com.whatchat.mcp.WxLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MCP JSON-RPC 分发器。
 *
 * 同时兼容两种协议语义（Eta 的 protocolMode=auto 会先试新版）：
 *  - modern 2026-07-28：无 session；tools/list 的 result 必须带 resultType=complete
 *  - legacy 2025-11-25：initialize 返回 Mcp-Session-Id，后续请求透传
 *
 * 判定方式：请求 params 里出现 _meta，或 header 里的 Mcp-Protocol-Version 以 2026 开头。
 */
class McpDispatcher {

    @Volatile
    private var legacySessionId: String? = null

    fun dispatch(body: String, headers: Map<String, String>): HttpResp {
        if (body.isBlank()) {
            return jsonResp(400, rpcError(null, -32700, "empty body"))
        }
        val req = runCatching { JSONObject(body) }.getOrElse {
            return jsonResp(400, rpcError(null, -32700, "parse error"))
        }

        val id = req.opt("id")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()
        val modern = isModern(params, headers)

        return when (method) {
            "initialize" -> handleInitialize(id, modern)
            "notifications/initialized", "notifications/cancelled" ->
                HttpResp(202, "application/json", "")

            "tools/list" -> handleToolsList(id, modern)
            "tools/call" -> handleToolsCall(id, params)
            "ping" -> jsonResp(200, rpcResult(id, JSONObject().put("ok", true)))
            else -> jsonResp(200, rpcError(id, -32601, "method not found: $method"))
        }
    }

    // ---------------------------------------------------------------- handlers

    private fun handleInitialize(id: Any?, modern: Boolean): HttpResp {
        val version = if (modern) PROTO_MODERN else PROTO_LEGACY
        val result = JSONObject()
            .put("protocolVersion", version)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false))
            )
            .put(
                "serverInfo",
                JSONObject().put("name", "whatchat-mcp").put("version", SERVER_VERSION)
            )

        val extra = HashMap<String, String>()
        if (!modern) {
            val sid = legacySessionId ?: UUID.randomUUID().toString().also { legacySessionId = it }
            extra["Mcp-Session-Id"] = sid
        }
        return jsonResp(200, rpcResult(id, result), extra)
    }

    private fun handleToolsList(id: Any?, modern: Boolean): HttpResp {
        val result = JSONObject().put("tools", ToolRegistry.toJsonArray())
        if (modern) {
            result.put("resultType", "complete")
        }
        return jsonResp(200, rpcResult(id, result))
    }

    private fun handleToolsCall(id: Any?, params: JSONObject): HttpResp {
        val name = params.optString("name", "")
        val tool = ToolRegistry.get(name)
            ?: return jsonResp(200, rpcError(id, -32602, "unknown tool: $name"))

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
            .put("structuredContent", payload)
        return jsonResp(200, rpcResult(id, result))
    }

    // ------------------------------------------------------------------ utils

    private fun isModern(params: JSONObject, headers: Map<String, String>): Boolean {
        if (params.has("_meta")) return true
        val v = headers["mcp-protocol-version"]
        return v != null && v.startsWith("2026")
    }

    private fun rpcResult(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

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

    companion object {
        const val PROTO_MODERN = "2026-07-28"
        const val PROTO_LEGACY = "2025-11-25"
        const val SERVER_VERSION = "0.1.0"
    }
}