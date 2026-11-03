package com.whatchat.mcp.bridge

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.whatchat.mcp.WxEnv
import com.whatchat.mcp.WxLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 微信数据库网关。
 *
 * L1：反射拿微信进程内已经打开的 SQLiteDatabase（不自己解密 EnMicroMsg.db）。
 *     好处是绕开 SQLCipher key 推导与 WAL 一致性问题，代价是依赖微信内部字段名。
 *
 * 本类同时承担 M0 阶段的「坐标勘察」：probe() 会扫描微信 ClassLoader，
 * 把所有 SQLiteDatabase 类型的字段找出来并打日志，用于确定 8.0.78 的落点。
 */
object DbGateway {

    private const val TAG = "DbGateway"

    @Volatile
    private var attached: SQLiteDatabase? = null

    @Volatile
    var probeReport: String = "not started"
        private set

    private val candidates = ArrayList<String>()

    // ------------------------------------------------------------------ 对外 API

    fun db(): SQLiteDatabase? {
        attached?.let { if (it.isOpen) return it }
        attached = null
        return tryAttach()
    }

    fun isReady(): Boolean = db() != null

    fun query(sql: String, args: Array<String>? = null, limit: Int = 200): List<Map<String, String>> {
        val database = db() ?: throw IllegalStateException(
            "微信数据库对象尚未就绪（probe=$probeReport）"
        )
        val out = ArrayList<Map<String, String>>()
        val cursor = database.rawQuery(sql, args)
        try {
            val names = cursor.columnNames
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val row = LinkedHashMap<String, String>()
                for (i in names.indices) {
                    val value = try {
                        cursor.getString(i)
                    } catch (t: Throwable) {
                        null
                    }
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

    // ------------------------------------------------------------------ 探测

    /**
     * 扫描微信 ClassLoader 下的所有类，找出 SQLiteDatabase 类型的字段。
     * 结果会写进 probeReport，并缓存可用实例。
     */
    fun probe(): String {
        val started = System.currentTimeMillis()
        val classLoader = WxEnv.classLoader
        if (classLoader == null) {
            probeReport = "no classloader"
            return probeReport
        }

        val classNames = runCatching { collectClassNames(classLoader) }.getOrElse { emptyList() }
        WxLog.i("probe: scanned dex entries = ${classNames.size}")

        var scanned = 0
        var dbFieldHits = 0

        for (name in classNames) {
            if (!name.startsWith("com.tencent.mm.")) continue
            if (scanned > MAX_SCAN) break
            scanned++
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            val fields = runCatching { clazz.declaredFields }.getOrNull() ?: continue
            for (field in fields) {
                val type = field.type ?: continue
                if (!SQLiteDatabase::class.java.isAssignableFrom(type)) continue
                dbFieldHits++
                val static = Modifier.isStatic(field.modifiers)
                val label = "${clazz.name}#${field.name}${if (static) " (static)" else ""}"
                candidates.add(label)
                WxLog.i("probe hit: $label")

                // 静态字段可以直接取；实例字段需要宿主单例
                if (attached == null && static) {
                    val value = runCatching { field.isAccessible = true; field.get(null) }.getOrNull()
                    if (value is SQLiteDatabase && value.isOpen) {
                        attached = value
                        WxLog.i("probe: attached via static field $label")
                    }
                }
            }
        }

        // 静态字段没命中时，尝试常见单例模式
        if (attached == null) {
            trySingletonPatterns(classLoader)
        }

        val cost = System.currentTimeMillis() - started
        probeReport = buildString {
            append("scanned=$scanned fields=$dbFieldHits costMs=$cost ")
            append("attached=")
            append(attached?.path ?: "none")
            if (candidates.isNotEmpty()) {
                append(" candidates=")
                append(candidates.take(12).joinToString(","))
            }
        }
        WxLog.i("probe done: $probeReport")
        return probeReport
    }

    private fun trySingletonPatterns(classLoader: ClassLoader) {
        for (label in candidates.toList()) {
            val parts = label.substringBefore(" (static)").split("#")
            if (parts.size != 2) continue
            val clazz = runCatching { Class.forName(parts[0], true, classLoader) }.getOrNull() ?: continue
            val field = runCatching { clazz.getDeclaredField(parts[1]) }.getOrNull() ?: continue
            if (Modifier.isStatic(field.modifiers)) continue

            runCatching { field.isAccessible = true }
            val host = findHostInstance(clazz) ?: continue
            val value = runCatching { field.get(host) }.getOrNull()
            if (value is SQLiteDatabase && value.isOpen) {
                attached = value
                WxLog.i("probe: attached via singleton ${clazz.name}.${field.name}")
                return
            }
        }
    }

    private fun findHostInstance(clazz: Class<*>): Any? {
        // 常见的单例字段名
        val names = arrayOf("INSTANCE", "instance", "ʻ", "ʽ", "ˊ", "ـ")
        for (name in names) {
            val field: Field? = runCatching { clazz.getDeclaredField(name) }.getOrNull()
            if (field == null) continue
            if (!Modifier.isStatic(field.modifiers)) continue
            val value = runCatching { field.isAccessible = true; field.get(null) }.getOrNull()
            if (value != null && clazz.isInstance(value)) return value
        }
        // Kotlin object 的 INSTANCE
        return runCatching {
            val f = clazz.getDeclaredField("INSTANCE")
            f.isAccessible = true
            f.get(null)
        }.getOrNull()
    }

    private fun tryAttach(): SQLiteDatabase? {
        // 已探测过就直接用候选重试一次（微信可能重建了 db）
        return runCatching {
            val cl = WxEnv.classLoader ?: return null
            for (label in candidates) {
                val clean = label.removeSuffix(" (static)")
                val parts = clean.split("#")
                if (parts.size != 2) continue
                val clazz = Class.forName(parts[0], false, cl)
                val field = clazz.getDeclaredField(parts[1])
                field.isAccessible = true
                val value: Any? = if (Modifier.isStatic(field.modifiers)) {
                    field.get(null)
                } else {
                    val host = findHostInstance(clazz) ?: continue
                    field.get(host)
                }
                if (value is SQLiteDatabase && value.isOpen) return value
            }
            null
        }.getOrNull()
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