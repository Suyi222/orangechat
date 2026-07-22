/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Location01
import me.rerere.hugeicons.stroke.Notification02
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.BatteryFull
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Watch01
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.hugeicons.stroke.Flashlight
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Sun02
import me.rerere.hugeicons.stroke.Speaker01
import me.rerere.hugeicons.stroke.SmartphoneWifi
import me.rerere.hugeicons.stroke.Share05
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.hugeicons.stroke.Scan
import me.rerere.hugeicons.stroke.HardDrive
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.rikkahub.data.ai.tools.SystemTools
import me.rerere.rikkahub.service.KeepAliveService
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.ui.permission.PermissionAccessBackgroundLocation
import me.rerere.rikkahub.ui.components.ui.permission.PermissionAccessCoarseLocation
import me.rerere.rikkahub.ui.components.ui.permission.PermissionAccessFineLocation
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionPostNotifications
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadSms
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadPhoneState
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.Screen
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSystemToolsPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var systemToolsSetting by remember(settings) {
        mutableStateOf(settings.systemToolsSetting)
    }
    LaunchedEffect(settings) {
        systemToolsSetting = settings.systemToolsSetting
    }

    fun updateSystemToolsSetting(setting: SystemToolsSetting) {
        systemToolsSetting = setting
        vm.updateSettings(settings.copy(systemToolsSetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val locationPermissions = buildSet {
        add(PermissionAccessFineLocation)
        add(PermissionAccessCoarseLocation)
        add(PermissionAccessBackgroundLocation)
    }
    val locationPermissionState = rememberPermissionState(permissions = locationPermissions)

    val notificationPermissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionPostNotifications)
        } else emptySet<PermissionInfo>()
    )

    var keepAliveEnabled by remember(settings) { mutableStateOf(settings.keepAliveEnabled) }
    LaunchedEffect(settings) { keepAliveEnabled = settings.keepAliveEnabled }

    val cameraPermissionState = rememberPermissionState(permissions = setOf(PermissionCamera))
    val smsPermissionState = rememberPermissionState(permissions = setOf(PermissionReadSms))
    val phoneStatePermissionState = rememberPermissionState(permissions = setOf(PermissionReadPhoneState))

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("系统工具") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 🌲 工作流主动唤醒（放在最前面便于发现）
            item {
            CardGroup(title = { Text("🌲 工作流主动唤醒") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用主动唤醒工具") },
                    supportingContent = { Text("开启后注册 trigger_proactive_message 工具。工作流可通过此工具唤醒 AI 在聊天中查岗或提醒你") },
                    trailingContent = {
                        Switch(
                            checked = systemToolsSetting.proactiveTriggerEnabled,
                            onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(proactiveTriggerEnabled = enabled)) }
                        )
                    }
                )
            }
            }

            // 📝 桌面便签
            item {
            CardGroup(title = { Text("📝 桌面便签") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用桌面便签工具") },
                    supportingContent = { Text("开启后注册 post_desk_note 工具。AI 可在桌面 Widget 写/删提醒，解锁即见。先在桌面添加「橘瓣·桌面便签」小部件") },
                    trailingContent = {
                        Switch(
                            checked = systemToolsSetting.deskNoteEnabled,
                            onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(deskNoteEnabled = enabled)) }
                        )
                    }
                )
            }
            }

            // 后台保活
            item {
            CardGroup(title = { Text("后台保活") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.Pulse01, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.setting_system_tools_keep_alive)) },
                    supportingContent = { Text(stringResource(R.string.setting_system_tools_keep_alive_desc)) },
                    trailingContent = {
                        Switch(
                            checked = keepAliveEnabled,
                            onCheckedChange = { enabled ->
                                keepAliveEnabled = enabled
                                vm.updateSettings(settings.copy(keepAliveEnabled = enabled))
                                if (enabled) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionState.allPermissionsGranted) {
                                        notificationPermissionState.requestPermissions()
                                    } else { KeepAliveService.start(context) }
                                } else { KeepAliveService.stop(context) }
                            }
                        )
                    }
                )
                if (keepAliveEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionState.allPermissionsGranted) {
                    item(
                        headlineContent = { Text("⚠ 通知权限未授予") },
                        supportingContent = { Text("开启保活需要通知权限以显示常驻通知") },
                        trailingContent = { FilledTonalButton(onClick = { notificationPermissionState.requestPermissions() }) { Text("授权") } }
                    )
                }
            }
            }

            // 位置服务
            item {
            CardGroup(title = { Text("位置服务") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.Location01, contentDescription = null) },
                    headlineContent = { Text("启用位置工具") },
                    supportingContent = { Text("允许AI获取您的当前位置，并使用高德API转换为地址") },
                    trailingContent = {
                        Switch(checked = systemToolsSetting.locationAccess, onCheckedChange = { enabled ->
                            if (enabled && !locationPermissionState.allPermissionsGranted) locationPermissionState.requestPermissions()
                            updateSystemToolsSetting(systemToolsSetting.copy(locationAccess = enabled))
                        })
                    }
                )
                if (systemToolsSetting.locationAccess && !locationPermissionState.allPermissionsGranted) {
                    item(headlineContent = { Text("⚠ 位置权限未授予") }, supportingContent = { Text("点击授权按钮授予位置权限") },
                        trailingContent = { FilledTonalButton(onClick = { locationPermissionState.requestPermissions() }) { Text("授权") } })
                }
                if (systemToolsSetting.locationAccess) {
                    item(headlineContent = { Text("高德API Key") }, supportingContent = {
                        TextField(value = systemToolsSetting.amapApiKey, onValueChange = { key -> updateSystemToolsSetting(systemToolsSetting.copy(amapApiKey = key)) },
                            placeholder = { Text("请输入高德Web服务API Key") }, modifier = Modifier.fillMaxSize(), singleLine = true,
                            shape = MaterialTheme.shapes.small, colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    })
                }
            }
            }

            // 通知服务
            item {
            CardGroup(title = { Text("通知服务") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.Notification02, contentDescription = null) },
                    headlineContent = { Text("启用通知工具") },
                    supportingContent = { Text("允许AI读取今日通知") },
                    trailingContent = { Switch(checked = systemToolsSetting.notificationAccess, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(notificationAccess = enabled)) }) }
                )
            }
            }

            // App使用统计
            item {
            CardGroup(title = { Text("应用使用统计") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用应用使用工具") },
                    supportingContent = { Text("允许AI查看您的应用使用情况") },
                    trailingContent = { Switch(checked = systemToolsSetting.appUsageAccess, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(appUsageAccess = enabled)) }) }
                )
            }
            }

            // 相机
            item {
            CardGroup(title = { Text("相机/拍照服务") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(
                    leadingContent = { Icon(imageVector = HugeIcons.Camera01, contentDescription = null) },
                    headlineContent = { Text("启用拍照工具") },
                    supportingContent = { Text("允许AI在后台拍照") },
                    trailingContent = { Switch(checked = systemToolsSetting.cameraAccess, onCheckedChange = { enabled ->
                        if (enabled && !cameraPermissionState.allPermissionsGranted) cameraPermissionState.requestPermissions()
                        updateSystemToolsSetting(systemToolsSetting.copy(cameraAccess = enabled))
                    })}
                )
            }
            }

            // 电量
            item {
            CardGroup(title = { Text("电量信息") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.BatteryFull, contentDescription = null) },
                    headlineContent = { Text("启用电量工具") }, supportingContent = { Text("允许AI读取电量信息") },
                    trailingContent = { Switch(checked = systemToolsSetting.batteryEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(batteryEnabled = enabled)) }) })
            }
            }

            // 手电筒
            item {
            CardGroup(title = { Text("手电筒") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Flashlight, contentDescription = null) },
                    headlineContent = { Text("启用手电筒工具") }, supportingContent = { Text("允许AI开关手电筒") },
                    trailingContent = { Switch(checked = systemToolsSetting.torchEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(torchEnabled = enabled)) }) })
            }
            }

            // Toast
            item {
            CardGroup(title = { Text("Toast提示") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Megaphone01, contentDescription = null) },
                    headlineContent = { Text("启用Toast工具") }, supportingContent = { Text("允许AI弹出Toast提示") },
                    trailingContent = { Switch(checked = systemToolsSetting.toastEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(toastEnabled = enabled)) }) })
            }
            }

            // 震动
            item {
            CardGroup(title = { Text("震动") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用震动工具") }, supportingContent = { Text("允许AI控制震动") },
                    trailingContent = { Switch(checked = systemToolsSetting.vibrateEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(vibrateEnabled = enabled)) }) })
            }
            }

            // 亮度
            item {
            CardGroup(title = { Text("屏幕亮度") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Sun02, contentDescription = null) },
                    headlineContent = { Text("启用亮度工具") }, supportingContent = { Text("允许AI读/设屏幕亮度") },
                    trailingContent = { Switch(checked = systemToolsSetting.brightnessEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(brightnessEnabled = enabled)) }) })
            }
            }

            // 音量
            item {
            CardGroup(title = { Text("音量控制") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Speaker01, contentDescription = null) },
                    headlineContent = { Text("启用音量工具") }, supportingContent = { Text("允许AI读/设音量") },
                    trailingContent = { Switch(checked = systemToolsSetting.volumeEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(volumeEnabled = enabled)) }) })
            }
            }

            // WiFi
            item {
            CardGroup(title = { Text("WiFi信息") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.SmartphoneWifi, contentDescription = null) },
                    headlineContent = { Text("启用WiFi信息工具") }, supportingContent = { Text("允许AI读取WiFi信息") },
                    trailingContent = { Switch(checked = systemToolsSetting.wifiInfoEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(wifiInfoEnabled = enabled)) }) })
            }
            }

            // 电话信息
            item {
            CardGroup(title = { Text("电话信息") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用电话信息工具") }, supportingContent = { Text("允许AI读取SIM/运营商信息") },
                    trailingContent = { Switch(checked = systemToolsSetting.telephonyInfoEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(telephonyInfoEnabled = enabled)) }) })
            }
            }

            // 分享
            item {
            CardGroup(title = { Text("分享") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Share05, contentDescription = null) },
                    headlineContent = { Text("启用分享工具") }, supportingContent = { Text("允许AI分享文字/URL") },
                    trailingContent = { Switch(checked = systemToolsSetting.shareEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(shareEnabled = enabled)) }) })
            }
            }

            // 壁纸
            item {
            CardGroup(title = { Text("设置壁纸") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Image02, contentDescription = null) },
                    headlineContent = { Text("启用壁纸工具") }, supportingContent = { Text("允许AI设置壁纸") },
                    trailingContent = { Switch(checked = systemToolsSetting.setWallpaperEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(setWallpaperEnabled = enabled)) }) })
            }
            }

            // 唤醒屏幕
            item {
            CardGroup(title = { Text("唤醒屏幕") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.FullScreen, contentDescription = null) },
                    headlineContent = { Text("启用唤醒屏幕工具") }, supportingContent = { Text("允许AI唤醒黑屏") },
                    trailingContent = { Switch(checked = systemToolsSetting.wakeScreenEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(wakeScreenEnabled = enabled)) }) })
            }
            }

            // 媒体扫描
            item {
            CardGroup(title = { Text("媒体扫描") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Scan, contentDescription = null) },
                    headlineContent = { Text("启用媒体扫描工具") }, supportingContent = { Text("允许AI触发媒体扫描") },
                    trailingContent = { Switch(checked = systemToolsSetting.scanMediaEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(scanMediaEnabled = enabled)) }) })
            }
            }

            // 发送通知
            item {
            CardGroup(title = { Text("发送通知") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.Notification02, contentDescription = null) },
                    headlineContent = { Text("启用发送通知工具") }, supportingContent = { Text("允许AI主动发系统通知") },
                    trailingContent = { Switch(checked = systemToolsSetting.postNotificationEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(postNotificationEnabled = enabled)) }) })
            }
            }

            // 存储信息
            item {
            CardGroup(title = { Text("存储信息") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.HardDrive, contentDescription = null) },
                    headlineContent = { Text("启用存储信息工具") }, supportingContent = { Text("允许AI读取存储空间") },
                    trailingContent = { Switch(checked = systemToolsSetting.storageInfoEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(storageInfoEnabled = enabled)) }) })
            }
            }

            // 应用切换
            item {
            CardGroup(title = { Text("应用切换") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用应用切换工具") }, supportingContent = { Text("允许AI启动/切换应用") },
                    trailingContent = { Switch(checked = systemToolsSetting.appSwitchEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(appSwitchEnabled = enabled)) }) })
            }
            }

            // App锁定
            item {
            CardGroup(title = { Text("App 锁定") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.SmartPhone01, contentDescription = null) },
                    headlineContent = { Text("启用 App 锁定工具") }, supportingContent = { Text("允许AI锁定指定App") },
                    trailingContent = { Switch(checked = systemToolsSetting.appLockEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(appLockEnabled = enabled)) }) })
            }
            }

            // 指纹验证
            item {
            CardGroup(title = { Text("指纹验证") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                item(leadingContent = { Icon(imageVector = HugeIcons.FingerPrint, contentDescription = null) },
                    headlineContent = { Text("启用指纹验证工具") }, supportingContent = { Text("允许AI弹出指纹验证框验证身份") },
                    trailingContent = { Switch(checked = systemToolsSetting.fingerprintEnabled, onCheckedChange = { enabled -> updateSystemToolsSetting(systemToolsSetting.copy(fingerprintEnabled = enabled)) }) })
            }
            }
        }

        PermissionManager(permissionState = locationPermissionState)
        PermissionManager(permissionState = notificationPermissionState)
        PermissionManager(permissionState = cameraPermissionState)
        PermissionManager(permissionState = smsPermissionState)
        PermissionManager(permissionState = phoneStatePermissionState)
    }
}
