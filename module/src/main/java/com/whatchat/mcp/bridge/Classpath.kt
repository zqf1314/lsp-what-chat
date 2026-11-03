package com.whatchat.mcp.bridge

/** 读取 ClassLoader 里所有 dex 的类名（不加载类，只是拿字符串）。 */
object Classpath {

    fun names(classLoader: ClassLoader): List<String> {
        val names = ArrayList<String>()
        val pathList = runCatching {
            val f = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList")
            f.isAccessible = true
            f.get(classLoader)
        }.getOrNull() ?: return names

        val elements = runCatching {
            val f = pathList.javaClass.getDeclaredField("dexElements")
            f.isAccessible = true
            f.get(pathList) as? Array<*>
        }.getOrNull() ?: return names

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
}