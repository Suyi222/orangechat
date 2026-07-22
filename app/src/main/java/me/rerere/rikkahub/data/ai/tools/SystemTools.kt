/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.system.createTriggerProactiveMessageTool
import me.rerere.rikkahub.data.ai.tools.system.createDeskNoteTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.service.AmapService
import me.rerere.rikkahub.data.service.DeviceLocationFetcher
import me.rerere.rikkahub.data.service.RikkaNotificationListenerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
sealed class SystemToolOption {
    @Serializable @SerialName("location") data object Location : SystemToolOption()
    @Serializable @SerialName("notifications") data object Notifications : SystemToolOption()
    @Serializable @SerialName("app_usage") data object AppUsage : SystemToolOption()
    @Serializable @SerialName("camera") data object Camera : SystemToolOption()
    @Serializable @SerialName("explore_nearby") data object ExploreNearby : SystemToolOption()
    @Serializable @SerialName("gadgetbridge") data object Gadgetbridge : SystemToolOption()
    @Serializable @SerialName("alarm") data object Alarm : SystemToolOption()
    @Serializable @SerialName("timer") data object Timer : SystemToolOption()
    @Serializable @SerialName("battery") data object Battery : SystemToolOption()
    @Serializable @SerialName("music") data object Music : SystemToolOption()
    @Serializable @SerialName("sms") data object Sms : SystemToolOption()
    @Serializable @SerialName("supabase_query") data object SupabaseQuery : SystemToolOption()
    @Serializable @SerialName("torch") data object Torch : SystemToolOption()
    @Serializable @SerialName("toast") data object Toast : SystemToolOption()
    @Serializable @SerialName("vibrate") data object Vibrate : SystemToolOption()
    @Serializable @SerialName("brightness") data object Brightness : SystemToolOption()
    @Serializable @SerialName("volume") data object Volume : SystemToolOption()
    @Serializable @SerialName("wifi_info") data object WifiInfo : SystemToolOption()
    @Serializable @SerialName("telephony_info") data object TelephonyInfo : SystemToolOption()
    @Serializable @SerialName("share") data object Share : SystemToolOption()
    @Serializable @SerialName("set_wallpaper") data object SetWallpaper : SystemToolOption()
    @Serializable @SerialName("wake_screen") data object WakeScreen : SystemToolOption()
    @Serializable @SerialName("scan_media") data object ScanMedia : SystemToolOption()
    @Serializable @SerialName("post_notification") data object PostNotification : SystemToolOption()
    @Serializable @SerialName("storage_info") data object StorageInfo : SystemToolOption()
    @Serializable @SerialName("app_switch") data object AppSwitch : SystemToolOption()
    @Serializable @SerialName("app_lock") data object AppLock : SystemToolOption()
    @Serializable @SerialName("fingerprint") data object Fingerprint : SystemToolOption()
    @Serializable @SerialName("proactive_trigger") data object ProactiveTrigger : SystemToolOption()
    @Serializable @SerialName("desk_note") data object DeskNote : SystemToolOption()
}

class SystemTools(private val context: Context, private val settings: Settings) {

