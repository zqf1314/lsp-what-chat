package com.whatchat.mcp.bridge

import android.database.Cursor
import com.whatchat.mcp.WxEnv
import com.whatchat.mcp.WxLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 微信数据库网关。
 *
 * 拿句柄的两条路：
 *  1. **SqliteHook**（首选）：hook WCDB/AOSP 的打开动作，直接拿到 path → instance。
 *     微信用的是 `com.tencent.wcdb.database.SQLiteDatabase`，反射扫 `android.database.sqlite.SQLiteDatabase`
 *     字段一个都扫不到（实测 fields=0），所以这是主路径。
 *  2. 反射扫字段（兜底兼诊断）：按**类型名后缀** `SQLiteDatabase` 匹配，同时覆盖 WCDB 与 AOSP。
 *
 * 拿到实例后只做 rawQuery，不自己解密 EnMicroMsg.db —— 绕开 SQLCipher key 推导与 WAL 一致性问题。
 */
object DbGateway {

    private const val MAIN_DB = "EnMicroMsg.db"
    private const val ALT_DB = "rIndexMicroMsg.db"

    @Volatile
    private var handle: Any? = null

    @Volatile
    private var handlePath: String? = null

    @Volatile
    private var lastProbeNote: String = "not started"

    private val fieldCandidates = ArrayList<String>()

    val probeReport: String
        get() = buildString {
            append(lastProbeNote)
            append(" hookDbs=")
            append(SqliteHook.paths().size)
            val paths = SqliteHook.paths()
            if (paths.isNotEmpty()) {
                append("[")
                append(paths.take(4).joinToString(",") { it.substringAfterLast('/') })
                append("]")
            }
            append(" attached=")
            append(handlePath ?: "none")
            if (fieldCandidates.isNotEmpty()) {
                append(" candidates=")
                append(fieldCandidates.take(8).joinToString(","))
            }
        }

    // ------------------------------------------------------------------ 对外 API

    fun db(): Any? {
        handle?.let { if (SqliteHook.isOpen(it)) return it }
        handle = null
        handlePath = null

        // 只认微信主库与索引库：Scheduler.db / androidx.work.workdb 这类库虽然也开了，
        // 但里面没有 message / rconversation 表，拿它当「已就绪」会误导所有工具。
        for (suffix in listOf(MAIN_DB, ALT_DB)) {
            SqliteHook.find(suffix)?.let { return cache(it, suffix) }
        }
        return scanFieldsForHandle()
    }

    fun isReady(): Boolean = db() != null

