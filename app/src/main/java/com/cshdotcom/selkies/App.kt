package com.cshdotcom.selkies

import android.app.Application

/** 应用入口：目前无全局状态初始化，保留扩展位（前台服务/通知等） */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
