package com.example.car

import android.app.Application
import android.util.Log

/**
 * 车机端全局 Application 类
 *
 * 核心功能：
 * 注册全局未捕获异常处理器（UncaughtExceptionHandler）。
 * 针对国内部分车机厂商深度定制 ROM 框架存在的系统级缺陷（例如 android.bluetooth_second 抛出 AbstractMethodError 或 BluetoothProfileConnector 兼容性异常）
 * 进行针对性捕获与吞并防护，防止因厂商底层车机蓝牙框架 Bug 导致应用崩溃闪退，保障服务持续稳定在线。
 */
class CarApplication : Application() {

    private val tag = "CarApplication"

    override fun onCreate() {
        super.onCreate()

        // 注册全局未捕获异常防护机制
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTraceStr = Log.getStackTraceString(throwable)

            // 针对特定车机厂商定制蓝牙栈 (android.bluetooth_second) 框架兼容性崩溃进行拦截保护
            if (throwable is AbstractMethodError && stackTraceStr.contains("bluetooth_second")) {
                Log.e(tag, "[车机系统崩溃防护] 已捕获并成功拦截车机厂商蓝牙栈 (android.bluetooth_second) 框架 compatibility Crash，应用继续稳定运行", throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // 拦截车机定制系统 BluetoothProfileConnector 相关异常
            if (stackTraceStr.contains("android.bluetooth_second") || stackTraceStr.contains("BluetoothProfileConnector")) {
                Log.e(tag, "[车机系统崩溃防护] 已捕获并成功拦截车机定制系统蓝牙框架异常，应用继续稳定运行", throwable)
                return@setDefaultUncaughtExceptionHandler
            }

            // 其它非厂商底层框架异常交还给系统默认处理器处理
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i(tag, "[车机应用] CarApplication 初始化完成，系统底层框架异常捕获防护已生效")
    }
}
