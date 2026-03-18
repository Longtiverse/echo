package com.echo

import android.app.Application
import android.util.Log

/**
 * Application类 - 应用入口
 * 初始化全局配置和音频引擎
 */
class EchoApplication : Application() {

    companion object {
        const val TAG = "EchoMonitor"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "EchoMonitor Application started")
        
        // 初始化全局配置
        initializeApp()
    }

    private fun initializeApp() {
        // 应用初始化逻辑
        // 可以在这里初始化音频引擎等
    }
}
