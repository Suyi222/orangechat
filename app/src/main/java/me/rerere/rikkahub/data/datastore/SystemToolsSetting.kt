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
        return options
    }
}
