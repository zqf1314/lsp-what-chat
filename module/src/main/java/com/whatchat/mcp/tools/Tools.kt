package com.whatchat.mcp.tools

import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONObject

/** 工具装配入口。只在这里能看到完整工具清单。 */
object Tools {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        registerStatusTools()
        registerConversationTools()
        registerMessageTools()
        registerContactTools()
        registerSendTools()
        registerSendFileQuoteTools()
        registerAnalysisTools()
        registerUiTools()
        registerRawSqlTool()
    }

    fun count(): Int = ToolRegistry.all().size

    /** 工具内部统一兜底，任何异常都变成结构化错误而不是 500。 */
    internal inline fun safe(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (t: Throwable) {
        Jsons.err("${t.javaClass.simpleName}: ${t.message}", "TOOL_ERROR")
    }
}