package com.whatchat.mcp.bridge

/**
 * 会话/联系人的展示名解析。
 *
 * 微信的显示名规则：群昵称 > 备注名 > 昵称 > wxid。
 * 这里只做最小实现，避免一次 JOIN 太多表导致字段兼容性风险。
 */
object DisplayName {

    fun of(username: String, remark: String?, nickname: String?): String {
        if (!remark.isNullOrBlank()) return remark
        if (!nickname.isNullOrBlank()) return nickname
        return username
    }

    fun isChatroom(username: String): Boolean = username.endsWith("@chatroom")

    /** 群消息 content 形如 "wxid_xxx:\n正文"，取出真正的发送人与正文。 */
    fun splitGroupContent(content: String?, talker: String): Pair<String, String> {
        if (content == null) return "" to ""
        if (!isChatroom(talker)) return "" to content
        val idx = content.indexOf(":\n")
        if (idx <= 0 || idx > 64) return "" to content
        val sender = content.substring(0, idx)
        if (!sender.startsWith("wxid_") && !sender.endsWith("@chatroom")) return "" to content
        return sender to content.substring(idx + 2)
    }
}