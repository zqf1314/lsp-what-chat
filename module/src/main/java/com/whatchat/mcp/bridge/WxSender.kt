package com.whatchat.mcp.bridge

import com.whatchat.mcp.WxEnv
import com.whatchat.mcp.WxLog
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 微信 8.0.80 发消息（坐标来自 `com.wechat.wechatai` 1.1.8 的 `Lz2/l54;` 常量表）。
 *
 * 参考实现的做法：自己造 netScene 对象 → 交给微信网络核心 `doScene`，不点 UI、不模拟点击。
 * 它在 callback 为空时走的最简路径（`Lz2/d26;->ʾ` 的 `cond_bb` 分支）就是三步：
 *
 *   1. `net = new q61.s0(talker, text, Integer(1), Integer(0x123), HashMap, "")`
 *      HashMap 里只有 `"atuserlist" -> "<![CDATA[wxid1,wxid2]]>"`
 *   2. `kernel = Class.forName("w41.i9")`，静态无参 `e()` 拿网络核心
 *      （同一个内核类：DB 走 `b()`，网络走 `e()`）
 *   3. `network.i(net, Integer(0))` 提交
 *
 * 全部反射，且在微信主线程执行；只做发送，不改任何微信状态。
 */
object WxSender {

    private const val MSG_CLASS = "q61.s0"
    private const val KERNEL_CLASS = "w41.i9"
    private const val NETWORK_GETTER = "e"
    private const val SEND_METHOD = "i"

    /** 参考实现的正文上限：0xfa0 = 4000 字。 */
    const val MAX_TEXT_LENGTH = 4000

    /** 单条消息的类型/场景参数，参考实现写死成 1 与 0x123。 */
    private const val MSG_TYPE = 1
    private const val MSG_SCENE = 0x123

    // ------------------------------------------------------------------ 限流

    /** 两条消息之间的最小间隔。 */
    const val MIN_INTERVAL_MS = 1_500L

    /** 限流窗口与窗口内最大条数。 */
    const val WINDOW_MS = 60_000L
    const val WINDOW_MAX = 20

    private val lastSendAt = AtomicLong(0)
    private val windowStart = AtomicLong(0)
    private val windowCount = AtomicInteger(0)

    data class SendResult(
        val ok: Boolean,
        val dryRun: Boolean,
        val submitted: Boolean,
        val talker: String,
        val textLength: Int,
        val atCount: Int,
        val costMs: Long,
        val message: String,
    )

