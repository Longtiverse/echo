package com.echo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.echo.app.databinding.ActivityMainBinding
import com.echo.app.permission.PermissionManager

/**
 * Phase 1: 基础工程构建与权限管理
 * 主Activity - 提供启动/停止监听的基础UI
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private var hasPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)
        
        checkPermissions()
        initUI()
    }

    private fun checkPermissions() {
        hasPermissions = permissionManager.hasAllPermissions()
        
        if (!hasPermissions) {
            permissionManager.requestRuntimePermissions()
        } else {
            Log.d(TAG, "All permissions granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        permissionManager.handlePermissionResult(
            requestCode = requestCode,
            permissions = permissions as Array<String>,
            grantResults = grantResults,
            onAllGranted = {
                hasPermissions = true
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show()
                updateStatus("就绪")
            },
            onDenied = { deniedPermissions ->
                hasPermissions = false
                val message = "缺少权限: ${deniedPermissions.joinToString(", ")}"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updateStatus("缺少权限")
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = false
            }
        )
    }

    private fun initUI() {
        binding.btnStart.setOnClickListener {
            if (hasPermissions) {
                startMonitoring()
            } else {
                Toast.makeText(this, "请先授予权限", Toast.LENGTH_SHORT).show()
                checkPermissions()
            }
        }

        binding.btnStop.setOnClickListener {
            stopMonitoring()
        }

        updateStatus(if (hasPermissions) "就绪" else "等待权限")
    }

    private fun startMonitoring() {
        // TODO: Phase 4 将启动前台服务
        Log.d(TAG, "Start monitoring requested")
        updateStatus("运行中")
        Toast.makeText(this, "开始监听 (Phase 4实现服务)", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        // TODO: Phase 4 将停止前台服务
        Log.d(TAG, "Stop monitoring requested")
        updateStatus("已停止")
        Toast.makeText(this, "停止监听 (Phase 4实现服务)", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(status: String) {
        binding.textStatus.text = "状态: $status"
    }
}
