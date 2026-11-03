package com.whatchat.mcp.tools

import android.content.Context
import android.content.Intent
import com.whatchat.mcp.server.Jsons
import com.whatchat.mcp.server.Schema
import com.whatchat.mcp.server.ToolDef
import com.whatchat.mcp.server.ToolRegistry
import org.json.JSONObject

/**
 * UI 跳转：把 wxadd.sh 里真正有用的那一半搬进微信进程内。
 *
 * 脚本靠 root + `am start` 越过 `exported=false` 的限制；本模块本来就注入在
 * 微信进程里（同 uid/同包），直接 startActivity 启动微信自己的 Activity 完全合法，
 * 所以**不需要 root、不需要 am**。
 *
 * 只保留两条实测通过的路径：
 *   profile → ContactInfoUI（资料页，底部「添加到通讯录」）
 *   sayhi   → SayHiWithSnsPermissionUI（打招呼/发送验证申请）
 *
 * 脚本里的 `add`（FTSAddFriendUI）与 `room`（ChatroomInfoUI）已移除：
 * 前者不针对具体人、后者要自己在群里点头像，且直连启动这两个页面在 8.0.80 上
 * 会把微信进程带崩（实测），不属于本工具的目标场景。
 */
private const val UI_WX_PKG = "com.tencent.mm"
private const val UI_CLS_PROFILE = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
private const val UI_CLS_SAYHI = "com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI"

fun registerUiTools() {
    registerOpenAddFriend()
}

private fun registerOpenAddFriend() {
    ToolRegistry.register(
        ToolDef(
            name = "wx_open_add_friend",
            title = "打开对方资料页/打招呼页",
            description = "按 wxid 拉起某个人的微信资料页（底部就是「添加到通讯录」）或「打招呼」页；" +
                "从群里加人时带上 room + nickname 能显示群昵称、命中率更高。" +
                "在微信进程内直接 startActivity，不需要 root（外部 wxadd.sh 靠 am start 才要 uid0）。" +
                "只切换前台界面：不发送好友申请、不改任何数据，点「发送」由你自己完成。",
            inputSchema = Schema.obj(
                required = listOf("wxid"),
                props = listOf(
                    "wxid" to Schema.str("目标 wxid"),
                    "mode" to Schema.str("profile=资料页（默认）/ sayhi=打招呼页", "profile"),
                    "room" to Schema.str("可选：来源群 ID（xxx@chatroom）"),
                    "nickname" to Schema.str("可选：对方的群昵称"),
                    "force_get_contact" to Schema.bool("本地查不到资料时按 wxid 去服务器拉（force_get_contact）", false),
                    "verify" to Schema.bool("按“需要验证”态进入（User_Verify）", false),
                    "dry_run" to Schema.bool("只返回将要构造的 Intent，不真正启动", false)
                )
            ),
            readOnly = false,
            destructive = false,
            idempotent = true,
            openWorld = false
        ) { args ->
            Tools.safe {
                val mode = args.optString("mode", "profile").trim().ifBlank { "profile" }
                val wxid = args.optString("wxid", "").trim()
                val room = args.optString("room", "").trim()
                val nick = args.optString("nickname", "").trim()
                val force = args.optBoolean("force_get_contact", false)
                val verify = args.optBoolean("verify", false)
                val dryRun = args.optBoolean("dry_run", false)

                if (mode !in setOf("profile", "sayhi")) {
                    return@safe Jsons.err(
                        "mode 只支持 profile（资料页）/ sayhi（打招呼页），收到：$mode",
                        "BAD_MODE"
                    )
                }
                if (wxid.isEmpty()) return@safe Jsons.err("必须给 wxid", "BAD_ARGUMENT")
                if (wxid.length > 64 || wxid.any { it.isWhitespace() }) {
                    return@safe Jsons.err("wxid 不像 wxid：$wxid", "BAD_ARGUMENT")
                }
                if (room.isNotEmpty() && !room.endsWith("@chatroom")) {
                    return@safe Jsons.err("room 必须以 @chatroom 结尾：$room", "BAD_ROOM")
                }

                val cls = if (mode == "sayhi") UI_CLS_SAYHI else UI_CLS_PROFILE
                val ctx = uiContext() ?: return@safe Jsons.err("拿不到微信 Context", "NO_CONTEXT")

                // 类不在说明微信改了 Activity 名，给明确错误，别变成 ActivityNotFoundException
                if (runCatching { ctx.classLoader.loadClass(cls) }.isFailure) {
                    return@safe Jsons.err("微信里找不到 $cls（版本变化？）", "CLASS_NOT_FOUND")
                }

                val intent = Intent().setClassName(UI_WX_PKG, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val extras = JSONObject()
                fun putS(k: String, v: String) { intent.putExtra(k, v); extras.put(k, v) }
                fun putI(k: String, v: Int) { intent.putExtra(k, v); extras.put(k, v) }
                fun putB(k: String, v: Boolean) { intent.putExtra(k, v); extras.put(k, v) }

                // extra key 与 wxadd.sh 一致（8.0.80 反编译确认）
                putS("Contact_User", wxid)
                putI("Contact_Scene", 9)
                if (nick.isNotEmpty()) {
                    putS("Contact_Nick", nick)
                    putS("Contact_RoomNickname", nick)
                }
                if (room.isNotEmpty()) putS("Contact_ChatRoomId", room)
                if (force) putB("force_get_contact", true)
                if (verify) putB("User_Verify", true)

                val component = "$UI_WX_PKG/$cls"
                if (dryRun) {
                    return@safe Jsons.ok(
                        JSONObject()
                            .put("mode", mode)
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

                // 给系统一点时间搬到前台，再回看一眼栈顶，判断是否真的跳过去了
                runCatching { Thread.sleep(400) }
                val top = uiTopActivity()
                val arrived = top.contains(cls.substringAfterLast('.'))

                Jsons.ok(
                    JSONObject()
                        .put("mode", mode)
                        .put("component", component)
                        .put("extras", extras)
                        .put("dryRun", false)
                        .put("started", true)
                        .put("topActivity", top)
                        .put("arrived", arrived)
                        .put(
                            "hint",
                            if (arrived) "已跳转，剩下的「发送」由你自己点"
                            else "启动请求已发出但没抓到目标页；微信不在前台时系统可能拦下后台启动，先把微信切到前台再试"
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