package com.example.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 车机开机自启广播接收器（BootReceiver）
 *
 * 核心功能：
 * 监听车载系统启动完成广播（ACTION_BOOT_COMPLETED 与 LOCKED_BOOT_COMPLETED），
 * 确保在车机点火或开机时第一时间响应并初始化投屏环境，支撑车载系统应用常驻后台运行。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 判断是否为系统开机完成广播（包括支持 Direct Boot 的锁屏开机完成广播）
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            Log.i("BootReceiver", "[开机广播] 车机系统开机完成，车机投屏服务已就绪")
        }
    }
}
