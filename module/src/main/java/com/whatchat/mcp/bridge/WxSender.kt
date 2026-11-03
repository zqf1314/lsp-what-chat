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

    /** 最近一次命中的构造器签名，仅用于日志/诊断。 */
    @Volatile
    private var usedCtor: String = ""

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

        // ---------------------------------------------------------- dry_run：只探测，不实例化
        // 重要：实测 q61.s0 的构造器本身就会把消息入队并发出去（构造 = 发送）。
        // 所以 dry_run 绝不能构造对象，否则就成了“假打真发”。这里只做反射能力探测。
        if (dryRun) {
            val probe = runCatching { probeCapability() }
                .getOrElse { t ->
                    WxLog.e("sendText[dryRun] probe failed", t)
                    return fail(started, true, target, body, atList.size, "能力探测失败：${t.javaClass.simpleName}: ${t.message}")
                }
            WxLog.i("sendText[dryRun] talker=$target len=${body.length} at=${atList.size} $probe")
            return SendResult(
                ok = true, dryRun = true, submitted = false, talker = target,
                textLength = body.length, atCount = atList.size,
                costMs = System.currentTimeMillis() - started,
                message = "dry_run：未实例化也未发送。$probe",
            )
        }

        // ---------------------------------------------------------- 限流（必须在构造前，构造即发送）
        val throttle = checkThrottle()
        if (throttle != null) {
            return fail(started, false, target, body, atList.size, throttle)
        }

        // ---------------------------------------------------------- 造对象 = 发送（微信主线程）
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
            return fail(started, false, target, body, atList.size, reason)
        }

        // 构造已把消息发出去；再按参考实现补一次 doScene（实测不会产生重复消息），失败也不影响结果
        val doScene = runCatching {
            WxEnv.callOnMain(15_000L) { submit(built) } ?: false
        }.getOrElse { t ->
            WxLog.w("sendText: doScene 调用失败（消息已由构造器发出）：${t.javaClass.simpleName} ${t.message}")
            false
        }

        lastSendAt.set(System.currentTimeMillis())
        bumpWindow()
        val cost = System.currentTimeMillis() - started
        WxLog.i("sendText: sent talker=$target len=${body.length} ctor=$usedCtor doScene=$doScene cost=${cost}ms")
        return SendResult(
            ok = true, dryRun = false, submitted = true, talker = target,
            textLength = body.length, atCount = atList.size, costMs = cost,
            message = "已发送（构造即入队）" + if (doScene) "，doScene 已调用" else "，doScene 未匹配（不影响发送）",
        )
    }

    /** dry_run 能力探测：只验证反射坐标是否还在，不创建对象、不触发发送。 */
    private fun probeCapability(): String {
        val cl = WxEnv.classLoader ?: throw IllegalStateException("classLoader 未就绪")
        val cls = Class.forName(MSG_CLASS, true, cl)
        val ctors = cls.declaredConstructors
            .filter { c ->
                val p = c.parameterTypes
                p.size == 6 && p[0] == String::class.java && p[1] == String::class.java &&
                    isInt(p[2]) && isInt(p[3]) && p[5] == String::class.java
            }
            .joinToString(" | ") { "(" + it.parameterTypes.joinToString(",") { t -> t.simpleName } + ")" }
        val kernel = Class.forName(KERNEL_CLASS, true, cl)
        val getter = kernel.declaredMethods.firstOrNull {
            it.name == NETWORK_GETTER && Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        }
        return "net=$MSG_CLASS ctors=[$ctors] kernel=$KERNEL_CLASS " +
            (if (getter != null) "getter=$NETWORK_GETTER() ok" else "getter=missing(" + staticSummary(kernel) + ")")
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

        // 8.0.80 实测只有这两个 6 参构造：
        //   (String,String,int,int,long,String)   —— 不带附加信息（纯文本实测可发）
        //   (String,String,int,int,Object,String) —— 第 5 参接 HashMap（@ 列表走这条）
        // 带 @ 时优先 Object 变体（参考实现就是这么传的），纯文本则先用 long 变体（已验证）。
        val withMap = ArrayList<Pair<Constructor<*>, Array<Any?>>>()
        val withLong = ArrayList<Pair<Constructor<*>, Array<Any?>>>()
        cls.declaredConstructors.forEach { c ->
            val p = c.parameterTypes
            if (p.size != 6 || p[0] != String::class.java || p[1] != String::class.java ||
                !isInt(p[2]) || !isInt(p[3]) || p[5] != String::class.java
            ) {
                return@forEach
            }
            when {
                p[4] == Any::class.java || p[4] == Object::class.java || Map::class.java.isAssignableFrom(p[4]) ->
                    withMap.add(c to arrayOf<Any?>(talker, text, MSG_TYPE, MSG_SCENE, map, ""))
                p[4] == java.lang.Long::class.java || p[4] == Long::class.javaPrimitiveType ->
                    withLong.add(c to arrayOf<Any?>(talker, text, MSG_TYPE, MSG_SCENE, 0L, ""))
            }
        }
        val candidates: List<Pair<Constructor<*>, Array<Any?>>> =
            if (atWxIds.isEmpty()) withLong + withMap else withMap + withLong

        if (candidates.isEmpty()) {
            throw IllegalStateException("没找到可用的 6 参构造器，实际：" + ctorSummary(cls))
        }

        var last: Throwable? = null
        candidates.forEach { (ctor, args) ->
            runCatching {
                ctor.isAccessible = true
                usedCtor = "(" + ctor.parameterTypes.joinToString(",") { it.simpleName } + ")"
                return ctor.newInstance(*args)
            }.onFailure { last = it }
        }
        throw IllegalStateException(
            "构造器全部失败：" + (last?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown") +
                "，实际：" + ctorSummary(cls)
        )
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