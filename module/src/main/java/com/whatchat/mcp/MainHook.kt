package com.whatchat.mcp

import android.app.Application
import android.app.Instrumentation
import android.content.ContextWrapper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口（assets/xposed_init 指向本类）。
 *
 * 只做一件事：在微信 Application 起来后启动 MCP Server，不做业务，避免拖慢冷启动。
 *
 * 坑（实测）：Android 16 上 `Application#attachBaseContext(Context)` 已不再由 Application
 * 自己声明（下沉到 ContextWrapper），用 findAndHookMethod 精确匹配会抛
 * `NoSuchMethodError: android.app.Application#attachBaseContext[class android.content.Context]#exact`，
 * 结果整条 hook 链断掉、服务永远起不来。所以这里改成「按方法名 hook + 多级兜底」：
 *   1. Application.attachBaseContext（按名匹配所有重载）
 *   2. 一个都没挂上 → ContextWrapper.attachBaseContext（过滤 thisObject is Application）
 *   3. 无论前面如何，再挂 Instrumentation.callApplicationOnCreate 作为必达兜底
 * Bootstrap.start 是幂等的，多路触发无副作用。
 */
class MainHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        WxLog.i("initZygote modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WX_PACKAGE) return

        val proc = lpparam.processName ?: lpparam.packageName
        // 只在主进程起服务：多进程同时 bind 18765 会互相抢端口，其余进程也没必要跑
        if (proc != WX_PACKAGE) {
            WxLog.i("skip sub process=$proc")
            return
        }
        WxLog.i("hit target package=${lpparam.packageName} proc=$proc sdk=${android.os.Build.VERSION.SDK_INT}")

        val cl = lpparam.classLoader
        var armed = 0

        // 关键时机：微信在自己 Application.attachBaseContext 里就会打开 EnMicroMsg.db，
        // 必须在这之前装好 SQLite hook，所以放在 handleLoadPackage 而不是 Bootstrap。
        runCatching { com.whatchat.mcp.bridge.SqliteHook.install(cl) }
            .onFailure { WxLog.e("early sqlite hook failed", it) }

        armed += hookAttachBase(Application::class.java, "Application", cl)
        if (armed == 0) {
            WxLog.w("Application.attachBaseContext not hookable, fallback to ContextWrapper")
            armed += hookAttachBase(ContextWrapper::class.java, "ContextWrapper", cl)
        }
        armed += hookCallApplicationOnCreate(cl)

        if (armed == 0) {
            WxLog.e("no hook could be installed, MCP server will not start")
        } else {
            WxLog.i("hooks armed=$armed")
        }
    }

    /** 按方法名 hook 所有重载，回调里只处理 Application 实例。返回挂上的方法数。 */
    private fun hookAttachBase(clazz: Class<*>, tag: String, cl: ClassLoader): Int {
        val hooks = runCatching {
            XposedBridge.hookAllMethods(clazz, "attachBaseContext", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val self = param.thisObject
                    if (self !is Application) return
                    runCatching { Bootstrap.start(self, cl) }
                        .onFailure { WxLog.e("bootstrap($tag) failed", it) }
                }
            })
        }.onFailure { WxLog.e("hookAllMethods $tag#attachBaseContext failed", it) }.getOrNull()

        val n = hooks?.size ?: 0
        WxLog.i("hook $tag#attachBaseContext -> $n")
        return n
    }

    /** Instrumentation.callApplicationOnCreate(Application) 从 API 1 起就在，作为必达兜底。 */
    private fun hookCallApplicationOnCreate(cl: ClassLoader): Int {
        val hooks = runCatching {
            XposedBridge.hookAllMethods(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.args.getOrNull(0) as? Application ?: return
                        runCatching { Bootstrap.start(app, cl) }
                            .onFailure { WxLog.e("bootstrap(Instrumentation) failed", it) }
                    }
                }
            )
        }.onFailure { WxLog.e("hook Instrumentation failed", it) }.getOrNull()

        val n = hooks?.size ?: 0
        WxLog.i("hook Instrumentation#callApplicationOnCreate -> $n")
        return n
    }

    companion object {
        const val WX_PACKAGE = "com.tencent.mm"
    }
}