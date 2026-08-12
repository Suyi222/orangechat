/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class SystemToolsSetting(
    val amapApiKey: String = "",
    val notificationAccess: Boolean = false,
    val cameraAccess: Boolean = false,
    val locationAccess: Boolean = false,
    val appUsageAccess: Boolean = false,
    val ocrProvider: String = "local",
    val ocrApiKey: String = "",
    val ocrApiUrl: String = "",
    val ocrModel: String = "",

    val locationExploreEnabled: Boolean = false,
    val locationExploreRadius: Int = 1000,
    val notificationQueryEnabled: Boolean = false,
    val appUsageEnabled: Boolean = false,
    val cameraOcrEnabled: Boolean = false,

    val proactiveMessagingEnabled: Boolean = false,
    val proactiveMessagingMinInterval: Int = 30,
    val proactiveMessagingMaxInterval: Int = 90,

    val supabaseEnabled: Boolean = false,
    val supabaseUrl: String = "",
    val supabaseApiKey: String = "",
    val supabaseTableName: String = "device_data",
    val deviceEventTrackingEnabled: Boolean = false,

    val gadgetbridgeEnabled: Boolean = false,
    val gadgetbridgeDbPath: String = "",
    val alarmEnabled: Boolean = false,
    val timerEnabled: Boolean = false,
    val batteryEnabled: Boolean = false,
    val musicEnabled: Boolean = false,
    val smsEnabled: Boolean = false,

    val torchEnabled: Boolean = false,
    val toastEnabled: Boolean = false,
    val vibrateEnabled: Boolean = false,
    val brightnessEnabled: Boolean = false,
    val volumeEnabled: Boolean = false,
    val wifiInfoEnabled: Boolean = false,
    val telephonyInfoEnabled: Boolean = false,
    val shareEnabled: Boolean = false,
    val setWallpaperEnabled: Boolean = false,
    val wakeScreenEnabled: Boolean = false,
    val scanMediaEnabled: Boolean = false,
    val postNotificationEnabled: Boolean = false,
    val storageInfoEnabled: Boolean = false,
    val appSwitchEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
    val fingerprintEnabled: Boolean = false,

    // 🌲 工作流主动唤醒
    val proactiveTriggerEnabled: Boolean = false,
    // 📝 桌面便签
    val deskNoteEnabled: Boolean = false,
    // 🔍 历史聊天搜索
    val searchHistoryEnabled: Boolean = false,
    // 🔍 按助手限定的历史聊天搜索（search_chat_history，只搜当前助手自己的对话）
    val searchChatHistoryEnabled: Boolean = false,
    // 👻 工作流管理工具（E4 系统级闸门：控制 workflow_* 工具是否暴露给 AI，默认开）
    val workflowManagementEnabled: Boolean = true,
    // 🎙️ TTS 缓存列表（只控制 list_tts_exports，朗读能力不受影响）
    val ttsCacheEnabled: Boolean = true,
    // 🌳 树影下自动记录（决策 9 / 2.4.0 自动记录开关组）
    val autoRecordEnabled: Boolean = true,              // 总开关
    val autoRecordRecorder: String = "both",            // 记录者: system / agent / both
    val autoRecordIdleEnabled: Boolean = true,          // 闲置段末触发（默认常开）
    val autoRecordChapterEnabled: Boolean = false,      // 章节轮转触发（默认关，灵活开关）
    val autoRecordChapterN: Int = 44,                   // 每 N 轮记一条（独立于模型窗口）
    val autoRecordIdleMinutes: Int = 15,                // 闲置阈值（分钟）
    val autoRecordSummaryModel: String? = null,         // 总结模型 id，null 则用 compressModelId
    val autoRecordAnnualRing: Boolean = false,          // 深度对话额外落年轮（B3.6/C3.1，默认关）
) {
    fun getEnabledOptions(): Set<me.rerere.rikkahub.data.ai.tools.SystemToolOption> {
        val options = mutableSetOf<me.rerere.rikkahub.data.ai.tools.SystemToolOption>()
        if (locationAccess || locationExploreEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Location)
        if (notificationAccess || notificationQueryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Notifications)
        if (appUsageAccess || appUsageEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppUsage)
        if (cameraAccess || cameraOcrEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Camera)
        if (locationExploreEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.ExploreNearby)
        if (gadgetbridgeEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Gadgetbridge)
        if (alarmEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Alarm)
        if (timerEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Timer)
        if (batteryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Battery)
        if (musicEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Music)
        if (smsEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Sms)
        if (torchEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Torch)
        if (toastEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Toast)
        if (vibrateEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Vibrate)
        if (brightnessEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Brightness)
        if (volumeEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Volume)
        if (wifiInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.WifiInfo)
        if (telephonyInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.TelephonyInfo)
        if (shareEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Share)
        if (setWallpaperEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SetWallpaper)
        if (wakeScreenEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.WakeScreen)
        if (scanMediaEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.ScanMedia)
        if (postNotificationEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.PostNotification)
        if (storageInfoEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.StorageInfo)
        if (appSwitchEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppSwitch)
        if (appLockEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.AppLock)
        if (fingerprintEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.Fingerprint)
        if (proactiveTriggerEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.ProactiveTrigger)
        if (deskNoteEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.DeskNote)
        if (searchHistoryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SearchHistory)
        if (searchChatHistoryEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SearchChatHistory)
        if (ttsCacheEnabled) options.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.TtsExports)
        return options
    }
}
