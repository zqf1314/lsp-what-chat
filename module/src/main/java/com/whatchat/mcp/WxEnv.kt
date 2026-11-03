package com.whatchat.mcp

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 微信进程内的运行时环境：Application、ClassLoader、微信版本、主线程调度。
 */
object WxEnv {

    @Volatile
    var app: Application? = null
        private set

    @Volatile
    var classLoader: ClassLoader? = null
        private set

    @Volatile
    var wxVersionName: String = "unknown"
        private set

    @Volatile
    var wxVersionCode: Long = 0L
        private set

    @Volatile
    var startedAt: Long = 0L
        private set

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun init(application: Application, cl: ClassLoader) {
        app = application
        classLoader = cl
        startedAt = System.currentTimeMillis()
        runCatching {
            val pi = application.packageManager.getPackageInfo(application.packageName, 0)
            wxVersionName = pi.versionName ?: "unknown"
            wxVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        }.onFailure { WxLog.e("read wx version failed", it) }
        WxLog.i("WxEnv init: wx=$wxVersionName($wxVersionCode) sdk=${Build.VERSION.SDK_INT}")
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 把调用切到微信主线程并等待结果。微信绝大多数 Manager 只允许主线程访问。
     * 超时返回 null（调用方必须处理，不要静默当成空数据）。
     */
    fun <T> callOnMain(timeoutMs: Long = 8000L, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { block() }.onFailure { WxLog.e("callOnMain(current) failed", it) }.getOrNull()
        }
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                box[0] = block()
            } catch (t: Throwable) {
                WxLog.e("callOnMain failed", t)
            } finally {
                latch.countDown()
            }
        }
        val done = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            WxLog.w("callOnMain timeout after ${timeoutMs}ms")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return box[0] as? T
    }
}