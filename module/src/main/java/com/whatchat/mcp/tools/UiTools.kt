package com.whatchat.mcp.tools

import android.content.Context
import android.content.Intent
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONObject

/**
 * 把 wxadd.sh 里**唯一实测可用**的那条路搬进微信进程内：按 wxid 打开对方资料页。
 *
 * 脚本靠 root + `am start` 越过 `exported=false`；本模块注入在微信进程里（同 uid/同包），
 * 直接 startActivity 启动微信自己的 Activity 完全合法 —— 不需要 root、不需要 am。
 *
 * 只保留 ContactInfoUI 一个目标：
 *   - 实测 arrived=true，资料页底部即「添加到通讯录」；
 *   - 脚本里的 sayhi / add / room 均已移除（sayhi 打开后不可用，add/room 直连会把微信带崩）。
 */
private const val UI_WX_PKG = "com.tencent.mm"
private const val UI_CLS_PROFILE = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"

fun registerUiTools() {
    registerOpenProfile()
}

private fun registerOpenProfile() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_open_profile",
            title = "打开对方资料页",
            description = "按 wxid 打开这个人的微信资料页，底部就是「添加到通讯录」；从群里加人时带上 room + nickname 命中率更高。" +
                "在微信进程内直接 startActivity，不需要 root（外部 wxadd.sh 靠 am start 才要 uid0）。" +
                "只切换微信里的页面：不发送好友申请、不改任何数据，点「添加」由你自己完成。",
            inputSchema = Schema.obj(
                required = listOf("wxid"),
                props = listOf(
                    "wxid" to Schema.str("目标 wxid"),
                    "room" to Schema.str("可选：来源群 ID（xxx@chatroom）"),
                    "nickname" to Schema.str("可选：对方的群昵称"),
                    "dry_run" to Schema.bool("只返回将要构造的 Intent，不真正启动", false)
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = true,
            openWorld = false
        ) { args ->
            Tools.safe {
                val wxid = args.optString("wxid", "").trim()
                val room = args.optString("room", "").trim()
                val nick = args.optString("nickname", "").trim()
                val dryRun = args.optBoolean("dry_run", false)

                if (wxid.isEmpty()) return@safe Jsons.err("必须给 wxid", "BAD_ARGUMENT")
                if (wxid.length > 64 || wxid.any { it.isWhitespace() }) {
                    return@safe Jsons.err("wxid 不像 wxid：$wxid", "BAD_ARGUMENT")
                }
                if (room.isNotEmpty() && !room.endsWith("@chatroom")) {
                    return@safe Jsons.err("room 必须以 @chatroom 结尾：$room", "BAD_ROOM")
                }

                val ctx = uiContext() ?: return@safe Jsons.err("拿不到微信 Context", "NO_CONTEXT")

                // 类不在说明微信改了 Activity 名，给明确错误，别变成 ActivityNotFoundException
                if (runCatching { ctx.classLoader.loadClass(UI_CLS_PROFILE) }.isFailure) {
                    return@safe Jsons.err("微信里找不到 $UI_CLS_PROFILE（版本变化？）", "CLASS_NOT_FOUND")
                }

                val intent = Intent()
                    .setClassName(UI_WX_PKG, UI_CLS_PROFILE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val extras = JSONObject()
                fun putS(k: String, v: String) { intent.putExtra(k, v); extras.put(k, v) }

                // extra key 与 wxadd.sh 的 profile 分支一致（8.0.80 反编译确认）
                putS("Contact_User", wxid)
                intent.putExtra("Contact_Scene", 9)
                extras.put("Contact_Scene", 9)
                if (nick.isNotEmpty()) {
                    putS("Contact_Nick", nick)
                    putS("Contact_RoomNickname", nick)
                }
                if (room.isNotEmpty()) putS("Contact_ChatRoomId", room)

                val component = "$UI_WX_PKG/$UI_CLS_PROFILE"
                if (dryRun) {
                    return@safe Jsons.ok(
                        JSONObject()
                            .put("component", component)
                            .put("extras", extras)
                            .put("dryRun", true)
                            .put("started", false)
                    )
                }

                val err = runCatching { ctx.startActivity(intent) }.exceptionOrNull()
                if (err != null) {
                    return@safe Jsons.err(
                        "startActivity 失败：${err.javaClass.simpleName}: ${err.message}",
                        "START_FAILED"
                    )
                }

                // 回读微信进程内的栈顶，确认页面确实起来了
                runCatching { Thread.sleep(400) }
                val top = uiTopActivity()
                val arrived = top.contains(UI_CLS_PROFILE.substringAfterLast('.'))

                Jsons.ok(
                    JSONObject()
                        .put("component", component)
                        .put("extras", extras)
                        .put("dryRun", false)
                        .put("started", true)
                        .put("topActivity", top)
                        .put("arrived", arrived)
                        .put(
                            "hint",
                            if (arrived) "资料页已打开，底部就是「添加到通讯录」；切回微信即可看到"
                            else "启动请求已发出但没抓到资料页；先把微信切到前台再试"
                        )
                )
            }
        }
    )
}

/** 微信进程内的 Application Context。 */
private fun uiContext(): Context? = runCatching {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication")
        .invoke(null) as? Context
}.getOrNull()

/**
 * 读当前 resumed 的 Activity 全名（ActivityThread.mActivities 反射）。
 * 只用来判断“是否跳成功”，读不到返回空串，不影响主流程。
 */
private fun uiTopActivity(): String = runCatching {
    val at = Class.forName("android.app.ActivityThread")
    val thread = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching ""
    val field = at.getDeclaredField("mActivities").apply { isAccessible = true }
    val map = field.get(thread) as? Map<*, *> ?: return@runCatching ""
    var top = ""
    map.values.forEach { rec ->
        if (rec == null) return@forEach
        val paused = runCatching {
            rec.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(rec)
        }.getOrDefault(true)
        if (paused) return@forEach
        val act = runCatching {
            rec.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(rec)
        }.getOrNull() ?: return@forEach
        top = act.javaClass.name
    }
    top
}.getOrDefault("")