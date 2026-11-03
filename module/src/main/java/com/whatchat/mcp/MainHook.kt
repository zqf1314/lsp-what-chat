package com.whatchat.mcp

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口（assets/xposed_init 指向本类）。
 *
 * 只做一件事：在微信 Application.attachBaseContext 之后启动 MCP Server。
 * 不在这里做任何业务，避免拖慢微信冷启动。
 */
class MainHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        WxLog.i("initZygote modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WX_PACKAGE) return
        WxLog.i("hit target package=${lpparam.packageName}")

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = param.thisObject as Application
                            Bootstrap.start(app, lpparam.classLoader)
                        } catch (t: Throwable) {
                            WxLog.e("bootstrap failed", t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            WxLog.e("hook Application.attachBaseContext failed", t)
        }
    }

    companion object {
        const val WX_PACKAGE = "com.tencent.mm"
    }
}