    fun query(sql: String, args: Array<String>? = null, limit: Int = 200): List<Map<String, String>> {
        val instance = db() ?: throw IllegalStateException("微信数据库对象尚未就绪（$probeReport）")
        val cursor = rawQuery(instance, sql, args)
            ?: throw IllegalStateException("rawQuery 调用失败：${instance.javaClass.name}")

        val out = ArrayList<Map<String, String>>()
        try {
            val names = cursor.columnNames
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val row = LinkedHashMap<String, String>()
                for (i in names.indices) {
                    val value = runCatching { cursor.getString(i) }.getOrNull()
                    row[names[i]] = value ?: ""
                }
                out.add(row)
                count++
            }
        } finally {
            runCatching { cursor.close() }
        }
        return out
    }

    // ------------------------------------------------------------------ 反射查询

    private fun rawQuery(instance: Any, sql: String, args: Array<String>?): Cursor? {
        val cls = instance.javaClass

        // WCDB / AOSP 都有的签名：rawQuery(String, String[])
        runCatching { cls.getMethod("rawQuery", String::class.java, Array<String>::class.java) }
            .getOrNull()?.let {
                return it.invoke(instance, sql, args) as? Cursor
            }

        // 某些版本是 Object[]
        runCatching { cls.getMethod("rawQuery", String::class.java, Array<Any>::class.java) }
            .getOrNull()?.let {
                @Suppress("UNCHECKED_CAST")
                return it.invoke(instance, sql, args as Array<Any>?) as? Cursor
            }

        // 最后兜底：遍历所有 rawQuery 重载，挑参数个数为 2 的
        cls.methods.firstOrNull {
            it.name == "rawQuery" && it.parameterTypes.size == 2
        }?.let { m ->
            val second = m.parameterTypes[1]
            val arg: Any? = when {
                second == Array<String>::class.java -> args
                second == Array<Any>::class.java -> args as Array<Any>?
                else -> null
            }
            return runCatching { m.invoke(instance, sql, arg) as? Cursor }.getOrNull()
        }

        WxLog.e("rawQuery signature not found on ${cls.name}: ${cls.methods.count { it.name == "rawQuery" }} overloads")
        return null
    }

    private fun cache(instance: Any, path: String): Any {
        handle = instance
        handlePath = path
        return instance
    }

    // ------------------------------------------------------------------ 探测/诊断

    /** 扫描 classpath，按类型名后缀找 `*SQLiteDatabase` 字段，仅用于诊断与兜底。 */
    fun probe(): String {
        val started = System.currentTimeMillis()
        val classLoader = WxEnv.classLoader
        if (classLoader == null) {
            lastProbeNote = "no classloader"
            return probeReport
        }

        // 1) hook 已经抓到就直接收工
        db()?.let {
            lastProbeNote = "probe: via hook costMs=${System.currentTimeMillis() - started}"
            WxLog.i("probe: attached from hook path=$handlePath")
            return probeReport
        }

        val classNames = runCatching { collectClassNames(classLoader) }.getOrElse { emptyList() }
        WxLog.i("probe: classpath entries = ${classNames.size}")

        var scanned = 0
        var hits = 0

        for (name in classNames) {
            if (!name.startsWith("com.tencent.mm.")) continue
            if (scanned > MAX_SCAN) break
            scanned++
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val fields = runCatching { clazz.declaredFields }.getOrNull() ?: continue
            for (field in fields) {
                val typeName = field.type?.name ?: continue
                if (!typeName.endsWith("SQLiteDatabase")) continue
                hits++
                val static = Modifier.isStatic(field.modifiers)
                val label = "${clazz.name}#${field.name}${if (static) " (static)" else ""}"
                if (!fieldCandidates.contains(label)) fieldCandidates.add(label)
                WxLog.i("probe hit: $label : ${field.type.name}")

                if (static && handle == null) {
                    val value = runCatching { field.isAccessible = true; field.get(null) }.getOrNull()
                    if (value != null && SqliteHook.isOpen(value)) {
                        cache(value, label)
                        WxLog.i("probe: attached via static field $label")
                    }
                }
            }
        }

        if (handle == null) trySingletonPatterns(classLoader)

        val cost = System.currentTimeMillis() - started
        lastProbeNote = "probe: scanned=$scanned dbFields=$hits costMs=$cost"
        WxLog.i("probe done: $probeReport")
        return probeReport
    }

    private fun scanFieldsForHandle(): Any? {
        val classLoader = WxEnv.classLoader ?: return null
        for (label in fieldCandidates) {
            val clean = label.removeSuffix(" (static)")
            val parts = clean.split("#")
            if (parts.size != 2) continue
            val clazz = runCatching { Class.forName(parts[0], false, classLoader) }.getOrNull() ?: continue
            val field: Field = runCatching { clazz.getDeclaredField(parts[1]) }.getOrNull() ?: continue
            val value = runCatching {
                field.isAccessible = true
                if (Modifier.isStatic(field.modifiers)) field.get(null)
                else field.get(findHostInstance(clazz) ?: return null)
            }.getOrNull() ?: continue
            if (SqliteHook.isOpen(value)) return cache(value, label)
        }
        return null
    }

    private fun trySingletonPatterns(classLoader: ClassLoader) {
        for (label in fieldCandidates.toList()) {
            val clean = label.removeSuffix(" (static)")
            val parts = clean.split("#")
            if (parts.size != 2) continue
            val clazz = runCatching { Class.forName(parts[0], true, classLoader) }.getOrNull() ?: continue
            val field = runCatching { clazz.getDeclaredField(parts[1]) }.getOrNull() ?: continue
            if (Modifier.isStatic(field.modifiers)) continue

            runCatching { field.isAccessible = true }
            val host = findHostInstance(clazz) ?: continue
            val value = runCatching { field.get(host) }.getOrNull() ?: continue
            if (SqliteHook.isOpen(value)) {
                cache(value, label)
                WxLog.i("probe: attached via singleton ${clazz.name}.${field.name}")
                return
            }
        }
    }

    private fun findHostInstance(clazz: Class<*>): Any? {
        val names = arrayOf("INSTANCE", "instance", "ʻ", "ʽ", "ˊ", "ـ")
        for (name in names) {
            val field: Field = runCatching { clazz.getDeclaredField(name) }.getOrNull() ?: continue
            if (!Modifier.isStatic(field.modifiers)) continue
            val value = runCatching { field.isAccessible = true; field.get(null) }.getOrNull() ?: continue
            if (clazz.isInstance(value)) return value
        }
        return null
    }

    private fun collectClassNames(classLoader: ClassLoader): List<String> {
        val names = ArrayList<String>()
        val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
            .getDeclaredField("pathList")
        pathListField.isAccessible = true
        val pathList = pathListField.get(classLoader) ?: return names
        val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
        dexElementsField.isAccessible = true
        val elements = dexElementsField.get(pathList) as? Array<*> ?: return names

        for (element in elements) {
            if (element == null) continue
            val dexFile = runCatching {
                val f = element.javaClass.getDeclaredField("dexFile")
                f.isAccessible = true
                f.get(element)
            }.getOrNull() ?: continue
            val enumeration = runCatching {
                val m = dexFile.javaClass.getMethod("entries")
                m.isAccessible = true
                m.invoke(dexFile) as? java.util.Enumeration<*>
            }.getOrNull()
            while (enumeration != null && enumeration.hasMoreElements()) {
                val n = enumeration.nextElement() as? String ?: continue
                names.add(n)
            }
        }
        return names
    }

    private const val MAX_SCAN = 30000
}