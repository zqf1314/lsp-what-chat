package com.whatchat.mcp.server

import org.json.JSONObject

/** 一次 HTTP 响应。 */
data class HttpResp(
    val code: Int,
    val contentType: String,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 工具统一返回结构：{"ok":true,"data":...} 或 {"ok":false,"error":{code,message}} */
object Jsons {

    fun ok(data: Any? = null): JSONObject {
        val o = JSONObject()
            .put("ok", true)
        if (data != null) {
            o.put("data", data)
        }
        return o
    }

    fun err(message: String, code: String = "ERROR"): JSONObject =
        JSONObject()
            .put("ok", false)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
}