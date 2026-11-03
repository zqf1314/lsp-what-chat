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

    /** 图片发送核心（来自参考实现 l54 常量表：ʻˑ=lt5.o5 枚举容器、ˉˉ=lt5.d5 核心、ʼᵢ=Lj 方法名）。 */
    private const val CORE_ENUM = "lt5.o5"
    private const val CORE_CLASS = "lt5.d5"
    private const val IMAGE_METHOD = "Lj"

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
        msgType: Int = MSG_TYPE,
        msgClass: String = MSG_CLASS,
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
        val outcome = buildOnMain(target, body, atList, msgType, msgClass)
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

    // ------------------------------------------------------------------ 图片

    @Volatile
    private var sendCoreCache: Any? = null

    data class MediaResult(
        val ok: Boolean,
        val dryRun: Boolean,
        val submitted: Boolean,
        val talker: String,
        val path: String,
        val sizeBytes: Long,
        val costMs: Long,
        val message: String,
    )

    /**
     * 发图片。坐标（8.0.80，来自参考实现 l54 常量表）：
     *   枚举 `lt5.o5` 的常量 → `get()` → 实例类型必须是 `lt5.d5`（发送核心）
     *   调核心的 `Lj(Context, talker, imagePath, 0, "", "", null, null, null)`（9 参）
     */
    fun sendImage(talker: String, imagePath: String, dryRun: Boolean = true): MediaResult {
        val started = System.currentTimeMillis()
        val target = talker.trim()
        val path = imagePath.trim()

        if (target.isEmpty()) return mediaFail(started, dryRun, target, path, 0L, "talker 不能为空")
        if (path.isEmpty()) return mediaFail(started, dryRun, target, path, 0L, "path 不能为空")
        val file = java.io.File(path)
        if (!file.exists()) return mediaFail(started, dryRun, target, path, 0L, "文件不存在：$path")
        if (!file.isFile) return mediaFail(started, dryRun, target, path, 0L, "不是普通文件：$path")
        // 关键前置：必须是「微信本进程」能读到的文件。
        // /sdcard 走 FUSE + 分区存储，别的 app / adb shell 创建的文件即便 chmod 644、
        // 即便放进 com.tencent.mm 目录，微信进程也读不到；此时发送核心不报错、不入库，
        // 表现为 submitted=true 而消息库没有新记录。这里提前拦下并给出可操作提示。
        if (!file.canRead()) {
            return mediaFail(
                started, dryRun, target, path, 0L,
                "当前进程无法读取该文件（Android 分区存储限制）：$path。" +
                    "请改用微信自己可读的路径（例如用微信保存/接收过的文件、" +
                    "/sdcard/Android/data/com.tencent.mm/MicroMsg/ 下由微信创建的文件），" +
                    "或让本模块自己落盘后再发；chmod / chown 对本进程无效。"
            )
        }
        val size = runCatching { file.length() }.getOrDefault(0L)

        if (dryRun) {
            val probe = runCatching { probeImage() }.getOrElse { t ->
                WxLog.e("sendImage[dryRun] probe failed", t)
                return mediaFail(started, true, target, path, size, "能力探测失败：${t.javaClass.simpleName}: ${t.message}")
            }
            WxLog.i("sendImage[dryRun] talker=$target path=$path size=$size $probe")
            return MediaResult(true, true, false, target, path, size, System.currentTimeMillis() - started, "dry_run：未发送。$probe")
        }

        val throttle = checkThrottle()
        if (throttle != null) return mediaFail(started, false, target, path, size, throttle)

        val ok = runCatching {
            WxEnv.callOnMain(20_000L) { sendImageNow(target, path) } ?: false
        }.getOrElse { t ->
            WxLog.e("sendImage failed talker=$target", t)
            return mediaFail(started, false, target, path, size, "发送失败：${t.javaClass.simpleName}: ${t.message}")
        }
        if (!ok) return mediaFail(started, false, target, path, size, "发送核心没找到或方法未命中，看日志 probe")

        lastSendAt.set(System.currentTimeMillis())
        bumpWindow()
        val cost = System.currentTimeMillis() - started
        WxLog.i("sendImage: sent talker=$target size=$size cost=${cost}ms")
        return MediaResult(true, false, true, target, path, size, cost, "已提交给发送核心（不代表对方已收到）")
    }

    private fun sendImageNow(talker: String, path: String): Boolean {
        val core = resolveSendCore() ?: run {
            WxLog.e("sendImage: 没找到发送核心 $CORE_CLASS，$CORE_ENUM 枚举=" + enumSummary())
            return false
        }
        val m = findMediaMethod(core.javaClass, IMAGE_METHOD)
            ?: run {
                WxLog.e("sendImage: ${core.javaClass.name} 上没有 $IMAGE_METHOD/9，候选：" + mediaCandidates(core))
                return false
            }
        val app = WxEnv.app ?: run {
            WxLog.e("sendImage: Application 未就绪")
            return false
        }
        m.isAccessible = true
        m.invoke(core, app, talker, path, 0, "", "", null, null, null)
        return true
    }

    /** 枚举 lt5.o5 里挨个调 get()，取第一个类型是 lt5.d5 的实例。 */
    private fun resolveSendCore(): Any? {
        sendCoreCache?.let { return it }
        val cl = WxEnv.classLoader ?: return null
        val enumCls = Class.forName(CORE_ENUM, true, cl)
        if (!enumCls.isEnum) {
            WxLog.e("sendImage: $CORE_ENUM 不是枚举")
            return null
        }
        val target = Class.forName(CORE_CLASS, true, cl)
        val constants = enumCls.enumConstants ?: return null
        for (c in constants) {
            val inst = runCatching {
                val g = c.javaClass.getMethod("get")
                g.isAccessible = true
                g.invoke(c)
            }.getOrNull() ?: continue
            if (target.isInstance(inst)) {
                sendCoreCache = inst
                return inst
            }
        }
        return null
    }

    /** 诊断用：列出指定类的全部方法签名，供坐标勘察（不发送任何消息）。 */
    fun dumpMethods(clsName: String, max: Int = 200): String {
        val cl = WxEnv.classLoader ?: return "classLoader 未就绪"
        val cls = runCatching { Class.forName(clsName, true, cl) }.getOrNull()
            ?: return "$clsName 不存在"
        val sb = StringBuilder("$clsName declared=").append(cls.declaredMethods.size)
        var n = 0
        for (m in cls.declaredMethods.sortedWith(compareBy({ it.name }, { it.parameterCount }))) {
            if (n++ >= max) {
                sb.append("\n…(已截断)")
                break
            }
            val static = java.lang.reflect.Modifier.isStatic(m.modifiers)
            sb.append('\n')
                .append(if (static) "static " else "")
                .append(m.name).append('(')
                .append(m.parameterTypes.joinToString(",") { it.simpleName })
                .append("):").append(m.returnType.simpleName)
        }
        return sb.toString()
    }

    private fun findMediaMethod(cls: Class<*>, name: String): Method? =
        (cls.methods.asList() + cls.declaredMethods.asList())
            .distinct()
            .firstOrNull { m ->
                m.name == name && m.parameterCount == 9 &&
                    m.parameterTypes[1] == String::class.java &&
                    m.parameterTypes[2] == String::class.java
            }

    private fun probeImage(): String = buildString {
        append("core=").append(CORE_CLASS).append(" enum=").append(CORE_ENUM)
        val cl = WxEnv.classLoader
        if (cl == null) {
            append(" classLoader=null")
            return@buildString
        }
        val enumCls = runCatching { Class.forName(CORE_ENUM, true, cl) }.getOrNull()
        if (enumCls == null) {
            append(" enumClass=missing")
            return@buildString
        }
        append(" enumOk=").append(enumCls.isEnum)
        val core = runCatching { resolveSendCore() }.getOrNull()
        if (core == null) {
            append(" core=missing constants=").append(enumSummary())
            return@buildString
        }
        val m = findMediaMethod(core.javaClass, IMAGE_METHOD)
        append(" method=").append(
            m?.let { IMAGE_METHOD + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }
                ?: "missing; 9-param=" + mediaCandidates(core)
        )
    }


    // ------------------------------------------------------------------ 文件 / 引用

    /** 文件发送：同一张发送核方法表上的 Kj(String talker, String path, String fileName)。 */
    private const val FILE_METHOD = "Kj"

    /** 文件发送（照抄参考实现 1.1.8 的 SDK 链路）。 */
    private const val MSG_FILE_OBJECT = "com.tencent.mm.opensdk.modelmsg.WXFileObject"
    private const val MSG_WX_MEDIA = "com.tencent.mm.opensdk.modelmsg.WXMediaMessage"
    private const val FILE_SENDER_CLASS = "com.tencent.mm.pluginsdk.model.app.k0"
    private const val FILE_APP_ID = "wx4310bbd51be7d979"
    private const val FILE_APP_TYPE = "weixinfile"

    /** 引用回复用 appmsg 的 netScene 类（q61.s0 是纯文本，q61.s1 才是 appmsg）。 */
    private const val QUOTE_MSG_CLASS = "q61.s1"

    /** 引用回复走 appmsg：外层消息 type=49，内部 <appmsg><type>57</type><refermsg>… */
    private const val QUOTE_MSG_TYPE = 49
    private const val QUOTE_APPMSG_TYPE = 57

    private const val UNREADABLE_HINT = "当前进程无法读取该文件（Android 分区存储限制）："

    /** 被引用的那条消息（字段语义对齐微信 refermsg）。 */
    data class QuoteRef(
        val svrId: Long,
        val chatUsr: String,
        val fromUsr: String,
        val type: Int,
        val content: String,
        val displayName: String,
        val createTimeMs: Long,
    )

    /** 发文件。坐标：lt5.d5.Kj(talker, path, fileName)（运行时方法表勘察确认）。 */
    fun sendFile(talker: String, filePath: String, fileName: String? = null, dryRun: Boolean = true, method: String = FILE_METHOD): MediaResult {
        val started = System.currentTimeMillis()
        val target = talker.trim()
        val path = filePath.trim()
        if (target.isEmpty()) return mediaFail(started, dryRun, target, path, 0L, "talker 不能为空")
        if (path.isEmpty()) return mediaFail(started, dryRun, target, path, 0L, "path 不能为空")
        val file = java.io.File(path)
        if (!file.exists()) return mediaFail(started, dryRun, target, path, 0L, "文件不存在：$path")
        if (!file.isFile) return mediaFail(started, dryRun, target, path, 0L, "不是普通文件：$path")
        if (!file.canRead()) {
            return mediaFail(
                started, dryRun, target, path, 0L,
                UNREADABLE_HINT + path +
                    "。请改用微信自己可读的路径（微信保存过的文件、或微信数据目录下的文件）；chmod / chown 对本进程无效。"
            )
        }
        val size = runCatching { file.length() }.getOrDefault(0L)
        val name = fileName?.trim().takeUnless { it.isNullOrEmpty() } ?: file.name

        if (dryRun) {
            val probe = runCatching { probeCoreMethod(FILE_METHOD, 3) }.getOrElse { "probe 失败：${it.message}" }
            return MediaResult(
                true, true, false, target, path, size, System.currentTimeMillis() - started,
                "dry_run：未发送。$probe"
            )
        }

        val throttle = checkThrottle()
        if (throttle != null) return mediaFail(started, false, target, path, size, throttle)

        val err = runCatching { WxEnv.callOnMain(20_000L) { sendFileNow(target, path, name, method) } }
            .getOrElse { "主线程执行失败：${it.javaClass.simpleName}: ${it.message}" }
        val cost = System.currentTimeMillis() - started
        if (err != null) {
            WxLog.e("sendFile: $err")
            return mediaFail(started, false, target, path, size, err)
        }
        WxLog.i("sendFile: sent talker=$target name=$name size=$size cost=${cost}ms")
        return MediaResult(true, false, true, target, path, size, cost, "已提交给发送核心（不代表对方已收到）")
    }

    private fun sendFileNow(talker: String, path: String, name: String, method: String): String? {
        val cl = WxEnv.classLoader ?: return "classLoader 未就绪"
        // 完全照抄参考实现 1.1.8 gea.ⁱⁱ 的链路：
        //   obj1 = new WXFileObject(absPath)
        //   obj2 = new WXMediaMessage(obj1); obj2.title = fileName
        //   com.tencent.mm.pluginsdk.model.app.k0.F(obj2, appId, "weixinfile", talker, 2, "")
        val fileObjCls = runCatching { Class.forName(MSG_FILE_OBJECT, true, cl) }.getOrNull()
            ?: return "找不到 $MSG_FILE_OBJECT"
        val mediaCls = runCatching { Class.forName(MSG_WX_MEDIA, true, cl) }.getOrNull()
            ?: return "找不到 $MSG_WX_MEDIA"
        val senderCls = runCatching { Class.forName(FILE_SENDER_CLASS, true, cl) }.getOrNull()
            ?: return "找不到 $FILE_SENDER_CLASS"

        // WXFileObject：优先 String 构造器，退回无参 + setFilePath
        val fileObj = runCatching {
            val c = fileObjCls.declaredConstructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            if (c != null) {
                c.isAccessible = true
                c.newInstance(path)
            } else {
                val o = fileObjCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val s = fileObjCls.methods.firstOrNull { it.name == "setFilePath" && it.parameterCount == 1 }
                    ?: throw IllegalStateException("既无 String 构造器也无 setFilePath")
                s.isAccessible = true
                s.invoke(o, path)
                o
            }
        }.getOrElse { return "构造 WXFileObject 失败：${it.javaClass.simpleName}: ${it.message}" }

        // WXMediaMessage：优先 (WXFileObject) 构造器，退回无参 + 反射写 media 字段
        val msg = runCatching {
            val c = mediaCls.declaredConstructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInstance(fileObj) }
            if (c != null) {
                c.isAccessible = true
                c.newInstance(fileObj)
            } else {
                val o = mediaCls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
                val f = allFields(mediaCls).firstOrNull { it.type.isInstance(fileObj) && !Modifier.isStatic(it.modifiers) }
                    ?: throw IllegalStateException(
                        "无 media 字段，字段表=" + allFields(mediaCls).joinToString(",") { it.name + ":" + it.type.simpleName }
                    )
                f.isAccessible = true
                f.set(o, fileObj)
                o
            }
        }.getOrElse { return "构造 WXMediaMessage 失败：${it.javaClass.simpleName}: ${it.message}" }

        // title：字段优先，退回 setTitle
        val titleField = allFields(mediaCls).firstOrNull { it.name.equals("title", true) && it.type == String::class.java }
        if (titleField != null) {
            runCatching { titleField.isAccessible = true; titleField.set(msg, name) }
        } else {
            runCatching { mediaCls.getMethod("setTitle", String::class.java).invoke(msg, name) }
        }

        val cand = (senderCls.methods.asList() + senderCls.declaredMethods.asList()).distinct()
        val m = cand.firstOrNull { it.name == method && it.parameterCount == 6 && it.parameterTypes[0].isInstance(msg) }
            ?: cand.firstOrNull { it.parameterCount == 6 && it.parameterTypes[0].isInstance(msg) }
            ?: return senderCls.name + " 上没有可用的 6 参发送方法，候选：" + candidateSummary(senderCls.name, 6)

        m.isAccessible = true
        val receiver: Any? = if (Modifier.isStatic(m.modifiers)) null
        else (senderInstance(senderCls) ?: return "k0.${m.name} 是实例方法但拿不到实例")

        val ret = runCatching { m.invoke(receiver, msg, FILE_APP_ID, FILE_APP_TYPE, talker, 2, "") }
            .getOrElse { return "调用 k0.${m.name} 失败：${it.javaClass.simpleName}: ${it.message}" }
        WxLog.i("sendFile: k0.${m.name} ret=$ret")
        return null
    }

    private fun candidateSummary(clsName: String, argc: Int): String = runCatching {
        val cl = WxEnv.classLoader ?: return@runCatching "no-cl"
        Class.forName(clsName, true, cl).declaredMethods.filter { it.parameterCount == argc }
            .take(8)
            .joinToString(";") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
    }.getOrDefault("?")

    /** 沿继承链收集全部字段（反射写 SDK 模型时要用）。 */
    private fun allFields(c: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            out.addAll(k.declaredFields)
            k = k.superclass
        }
        return out
    }

    /** 拿发送器实例：无参构造器优先，Kotlin object 的 INSTANCE 兜底。 */
    private fun senderInstance(c: Class<*>): Any? =
        runCatching { c.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() }.getOrNull()
            ?: runCatching { c.getField("INSTANCE").also { it.isAccessible = true }.get(null) }.getOrNull()

    /** 发引用回复：自拼 refermsg appmsg，再按 type=49 走同一条发送链。 */
    fun sendQuote(
        talker: String,
        text: String,
        ref: QuoteRef,
        atWxIds: List<String> = emptyList(),
        dryRun: Boolean = true,
    ): SendResult {
        val xml = buildQuoteXml(text.trim(), ref, atWxIds)
        val r = sendText(talker, xml, atWxIds, dryRun, QUOTE_MSG_TYPE, QUOTE_MSG_CLASS)
        return if (!r.ok || r.dryRun) r else r.copy(
            message = "已提交引用回复（svrid=${ref.svrId}，appmsg ${r.textLength} 字符）"
        )
    }

    private fun buildQuoteXml(text: String, ref: QuoteRef, atWxIds: List<String>): String {
        val at = if (atWxIds.isEmpty()) "" else
            "<msgsource><atuserlist><![CDATA[" + atWxIds.joinToString(",") + "]]></atuserlist></msgsource>"
        val quoted = if (ref.content.length > 1200) ref.content.substring(0, 1200) else ref.content
        return buildString {
            append("<msg><appmsg appid=\"\" sdkver=\"0\">")
            append("<title>").append(xmlEscape(text)).append("</title>")
            append("<type>").append(QUOTE_APPMSG_TYPE).append("</type>")
            append("<refermsg>")
            append("<type>").append(ref.type).append("</type>")
            append("<svrid>").append(ref.svrId).append("</svrid>")
            append("<fromusr>").append(xmlEscape(ref.fromUsr)).append("</fromusr>")
            append("<chatusr>").append(xmlEscape(ref.chatUsr)).append("</chatusr>")
            append("<displayname>").append(xmlEscape(ref.displayName)).append("</displayname>")
            append("<content>").append(xmlEscape(quoted)).append("</content>")
            append("<createtime>").append(ref.createTimeMs / 1000).append("</createtime>")
            append("</refermsg>")
            append(at)
            append("</appmsg></msg>")
        }
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&" + "quot;")

    private fun findCoreMethod(cls: Class<*>, name: String, argc: Int): Method? =
        (cls.methods.asList() + cls.declaredMethods.asList())
            .distinct()
            .firstOrNull { m ->
                m.name == name && m.parameterCount == argc &&
                    m.parameterTypes.all { it == String::class.java }
            }

    private fun candidateSummary(core: Any, argc: Int): String =
        core.javaClass.declaredMethods.filter { it.parameterCount == argc }.take(8)
            .joinToString(";") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }

    private fun probeCoreMethod(name: String, argc: Int): String {
        val core = resolveSendCore() ?: return "core=$CORE_CLASS missing enum=" + enumSummary()
        val m = findCoreMethod(core.javaClass, name, argc)
            ?: return "core ok，但没找到 $name/$argc，候选：" + candidateSummary(core, argc)
        return "core ok method=" + m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
    }

    private fun enumSummary(): String = runCatching {
        val cl = WxEnv.classLoader ?: return@runCatching "no-cl"
        val e = Class.forName(CORE_ENUM, true, cl)
        (e.enumConstants ?: emptyArray<Any>()).joinToString(",") { it.javaClass.simpleName }
    }.getOrDefault("?")

    private fun mediaCandidates(core: Any): String =
        core.javaClass.declaredMethods.filter { it.parameterCount == 9 }.take(8)
            .joinToString(";") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }

    private fun mediaFail(
        started: Long, dryRun: Boolean, talker: String, path: String,
        size: Long, message: String,
    ) = MediaResult(false, dryRun, false, talker, path, size, System.currentTimeMillis() - started, message)

    // ------------------------------------------------------------------ 反射

    private class BuildOutcome(
        val scene: Any? = null,
        val error: Throwable? = null,
        val timedOut: Boolean = false,
    )

    /** 参考实现整条发送链都跑在微信主线程；构造消息对象也放主线程，避免碰到主线程断言。 */
    private fun buildOnMain(talker: String, text: String, atWxIds: List<String>, msgType: Int = MSG_TYPE, msgClass: String = MSG_CLASS): BuildOutcome {
        val holder = arrayOfNulls<Any?>(1)
        val err = arrayOfNulls<Throwable>(1)
        val done = WxEnv.callOnMain(15_000L) {
            try {
                holder[0] = buildNetScene(talker, text, atWxIds, msgType)
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

    private fun buildNetScene(talker: String, text: String, atWxIds: List<String>, msgType: Int = MSG_TYPE, msgClass: String = MSG_CLASS): Any {
        val cl = WxEnv.classLoader ?: throw IllegalStateException("classLoader 未就绪")
        val cls = Class.forName(msgClass, true, cl)

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
                    withMap.add(c to arrayOf<Any?>(talker, text, msgType, MSG_SCENE, map, ""))
                p[4] == java.lang.Long::class.java || p[4] == Long::class.javaPrimitiveType ->
                    withLong.add(c to arrayOf<Any?>(talker, text, msgType, MSG_SCENE, 0L, ""))
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