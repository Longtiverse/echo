package com.echo.app

import android.app.Application
import android.util.Log

/**
 * Phase 1: 基础工程构建与权限管理
 * Application类 - 应用入口点
 */
class EchoApplication : Application() {

    companion object {
        const val TAG = "EchoApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Echo Application initialized")
    }
}
