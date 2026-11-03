package com.whatchat.mcp

import android.util.Log

/**
 * 统一日志出口。tag 固定，方便 logcat 过滤：
 *   adb logcat -s WhatChatMCP
 */
object WxLog {
    const val TAG = "WhatChatMCP"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String) = e(msg, null)

    fun e(msg: String, t: Throwable?) {
        if (t == null) {
            Log.e(TAG, msg)
        } else {
            Log.e(TAG, msg, t)
        }
    }
}