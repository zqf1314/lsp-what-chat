package com.whatchat.mcp.server

import org.json.JSONArray
import org.json.JSONObject

/** 一个 MCP 工具的完整定义。 */
class ToolDef(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean = true,
    val destructive: Boolean = false,
    val idempotent: Boolean = true,
    val openWorld: Boolean = false,
    val handler: (JSONObject) -> JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("title", title)
        .put("description", description)
        .put("inputSchema", inputSchema)
        .put(
            "annotations",
            JSONObject()
                .put("readOnlyHint", readOnly)
                .put("destructiveHint", destructive)
                .put("idempotentHint", idempotent)
                .put("openWorldHint", openWorld)
        )
}

object ToolRegistry {

    private val tools = LinkedHashMap<String, ToolDef>()

    fun register(tool: ToolDef) {
        tools[tool.name] = tool
    }

    fun get(name: String): ToolDef? = tools[name]

    fun all(): List<ToolDef> = tools.values.toList()

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        tools.values.forEach { arr.put(it.toJson()) }
        return arr
    }
}

/** JSON Schema 构造小工具，避免手写大量嵌套 JSONObject。 */
object Schema {

    fun obj(required: List<String> = emptyList(), props: List<Pair<String, JSONObject>>): JSONObject {
        val properties = JSONObject()
        props.forEach { (key, value) -> properties.put(key, value) }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
    }

    fun str(desc: String, default: String? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }

    fun int(desc: String, default: Int? = null, min: Int? = null, max: Int? = null): JSONObject {
        val o = JSONObject().put("type", "integer").put("description", desc)
        if (default != null) o.put("default", default)
        if (min != null) o.put("minimum", min)
        if (max != null) o.put("maximum", max)
        return o
    }

    fun bool(desc: String, default: Boolean? = null): JSONObject {
        val o = JSONObject().put("type", "boolean").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }

    /** 字符串数组，例如 @ 的 wxid 列表。 */
    fun arrStr(desc: String, minItems: Int? = null, maxItems: Int? = null): JSONObject {
        val o = JSONObject()
            .put("type", "array")
            .put("description", desc)
            .put("items", JSONObject().put("type", "string"))
        if (minItems != null) o.put("minItems", minItems)
        if (maxItems != null) o.put("maxItems", maxItems)
        return o
    }
}