    fun sendText(
        talker: String,
        text: String,
        atWxIds: List<String> = emptyList(),
        dryRun: Boolean = true,
    ): SendResult {
        val started = System.currentTimeMillis()
        val target = talker.trim()
        val body = text.trim()

        if (target.isEmpty()) return fail(started, dryRun, target, body, atWxIds.size, "talker 不能为空")
        if (body.isEmpty()) return fail(started, dryRun, target, body, atWxIds.size, "text 不能为空")
        if (body.length > MAX_TEXT_LENGTH) {
            return fail(started, dryRun, target, body, atWxIds.size, "text 过长：${body.length}，最多 $MAX_TEXT_LENGTH 字符")
        }

        val atList = atWxIds.map { it.trim() }.filter { it.isNotEmpty() }

        // ---------------------------------------------------------- 造对象（微信主线程）
        val outcome = buildOnMain(target, body, atList)
        val built = outcome.scene
        if (built == null) {
            val detail = describeFailure(target)
            val err = outcome.error
            val reason = when {
                outcome.timedOut -> "构造消息对象超时：主线程 15s 未返回"
                err != null -> "构造消息对象失败：${err.javaClass.simpleName}: ${err.message}"
                else -> "构造消息对象失败：返回 null"
            }
            WxLog.e("sendText: $reason, $detail")
            return fail(started, dryRun, target, body, atList.size, reason)
        }

        if (dryRun) {
            WxLog.i("sendText[dryRun] talker=$target len=${body.length} at=${atList.size} net=${built.javaClass.name}")
            return SendResult(
                ok = true, dryRun = true, submitted = false, talker = target,
                textLength = body.length, atCount = atList.size,
                costMs = System.currentTimeMillis() - started,
                message = "dry_run：消息对象已构造（${built.javaClass.name}），未提交给网络层",
            )
        }

        // ---------------------------------------------------------- 限流
        val throttle = checkThrottle()
        if (throttle != null) {
            return fail(started, dryRun, target, body, atList.size, throttle)
        }

        // ---------------------------------------------------------- 提交
        val submitted = runCatching {
            WxEnv.callOnMain(15_000L) { submit(built) } ?: false
        }.getOrElse { t ->
            WxLog.e("sendText: submit failed", t)
            return fail(started, dryRun, target, body, atList.size, "提交失败：${t.javaClass.simpleName}: ${t.message}")
        }

        val cost = System.currentTimeMillis() - started
        return if (submitted) {
            lastSendAt.set(System.currentTimeMillis())
            bumpWindow()
            WxLog.i("sendText: submitted talker=$target len=${body.length} cost=${cost}ms")
            SendResult(
                ok = true, dryRun = false, submitted = true, talker = target,
                textLength = body.length, atCount = atList.size, costMs = cost,
                message = "已提交给微信网络层（不代表对方已收到）",
            )
        } else {
            fail(started, dryRun, target, body, atList.size, "未找到可用的网络核心或提交方法")
        }
    }

    // ------------------------------------------------------------------ 反射

    private class BuildOutcome(
        val scene: Any? = null,
        val error: Throwable? = null,
        val timedOut: Boolean = false,
    )

    /** 参考实现整条发送链都跑在微信主线程；构造消息对象也放主线程，避免碰到主线程断言。 */
    private fun buildOnMain(talker: String, text: String, atWxIds: List<String>): BuildOutcome {
        val holder = arrayOfNulls<Any?>(1)
        val err = arrayOfNulls<Throwable>(1)
        val done = WxEnv.callOnMain(15_000L) {
            try {
                holder[0] = buildNetScene(talker, text, atWxIds)
            } catch (t: Throwable) {
                err[0] = t
            }
            true
        }
        return when {
            done == null -> BuildOutcome(timedOut = true)
            err[0] != null -> BuildOutcome(error = err[0])
            holder[0] == null -> BuildOutcome(error = IllegalStateException("构造器返回 null"))
            else -> BuildOutcome(scene = holder[0])
        }
    }

    private fun buildNetScene(talker: String, text: String, atWxIds: List<String>): Any {
        val cl = WxEnv.classLoader ?: throw IllegalStateException("classLoader 未就绪")
        val cls = Class.forName(MSG_CLASS, true, cl)

        val map = HashMap<String, String>()
        // 参考实现无条件塞这一项，@ 列表为空时就是空 CDATA
        map["atuserlist"] = "<![CDATA[" + atWxIds.joinToString(",") + "]]>"

        val ctor = findConstructor(cls)
            ?: throw IllegalStateException("没找到 6 参构造器，实际：" + ctorSummary(cls))
        ctor.isAccessible = true
        return ctor.newInstance(talker, text, MSG_TYPE, MSG_SCENE, map, "")
    }

