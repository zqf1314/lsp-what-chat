package com.whatchat.mcp.bridge

import android.database.Cursor
import com.whatchat.mcp.WxEnv
import com.whatchat.mcp.WxLog
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信 8.0.80 取库：照抄已验证可用的 `com.wechat.wechatai` 1.1.8 反射链。
 *
 * 坐标来源：该 APK 的 `Lz2/l54;` 构造器常量表（按微信版本写死的混淆名映射），
 * `Lz2/x16;` 是它的 DB 网关。对应关系：
 *
 *  - 方式 B（x16->ʽ 的 `cond_42` 分支，不需要 uin）：
 *        cls = Class.forName("w41.i9")
 *        holder = cls.b()            // 静态、无参
 *        db = holder.t()             // 实例、无参
 *  - 方式 A（x16->ᵔ，需要 uin）：
 *        core = Class.forName("ai5.d0").newInstance()
 *        core.R(uin)                 // 微信自己按 uin 打开 EnMicroMsg.db
 *  - 查询：`db.f(String sql, String[] selectionArgs, int 0)` 返回 Cursor
 *    （参考实现用 XposedHelpers.findMethodBestMatch 匹配，第三个参数是 Integer 0）
 *
 * 只做只读查询：不拼路径、不推导 SQLCipher key、不自己开库。
 */
object WxDb {

    private const val HOLDER_CLASS = "w41.i9"
    private const val HOLDER_GETTER = "b"
    private const val DB_GETTER = "t"
    private const val CORE_CLASS = "ai5.d0"
    private const val CORE_INIT = "R"
    private const val QUERY_NAME = "f"

    @Volatile
    private var handle: Any? = null

    @Volatile
    private var queryMethod: Method? = null

    @Volatile
    var diag: String = "not started"
        private set

    fun reset() {
        handle = null
        queryMethod = null
        diag = "not started"
    }

    fun db(): Any? {
        handle?.let { return it }
        // 微信 kernel/storage 单例通常要求主线程访问
        val resolved = WxEnv.callOnMain(8000L) { resolveNow() }
        if (resolved == null && handle == null && diag == "not started") diag = "main-thread timeout"
        return resolved
    }

    private fun resolveNow(): Any? {
        handle?.let { return it }
        val cl = WxEnv.classLoader ?: run {
            diag = "no classloader"
            return null
        }

        val notes = StringBuilder()

        // ---------------------------------------------------------- 方式 B
        val holder = runCatching {
            val cls = Class.forName(HOLDER_CLASS, false, cl)
            notes.append("B:").append(cls.simpleName)
            val h = XposedHelpers.callStaticMethod(cls, HOLDER_GETTER)
            notes.append('#').append(HOLDER_GETTER).append("->")
                .append(h?.javaClass?.name?.substringAfterLast('.') ?: "null")
            h
        }.onFailure {
            notes.append("B:fail(").append(it.javaClass.simpleName).append(": ")
                .append(it.message?.take(80)).append(')')
        }.getOrNull()

        if (holder != null) {
            runCatching {
                val db = XposedHelpers.callMethod(holder, DB_GETTER)
                notes.append(" t->").append(db?.javaClass?.name ?: "null")
                if (db != null) {
                    handle = db
                    queryMethod = resolveQuery(db)
                    notes.append(" query=").append(queryMethod?.let { describe(it) } ?: "none")
                }
            }.onFailure {
                notes.append(" t:fail(").append(it.message?.take(80)).append(')')
            }
        }

        // ---------------------------------------------------------- 方式 A（无 uin，仅在 B 失败时尝试）
        if (handle == null) {
            runCatching {
                val cls = Class.forName(CORE_CLASS, false, cl)
                val core = XposedHelpers.newInstance(cls)
                notes.append(" A:new=").append(core?.javaClass?.name ?: "null")
                notes.append(" A:no-uin(skip ").append(CORE_INIT).append(')')
                core
            }.onFailure {
                notes.append(" A:fail(").append(it.message?.take(80)).append(')')
            }
        }

        diag = notes.toString()
        WxLog.i("WxDb resolve: $diag")
        handle?.let { d ->
            WxLog.i("WxDb method candidates: " + methodSummary(d))
        }
        return handle
    }

    fun isReady(): Boolean = db() != null

    /** 只读查询；返回 null 表示当前不可用（调用方负责报错，不要静默返回空列表）。 */
    fun cursor(sql: String, args: Array<String>?): Cursor? {
        val instance = db() ?: return null
        val method = queryMethod ?: resolveQuery(instance)?.also { queryMethod = it }
        if (method == null) {
            WxLog.e("WxDb: no query method on ${instance.javaClass.name}")
            return null
        }

        val params = arrayOfNulls<Any?>(method.parameterCount)
        for (i in 0 until method.parameterCount) {
            val type = method.parameterTypes[i]
            params[i] = when {
                i == 0 && type == String::class.java -> sql
                i == 1 && type == Array<String>::class.java -> args ?: emptyArray<String>()
                type == Int::class.javaPrimitiveType || type == Integer::class.java -> 0
                type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java -> false
                else -> null
            }
        }

        return runCatching {
            method.isAccessible = true
            method.invoke(instance, *params) as? Cursor
        }.onFailure {
            WxLog.e("WxDb query failed via ${describe(method)}", it)
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 方法匹配

    private fun resolveQuery(instance: Any): Method? {
        val cls = instance.javaClass
        val all = runCatching { cls.methods.toList() + cls.declaredMethods.toList() }
            .getOrDefault(emptyList())
            .filter { !Modifier.isAbstract(it.modifiers) }

        // 1) 参考实现的精确形态：f(String, String[], int)
        all.firstOrNull {
            it.name == QUERY_NAME &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Array<String>::class.java &&
                (it.parameterTypes[2] == Int::class.javaPrimitiveType || it.parameterTypes[2] == Integer::class.java)
        }?.let { return it }

        // 2) f(String, String[])
        all.firstOrNull {
            it.name == QUERY_NAME &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Array<String>::class.java
        }?.let { return it }

        // 3) 标准 WCDB/AOSP 签名
        all.firstOrNull {
            it.name == "rawQuery" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Array<String>::class.java
        }?.let { return it }

        // 4) 任意 name==f 且首参为 String
        all.firstOrNull {
            it.name == QUERY_NAME && it.parameterCount >= 2 && it.parameterTypes[0] == String::class.java
        }?.let { return it }

        WxLog.e(
            "WxDb: resolveQuery none on ${cls.name}, candidates=" +
                all.filter { it.name == QUERY_NAME || it.name == "rawQuery" }
                    .joinToString(",") { describe(it) }
        )
        return null
    }

    private fun describe(m: Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"

    private fun methodSummary(instance: Any): String = runCatching {
        val cls = instance.javaClass
        val methods = (cls.methods.toList() + cls.declaredMethods.toList()).distinct()
        val named = methods.map { describe(it) }.distinct()
        "n=${named.size} " + named.take(24).joinToString(" | ")
    }.getOrDefault("n/a")
}