    companion object {
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasNotificationPermission(context: Context): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true

        fun hasAppUsagePermission(context: Context): Boolean =
            (context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager)
                .checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    val locationTool: Tool by lazy {
        Tool(
            name = "get_location", description = "Get the current device location with coordinates and address.", needsApproval = true,
            parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("include_address") { put("type", "boolean"); put("description", "Include address info") } }) },
            execute = { _ ->
                if (!hasLocationPermission(context)) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Location permission not granted") }.toString()))
                try {
                    val fetched = DeviceLocationFetcher.fetch(context) ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Unable to get location") }.toString()))
                    val loc = fetched.location
                    val result = buildJsonObject {
                        put("success", true); put("latitude", loc.latitude); put("longitude", loc.longitude); put("altitude", loc.altitude)
                        put("accuracy", loc.accuracy.toDouble()); put("timestamp", loc.time); put("time", dateFormat.format(Date(loc.time))); put("is_fresh", fetched.isFresh)
                        val apiKey = settings.systemToolsSetting.amapApiKey; var resolved = false
                        if (apiKey.isNotBlank()) try {
                            val r = AmapService(apiKey).getAddressFromGps(loc.latitude, loc.longitude)
                            if (r.success) { resolved = true; put("address", r.formattedAddress ?: ""); put("province", r.province ?: ""); put("city", r.city ?: ""); put("district", r.district ?: ""); put("street", r.street ?: ""); put("neighborhood", r.neighborhood ?: ""); put("building", r.building ?: "") }
                        } catch (_: Exception) {}
                        if (!resolved) try {
                            val addrs = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!addrs.isNullOrEmpty()) { val a = addrs[0]; put("address", (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").ifBlank { a.featureName ?: "" }); put("country", a.countryName ?: ""); put("province", a.adminArea ?: ""); put("city", a.locality ?: ""); put("district", a.subLocality ?: ""); put("street", a.thoroughfare ?: "") }
                            else put("address", "Unknown")
                        } catch (e: Exception) { put("address", "Geocoder failed: ${e.message}") }
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown") }.toString())) }
            }
        )
    }

    val notificationsTool: Tool by lazy {
        Tool(
            name = "get_notifications", description = "Get today's notifications.", needsApproval = true,
            parameters = { InputSchema.Obj(properties = buildJsonObject { putJsonObject("limit") { put("type", "integer"); put("description", "Max (default 20)") } }) },
            execute = { args ->
                val limit = args.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 20
                try {
                    val list = RikkaNotificationListenerService.getTodayNotifications().take(limit)
                    if (list.isEmpty()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("count", 0) }.toString()))
                    val arr = buildJsonArray { list.forEach { n -> add(buildJsonObject { put("app_name", n.appName); put("title", n.title); put("content", n.content); put("time", dateFormat.format(Date(n.timestamp))) }) } }
                    listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("count", list.size); put("notifications", arr) }.toString()))
                } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown") }.toString())) }
            }
        )
    }

    private val supabaseQueryTool by lazy {
        Tool(
            name = "supabase_query", description = "Query Supabase tables.", needsApproval = true,
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {
                    putJsonObject("operation") { put("type", "string"); put("description", "'query_recent_messages' or 'search_messages'"); putJsonArray("enum") { add("query_recent_messages"); add("search_messages") } }
                    putJsonObject("table") { put("type", "string"); put("description", "Table name") }
                    putJsonObject("count") { put("type", "integer"); put("description", "Rows (default 10)") }
                    putJsonObject("keyword") { put("type", "string"); put("description", "Search keyword") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max results (default 10)") }
                }, required = listOf("operation"))
            },
            execute = { args ->
                val params = args.jsonObject
                val operation = params["operation"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'operation'") }.toString()))
                val mems = settings.externalMemories.filter { it.enabled }
                if (mems.isEmpty()) return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "No Supabase configured") }.toString()))
                val table = params["table"]?.jsonPrimitive?.contentOrNull ?: "chat_messages"
                val mem = mems.firstOrNull { it.tableName == table || it.summariesTableName == table } ?: mems.first()
                val baseUrl = mem.supabaseUrl.trimEnd('/'); val apiKey = mem.supabaseKey
                try {
                    val url = when (operation) {
                        "query_recent_messages" -> { val c = (params["count"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50); "$baseUrl/rest/v1/$table?select=*&order=created_at.desc&limit=$c" }
                        "search_messages" -> { val kw = params["keyword"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Missing 'keyword'") }.toString())); val l = (params["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50); "$baseUrl/rest/v1/$table?select=*&content=ilike.${java.net.URLEncoder.encode("%$kw%", "UTF-8")}&limit=$l" }
                        else -> return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "Unknown operation") }.toString()))
                    }
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { requestMethod = "GET"; setRequestProperty("apikey", apiKey); setRequestProperty("Authorization", "Bearer $apiKey"); setRequestProperty("Accept", "application/json"); connectTimeout = 15000; readTimeout = 15000 }
                    val code = conn.responseCode
                    val body = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
                    val data: JsonElement = try { Json.parseToJsonElement(body) } catch (_: Exception) { JsonPrimitive(body) }
                    listOf(UIMessagePart.Text(buildJsonObject { put("success", code in 200..299); put("data", data) }.toString()))
                } catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", e.message ?: "Unknown") }.toString())) }
            }
        )
    }

    private val appUsageTool by lazy { createAppUsageTool(context) }
    private val exploreNearbyTool by lazy { createExploreNearbyTool(context, settings) }
    private val cameraTool by lazy { createCameraTool(context) }
    private val gadgetbridgeTool by lazy { createGadgetbridgeTool(settings.systemToolsSetting.gadgetbridgeDbPath) }
    private val alarmTool by lazy { createAlarmTool(context) }
    private val timerTool by lazy { createTimerTool(context) }
    private val batteryTool by lazy { createBatteryTool(context) }
    private val musicTool by lazy { createMusicTool(context) }
    private val smsTool by lazy { createSmsTool(context) }
    private val torchTool by lazy { createTorchTool(context) }
    private val toastTool by lazy { createToastTool(context) }
    private val vibrateTool by lazy { createVibrateTool(context) }
    private val getBrightnessTool by lazy { createGetBrightnessTool(context) }
    private val setBrightnessTool by lazy { createSetBrightnessTool(context) }
    private val getVolumeTool by lazy { createGetVolumeTool(context) }
    private val setVolumeTool by lazy { createSetVolumeTool(context) }
    private val wifiInfoTool by lazy { createWifiInfoTool(context) }
    private val telephonyInfoTool by lazy { createTelephonyInfoTool(context) }
    private val shareTool by lazy { createShareTool(context) }
    private val wakeScreenTool by lazy { createWakeScreenTool(context) }
    private val mediaScannerTool by lazy { createMediaScannerTool(context) }
    private val notificationPostTool by lazy { createNotificationPostTool(context) }
    private val storageInfoTool by lazy { createStorageInfoTool(context) }
    private val appSwitchTool by lazy { createAppSwitchTool(context) }
    private val appLockTool by lazy { createAppLockTool(context) }
    private val fingerprintTool by lazy { me.rerere.rikkahub.data.ai.tools.local.fingerprintTool(context, me.rerere.rikkahub.ui.activity.BiometricPromptActivity.buffer) }
    private val triggerProactiveMessageTool by lazy { createTriggerProactiveMessageTool(context) }
    private val deskNoteTool by lazy { createDeskNoteTool(context) }

    fun getTools(enabledTools: Set<SystemToolOption>, recentMessages: List<UIMessage> = emptyList(), filesManager: FilesManager? = null): List<Tool> {
        val t = mutableListOf<Tool>()
        if (SystemToolOption.Location in enabledTools) t.add(locationTool)
        if (SystemToolOption.Notifications in enabledTools) t.add(notificationsTool)
        if (SystemToolOption.AppUsage in enabledTools) t.add(appUsageTool)
        if (SystemToolOption.ExploreNearby in enabledTools) t.add(exploreNearbyTool)
        if (SystemToolOption.Camera in enabledTools) t.add(cameraTool)
        if (SystemToolOption.Gadgetbridge in enabledTools) t.add(gadgetbridgeTool)
        if (SystemToolOption.Alarm in enabledTools) t.add(alarmTool)
        if (SystemToolOption.Timer in enabledTools) t.add(timerTool)
        if (SystemToolOption.Battery in enabledTools) t.add(batteryTool)
        if (SystemToolOption.Music in enabledTools) t.add(musicTool)
        if (SystemToolOption.Sms in enabledTools) t.add(smsTool)
        if (SystemToolOption.SupabaseQuery in enabledTools) t.add(supabaseQueryTool)
        if (SystemToolOption.Torch in enabledTools) t.add(torchTool)
        if (SystemToolOption.Toast in enabledTools) t.add(toastTool)
        if (SystemToolOption.Vibrate in enabledTools) t.add(vibrateTool)
        if (SystemToolOption.Brightness in enabledTools) { t.add(getBrightnessTool); t.add(setBrightnessTool) }
        if (SystemToolOption.Volume in enabledTools) { t.add(getVolumeTool); t.add(setVolumeTool) }
        if (SystemToolOption.WifiInfo in enabledTools) t.add(wifiInfoTool)
        if (SystemToolOption.TelephonyInfo in enabledTools) t.add(telephonyInfoTool)
        if (SystemToolOption.Share in enabledTools) t.add(shareTool)
        if (SystemToolOption.SetWallpaper in enabledTools) t.add(createSetWallpaperTool(context, recentMessages, filesManager))
        if (SystemToolOption.WakeScreen in enabledTools) t.add(wakeScreenTool)
        if (SystemToolOption.ScanMedia in enabledTools) t.add(mediaScannerTool)
        if (SystemToolOption.PostNotification in enabledTools) t.add(notificationPostTool)
        if (SystemToolOption.StorageInfo in enabledTools) t.add(storageInfoTool)
        if (SystemToolOption.AppSwitch in enabledTools) t.add(appSwitchTool)
        if (SystemToolOption.AppLock in enabledTools) t.add(appLockTool)
        if (SystemToolOption.Fingerprint in enabledTools) t.add(fingerprintTool)
        if (SystemToolOption.ProactiveTrigger in enabledTools) t.add(triggerProactiveMessageTool)
        if (SystemToolOption.DeskNote in enabledTools) t.add(deskNoteTool)
        return t
    }
}
