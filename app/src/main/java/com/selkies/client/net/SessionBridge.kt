package com.selkies.client.net

/**
 * 会话桥 —— AppsActivity 等叠加页与 SessionActivity 内连接引擎之间的轻量通道。
 *
 * SessionActivity 装配引擎时注入 sendCommand；服务端 apps_installed 广播更新
 * installedApps 并通知监听者。叠加页（应用启动器）读取状态、发命令、订阅变更。
 *
 * 生命周期：SessionActivity.onDestroy 时清空引用，避免泄漏。
 */
object SessionBridge {
    /** 发送远程命令（cmd,<command>）；会话不存在时为 null */
    @Volatile var sendCommand: ((String) -> Unit)? = null

    /** 服务端报告的已安装应用集合（来自 server_settings.apps_installed / system apps_installed） */
    @Volatile var installedApps: Set<String> = emptySet()

    /** 已安装集合变化通知（AppsActivity 在 onResume 注册 / onPause 注销） */
    @Volatile var onInstalledChanged: (() -> Unit)? = null
}
