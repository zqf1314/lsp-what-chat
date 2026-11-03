package com.whatchat.mcp.bridge

import com.whatchat.mcp.WxLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据库句柄捕获器。
 *
 * 微信不用 AOSP 的 `android.database.sqlite.SQLiteDatabase`，而是自带的 **WCDB**
 * （`com.tencent.wcdb.database.SQLiteDatabase`）。所以 0.2.x 里「扫 `SQLiteDatabase` 类型字段」
 * 一个都扫不到（实测 fields=0）。
 *
 * 这里改用「hook 打开动作」：在构造器与 `openDatabase/openOrCreateDatabase` 之后拿实例与路径，
 * 存进 path → instance 映射。相比反射扫字段，不依赖任何混淆后的字段名/单例名。
 *
 * 只读用途：拿到实例后只做 rawQuery。
 */
object SqliteHook {

    private const val WCDB = "com.tencent.wcdb.database.SQLiteDatabase"
    private const val AOSP = "android.database.sqlite.SQLiteDatabase"

    private val opened = ConcurrentHashMap<String, Any>()

    @Volatile
    var installed: Boolean = false
        private set

    fun install(cl: ClassLoader) {
        if (installed) return
        var hooked = 0
        hooked += hookClass(WCDB, cl)
        hooked += hookClass(AOSP, cl)
        installed = hooked > 0
        WxLog.i("SqliteHook installed=$installed hooks=$hooked")
    }

    private fun hookClass(name: String, cl: ClassLoader): Int {
        val cls = runCatching { Class.forName(name, false, cl) }.getOrNull()
        if (cls == null) {
            WxLog.i("SqliteHook $name not present")
            return 0
        }

        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 构造器用 thisObject，静态 open 方法用返回值
                val instance = param.thisObject ?: param.result ?: return
                val path = extractPath(param.args) ?: return
                if (opened.put(path, instance) == null) {
                    WxLog.i("db opened: $path (${instance.javaClass.name})")
                }
            }
        }

        var n = runCatching { XposedBridge.hookAllConstructors(cls, callback) }
            .onFailure { WxLog.e("hook $name ctors failed", it) }
            .getOrNull()?.size ?: 0

        for (method in arrayOf("openDatabase", "openOrCreateDatabase", "openDatabaseInMemory")) {
            n += runCatching { XposedBridge.hookAllMethods(cls, method, callback) }
                .onFailure { WxLog.e("hook $name#$method failed", it) }
                .getOrNull()?.size ?: 0
        }

        WxLog.i("SqliteHook $name hooks=$n")
        return n
    }

    private fun extractPath(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            when (arg) {
                is String -> if (arg.contains('/') || arg.endsWith(".db")) return arg
                is File -> return arg.absolutePath
            }
        }
        return null
    }

    /** 按文件名后缀找已打开的库，例如 "EnMicroMsg.db"。 */
    fun find(suffix: String): Any? = opened.entries
        .firstOrNull { it.key.endsWith(suffix) }?.value

    /** 任意一个已打开的库（优先返回 EnMicroMsg.db）。 */
    fun findAny(): Pair<String, Any>? {
        opened.entries.firstOrNull { it.key.endsWith("EnMicroMsg.db") }?.let {
            return it.key to it.value
        }
        return opened.entries.firstOrNull()?.let { it.key to it.value }
    }

    fun paths(): List<String> = opened.keys.toList()

    fun clear() = opened.clear()

    /** 反射调用 isOpen（WCDB 与 AOSP 都有此方法）。 */
    fun isOpen(instance: Any): Boolean = runCatching {
        val m = instance.javaClass.getMethod("isOpen")
        m.invoke(instance) as? Boolean ?: true
    }.getOrDefault(true)
}