    private fun findConstructor(cls: Class<*>): Constructor<*>? =
        cls.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 6 &&
                p[0] == String::class.java &&
                p[1] == String::class.java &&
                isInt(p[2]) &&
                isInt(p[3]) &&
                Map::class.java.isAssignableFrom(p[4]) &&
                p[5] == String::class.java
        }

    private fun submit(netScene: Any): Boolean {
        val cl = WxEnv.classLoader ?: throw IllegalStateException("classLoader 未就绪")
        val kernel = Class.forName(KERNEL_CLASS, true, cl)

        val network = runCatching { kernel.getMethod(NETWORK_GETTER).invoke(null) }
            .getOrNull()
            ?: runCatching {
                kernel.declaredMethods.firstOrNull {
                    it.name == NETWORK_GETTER && Modifier.isStatic(it.modifiers) && it.parameterCount == 0
                }?.also { it.isAccessible = true }?.invoke(null)
            }.getOrNull()
            ?: run {
                WxLog.e("sendText: $KERNEL_CLASS.$NETWORK_GETTER() 不可用，静态方法：" + staticSummary(kernel))
                return false
            }

        val method = findSendMethod(network, netScene)
            ?: run {
                WxLog.e("sendText: ${network.javaClass.name} 上没找到 $SEND_METHOD(net,int)，候选：" + sendCandidates(network))
                return false
            }

        method.isAccessible = true
        method.invoke(network, netScene, 0)
        return true
    }

    private fun findSendMethod(network: Any, netScene: Any): Method? =
        (network.javaClass.methods.asList() + network.javaClass.declaredMethods.asList())
            .distinct()
            .firstOrNull { m ->
                m.name == SEND_METHOD &&
                    m.parameterCount == 2 &&
                    m.parameterTypes[0].isInstance(netScene) &&
                    isInt(m.parameterTypes[1])
            }

    private fun isInt(t: Class<*>): Boolean =
        t == Int::class.javaPrimitiveType || t == Integer::class.java

    // ------------------------------------------------------------------ 限流实现

    private fun checkThrottle(): String? {
        val now = System.currentTimeMillis()
        val last = lastSendAt.get()
        if (last > 0 && now - last < MIN_INTERVAL_MS) {
            return "发送过快，请 ${MIN_INTERVAL_MS - (now - last)}ms 后再试"
        }
        val start = windowStart.get()
        if (start == 0L || now - start > WINDOW_MS) {
            windowStart.set(now)
            windowCount.set(0)
            return null
        }
        if (windowCount.get() >= WINDOW_MAX) {
            return "已达到限流上限：每分钟 $WINDOW_MAX 条"
        }
        return null
    }

    private fun bumpWindow() {
        val now = System.currentTimeMillis()
        if (windowStart.get() == 0L || now - windowStart.get() > WINDOW_MS) {
            windowStart.set(now)
            windowCount.set(1)
        } else {
            windowCount.incrementAndGet()
        }
    }

    /** 当前限流窗口已用条数，供 `wx_send_limits` 展示。 */
    fun windowUsed(): Int {
        val start = windowStart.get()
        if (start == 0L || System.currentTimeMillis() - start > WINDOW_MS) return 0
        return windowCount.get()
    }

    // ------------------------------------------------------------------ 诊断

    private fun fail(
        started: Long, dryRun: Boolean, talker: String,
        text: String, atCount: Int, message: String,
    ) = SendResult(
        ok = false, dryRun = dryRun, submitted = false, talker = talker,
        textLength = text.length, atCount = atCount,
        costMs = System.currentTimeMillis() - started,
        message = message,
    )

    private fun ctorSummary(cls: Class<*>): String =
        cls.declaredConstructors.joinToString("; ") { c ->
            "(" + c.parameterTypes.joinToString(",") { it.simpleName } + ")"
        }

    private fun staticSummary(cls: Class<*>): String =
        cls.declaredMethods.filter { Modifier.isStatic(it.modifiers) }
            .take(30)
            .joinToString(",") { it.name + "/" + it.parameterCount }

    private fun sendCandidates(network: Any): String =
        network.javaClass.methods.filter { m ->
            m.parameterCount == 2 && m.parameterTypes[0].name.contains("NetScene", true)
        }.take(20).joinToString(",") { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }

    private fun describeFailure(talker: String): String = buildString {
        append("talker=").append(talker)
        runCatching {
            val cl = WxEnv.classLoader ?: return@runCatching
            val cls = Class.forName(MSG_CLASS, true, cl)
            append(" ctors=").append(ctorSummary(cls))
        }
    }
}