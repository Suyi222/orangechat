/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginEnv
import me.rerere.rikkahub.plugin.model.PluginHookConfig
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.repository.PluginRepository
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import java.io.File

private const val TAG = "PluginWebViewPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebViewPage(
    pluginId: String,
    htmlEntryPath: String,
    pluginManager: PluginManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val pluginLoader = koinInject<PluginLoader>()
    val pluginRepository = koinInject<PluginRepository>()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingImageCallback by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingBinaryFileCallback by remember { mutableStateOf<String?>(null) }
    var pendingImportAudioCallback by remember { mutableStateOf<String?>(null) }
    var webViewFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // State for saveFileAs (SAF CreateDocument)
    var pendingSaveCallbackId by remember { mutableStateOf<String?>(null) }
    var pendingSaveBase64Data by remember { mutableStateOf<String?>(null) }

    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val pluginInfo = plugins.find { it.manifest.id == pluginId }

    val dataStore = remember(pluginId) {
        PluginDataStore(context, pluginId)
    }

    // Overlay WebView reference for pomodoro lock screen
    var overlayWebView by remember { mutableStateOf<WebView?>(null) }

    // Launcher for overlay permission (SYSTEM_ALERT_WINDOW)
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Result is handled by checking Settings.canDrawOverlays when needed
    }

    // Launcher for POST_NOTIFICATIONS permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result is handled; proceed regardless
    }

    // Timer end broadcast receiver
    val timerEndReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PomodoroTimerService.ACTION_TIMER_END) {
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                        // 插件环境 v2（B4）：同时走通用事件通道，Bridge.on('timerEnd', fn) 也能收到
                        webView?.evaluateJavascript(
                            "if(typeof window.__bridgeEvent === 'function') { window.__bridgeEvent('timerEnd', '{}'); }",
                            null
                        )
                    }
                    overlayWebView?.post {
                        overlayWebView?.evaluateJavascript(
                            "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // Music completion broadcast receiver
    val musicCompletedReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == MusicPlayerService.ACTION_MUSIC_COMPLETED) {
                    Log.i(TAG, "[DEBUG] musicCompletedReceiver received broadcast, webView is null: ${webView == null}")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "if(typeof window.onMusicCompleted === 'function') { window.onMusicCompleted(); } else { console.error('[DEBUG] onMusicCompleted not a function'); }",
                            null
                        )
                        // 插件环境 v2（B4）：同时走通用事件通道
                        webView?.evaluateJavascript(
                            "if(typeof window.__bridgeEvent === 'function') { window.__bridgeEvent('musicCompleted', '{}'); }",
                            null
                        )
                    }
                }
            }
        }
    }

    // 插件环境 v2（B5）：插件页面键盘适配 adjustResize——输入法弹起时页面收缩不被遮挡；
    // 页面销毁时恢复原模式，不影响宿主 Activity 的其他页面
    val hostActivity = context as? Activity
    val originalSoftInputMode = hostActivity?.window?.attributes?.softInputMode
    DisposableEffect(Unit) {
        hostActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            originalSoftInputMode?.let { hostActivity.window?.setSoftInputMode(it) }
        }
    }

    // Register broadcast receivers
    DisposableEffect(Unit) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            timerEndReceiver,
            IntentFilter(PomodoroTimerService.ACTION_TIMER_END)
        )
        LocalBroadcastManager.getInstance(context).registerReceiver(
            musicCompletedReceiver,
            IntentFilter(MusicPlayerService.ACTION_MUSIC_COMPLETED)
        )
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(timerEndReceiver)
            LocalBroadcastManager.getInstance(context).unregisterReceiver(musicCompletedReceiver)
        }
    }

    // 文件选择器 - 用于 Bridge.pickFile()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val bridgeCallback = pendingFileCallback
        if (bridgeCallback != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val content = inputStream?.bufferedReader()?.readText()
                    inputStream?.close()
                    val fileName = uri.lastPathSegment ?: "unknown.txt"
                    val escapedContent = content?.replace("\\", "\\\\")?.replace("'", "\\'")?.replace("\n", "\\n")?.replace("\r", "\\r") ?: ""
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$bridgeCallback', {success:true,fileName:'$escapedName',content:'$escapedContent'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$bridgeCallback', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$bridgeCallback', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingFileCallback = null
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val callbackId = pendingImageCallback
        if (callbackId != null && uri != null) {
            try {
                val base64 = uriToBase64(context, uri)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId']($base64); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process picked image", e)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            }
        } else if (callbackId != null) {
            webView?.evaluateJavascript(
                "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                        "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                null
            )
        }
        pendingImageCallback = null
    }

    // Launcher for binary file picking (Bridge.pickBinaryFile)
    val binaryFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callbackId = pendingBinaryFileCallback
        if (callbackId != null) {
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    val base64 = if (bytes != null) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
                    val fileName = uri.lastPathSegment ?: "unknown"
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:true,fileName:'$escapedName',mimeType:'$mimeType',base64:'$base64'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingBinaryFileCallback = null
        }
    }

    // Launcher for importing audio files (Bridge.importAudioFile) - multi-select with duplicate detection
    val audioFileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<@JvmSuppressWildcards Uri>? ->
        val callbackId = pendingImportAudioCallback
        if (callbackId != null) {
            if (uris.isNullOrEmpty()) {
                // null or empty list -> user cancelled
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            } else {
                val imported = StringBuilder()
                val duplicates = StringBuilder()
                val errors = StringBuilder()
                val seenInBatch = mutableSetOf<String>()

                // Create music/ subdirectory once
                val musicDir = File(dataStore.getDataDir(), "music")
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                for (uri in uris) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        inputStream?.close()

                        if (bytes == null) {
                            val errName = uri.lastPathSegment ?: "unknown"
                            val escapedErrName = errName.replace("\\", "\\\\").replace("'", "\\'")
                            val escapedErr = "Failed to read file".replace("\\", "\\\\").replace("'", "\\'")
                            if (errors.isNotEmpty()) errors.append(",")
                            errors.append("{fileName:'$escapedErrName',error:'$escapedErr'}")
                            continue
                        }

                        // Get original file name from URI (reuse existing path/colon logic)
                        var fileName = uri.lastPathSegment ?: "unknown.mp3"
                        val lastSlash = fileName.lastIndexOf('/')
                        val lastColon = fileName.lastIndexOf(':')
                        val cutIndex = maxOf(lastSlash, lastColon)
                        if (cutIndex >= 0) {
                            fileName = fileName.substring(cutIndex + 1)
                        }

                        // Duplicate detection: existing in music/ OR already seen in this batch
                        val targetFile = File(musicDir, fileName)
                        if (targetFile.exists() || seenInBatch.contains(fileName)) {
                            val escapedDup = fileName.replace("\\", "\\\\").replace("'", "\\'")
                            if (duplicates.isNotEmpty()) duplicates.append(",")
                            duplicates.append("'$escapedDup'")
                        } else {
                            targetFile.writeBytes(bytes)
                            seenInBatch.add(fileName)

                            val filePath = targetFile.absolutePath
                            val escapedPath = filePath.replace("\\", "\\\\").replace("'", "\\'")
                            val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                            if (imported.isNotEmpty()) imported.append(",")
                            imported.append("{filePath:'$escapedPath',fileName:'$escapedName'}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "importAudioFile: failed to process uri=$uri, err=${e.message}", e)
                        val errName = (uri.lastPathSegment ?: "unknown").replace("\\", "\\\\").replace("'", "\\'")
                        val escapedErr = (e.message ?: "Unknown error").replace("\\", "\\\\").replace("'", "\\'")
                        if (errors.isNotEmpty()) errors.append(",")
                        errors.append("{fileName:'$errName',error:'$escapedErr'}")
                    }
                }

                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true,imported:[$imported],duplicates:[$duplicates],errors:[$errors]});", null
                    )
                }
            }
            pendingImportAudioCallback = null
        }
    }

    // Launcher for saveFileAs (SAF CreateDocument - user picks save location)
    val saveFileAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val callbackId = pendingSaveCallbackId
        val base64Data = pendingSaveBase64Data
        if (callbackId != null) {
            if (uri != null && base64Data != null) {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(bytes)
                        outputStream.flush()
                    }
                    val fileName = uri.lastPathSegment ?: "saved_file"
                    val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:true,fileName:'$escapedName'});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView?.post {
                        webView?.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            } else {
                webView?.post {
                    webView?.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'User cancelled'});", null
                    )
                }
            }
            pendingSaveCallbackId = null
            pendingSaveBase64Data = null
        }
    }

    // Launcher for native HTML <input type="file"> file chooser
    val htmlFileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val callback = webViewFileChooserCallback
        if (callback != null) {
            if (uri != null) {
                callback.onReceiveValue(arrayOf(uri))
            } else {
                callback.onReceiveValue(null)
            }
            webViewFileChooserCallback = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pluginInfo?.manifest?.name ?: "\u63D2\u4EF6\u7BA1\u7406") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "\u8FD4\u56DE")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (pluginInfo == null) {
                Text(
                    text = "\u63D2\u4EF6\u4E0D\u5B58\u5728",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            // 插件环境 v2（A2）：debug 包允许 chrome://inspect 远程调试插件页；
                            // release 包 BuildConfig.DEBUG=false 自动关闭，不暴露调试面
                            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                databaseEnabled = true
                                // 插件环境 v2（B5）：字号跟随系统字体缩放（80%~200% 保护带）
                                textZoom = (ctx.resources.configuration.fontScale * 100).toInt().coerceIn(80, 200)
                                // 插件环境 v2（B5）：视口规范化——尊重插件页声明的 viewport meta；
                                // 未声明 viewport 的页面按设备宽度渲染，不再走 980px 虚拟视口缩放发虚
                                useWideViewPort = true
                            }

                            // 插件环境 v2（A4）：软件渲染兜底（manifest uiOptions.softwareRender 按需开启）——
                            // 治部分机型硬件加速下弹层/快速 DOM 更新不重绘（"数据到了画面没刷新"）
                            if (pluginInfo.manifest.uiOptions?.softwareRender == true) {
                                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            }

                            val pluginWebViewClient = PluginWebViewClient(
                                pluginInfo = pluginInfo,
                                dataStore = dataStore,
                                pluginLoader = pluginLoader,
                                pluginManager = pluginManager,
                                pluginRepository = pluginRepository,
                                onPickImage = { callbackId ->
                                    pendingImageCallback = callbackId
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickFile = { callbackId ->
                                    pendingFileCallback = callbackId
                                    filePickerLauncher.launch(arrayOf("text/plain", "text/markdown", "application/octet-stream"))
                                },
                                onPickBinaryFile = { callbackId ->
                                    pendingBinaryFileCallback = callbackId
                                    binaryFilePickerLauncher.launch(arrayOf("*/*"))
                                },
                                onImportAudioFile = { callbackId ->
                                    pendingImportAudioCallback = callbackId
                                    audioFileImportLauncher.launch(arrayOf("audio/*"))
                                },
                                onSaveFileAs = { callbackId, fileName, base64Data ->
                                    pendingSaveCallbackId = callbackId
                                    pendingSaveBase64Data = base64Data
                                    saveFileAsLauncher.launch(fileName)
                                },
                                onClose = onNavigateBack,
                                onStartTimer = { wv, seconds ->
                                    // Check overlay permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayPermissionLauncher.launch(intent)
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Overlay permission required. Please grant it in settings and try again.'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }
                                    // Check notification permission
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                                            != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    PomodoroTimerService.start(context, seconds)
                                    wv.post {
                                        // Find the callbackId from the pending bridge call
                                        // We use a simplified approach - the callback is handled in handleBridgeCall
                                    }
                                },
                                onStopTimer = {
                                    PomodoroTimerService.stop(context)
                                },
                                onShowOverlay = { wv, html ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Overlay permission not granted'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    if (windowManager == null) {
                                        wv.post {
                                            wv.evaluateJavascript(
                                                "window.__bridgeResult('${null}', {success:false,error:'Cannot access WindowManager'});", null
                                            )
                                        }
                                        return@PluginWebViewClient
                                    }

                                    // Remove existing overlay if any
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing existing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                    }

                                    val overlayWv = WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        isFocusable = true
                                        isFocusableInTouchMode = true
                                    }
                                    overlayWv.requestFocus()

                                    // Inject bridge JS for overlay
                                    overlayWv.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(overlayBridgeJavascript, null)
                                            // Auto-focus input/textarea on click so keyboard appears
                                            view?.evaluateJavascript(
                                                "document.addEventListener('click', function(e){ if(e.target.tagName==='INPUT'||e.target.tagName==='TEXTAREA'){ e.target.focus(); } });",
                                                null
                                            )
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            if (url.startsWith("bridge://")) {
                                                val uri = Uri.parse(url)
                                                val method = uri.host ?: return false
                                                val params = uri.queryParameterNames.associateWith {
                                                    uri.getQueryParameter(it) ?: ""
                                                }
                                                when (method) {
                                                    "hideOverlay" -> {
                                                        try {
                                                            windowManager.removeView(overlayWv)
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Error removing overlay", e)
                                                        }
                                                        overlayWv.destroy()
                                                        overlayWebView = null
                                                    }
                                                    "getTimerState" -> {
                                                        val running = PomodoroTimerService.isRunning()
                                                        val remaining = PomodoroTimerService.getRemainingSeconds()
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', {running:$running,remaining:$remaining});", null
                                                            )
                                                        }
                                                    }
                                                    "getData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = dataStore.getData(key)
                                                        val result = if (value != null) {
                                                            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                                                        } else "null"
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', $result);", null
                                                            )
                                                        }
                                                    }
                                                    "setData" -> {
                                                        val key = params["key"] ?: ""
                                                        val value = params["value"] ?: ""
                                                        dataStore.setData(key, value)
                                                        val cbId = params["callbackId"] ?: ""
                                                        overlayWv.post {
                                                            overlayWv.evaluateJavascript(
                                                                "window.__bridgeResult('$cbId', true);", null
                                                            )
                                                        }
                                                    }
                                                    "callAI" -> {
                                                        val cbId = params["callbackId"] ?: ""
                                                        val prompt = params["prompt"] ?: ""
                                                        val contextJson = params["context"] ?: "{}"
                                                        CoroutineScope(Dispatchers.IO).launch {
                                                            try {
                                                                // Use the PluginWebViewClient's callAI via a simple approach
                                                                val settingsStore: SettingsStore = org.koin.java.KoinJavaComponent.get(SettingsStore::class.java)
                                                                val providerManager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
                                                                val settings = settingsStore.settingsFlow.first()
                                                                val model = settings.getCurrentChatModel()
                                                                val aiResult = if (model == null) {
                                                                    """{"success":false,"error":"No chat model configured"}"""
                                                                } else {
                                                                    val providerSetting = model.findProvider(settings.providers)
                                                                    if (providerSetting == null) {
                                                                        """{"success":false,"error":"Provider not found"}"""
                                                                    } else {
                                                                        val providerImpl = providerManager.getProviderByType(providerSetting)
                                                                        val systemPrompt = "你是一个番茄钟陪伴助手。用户正在使用番茄钟专注。请用简短温暖的话回应，鼓励用户保持专注。当前上下文：$contextJson"
                                                                        val messages = listOf(
                                                                            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))),
                                                                            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt)))
                                                                        )
                                                                        val textGenParams = TextGenerationParams(model = model, tools = emptyList(), temperature = 0.7f)
                                                                        val response = providerImpl.generateText(providerSetting, messages, textGenParams)
                                                                        val responseText = response.choices.firstOrNull()?.message?.parts
                                                                            ?.filterIsInstance<UIMessagePart.Text>()
                                                                            ?.firstOrNull()?.text ?: ""
                                                                        val escaped = responseText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                                                        """{"success":true,"text":"$escaped"}"""
                                                                    }
                                                                }
                                                                overlayWv.post {
                                                                    overlayWv.evaluateJavascript("window.__bridgeResult('$cbId', $aiResult);", null)
                                                                }
                                                            } catch (e: Exception) {
                                                                val err = """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
                                                                overlayWv.post {
                                                                    overlayWv.evaluateJavascript("window.__bridgeResult('$cbId', $err);", null)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }

                                    // Block back key
                                    overlayWv.setOnKeyListener { _, keyCode, event ->
                                        keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                                    }

                                    val params = WindowManager.LayoutParams(
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        WindowManager.LayoutParams.MATCH_PARENT,
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                        else
                                            @Suppress("DEPRECATION")
                                            WindowManager.LayoutParams.TYPE_PHONE,
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                        android.graphics.PixelFormat.TRANSLUCENT
                                    )
                                    params.gravity = Gravity.CENTER
                                    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                                    // Load HTML content
                                    overlayWv.loadDataWithBaseURL(
                                        "https://rikkahub.local",
                                        html,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )

                                    windowManager.addView(overlayWv, params)
                                    overlayWebView = overlayWv
                                },
                                onHideOverlay = {
                                    val activity = context as? Activity
                                    val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                                    overlayWebView?.let { existingOverlay ->
                                        try {
                                            windowManager?.removeView(existingOverlay)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error removing overlay", e)
                                        }
                                        existingOverlay.destroy()
                                        overlayWebView = null
                                    }
                                }
                            )
                            webViewClient = pluginWebViewClient

                            // 插件环境 v2（A3）：Bridge v2 原生通道。JS 调用直达原生，不再走
                            // iframe URL 传参——没有 URL 长度上限（根治 setData 大数据静默失败），
                            // 也没有 iframe 创建/销毁开销。注入的 Bridge JS 会自动探测本通道，
                            // 不可用时回落旧 iframe 通道，旧插件零改动。
                            addJavascriptInterface(
                                pluginWebViewClient.createNativeBridge(this),
                                "NativePluginBridge"
                            )

                            // 处理 HTML <input type="file"> 文件选择 +
                            // 插件环境 v2（A1）：console 日志面——JS 的 console.log/warn/error
                            // 从此进入 logcat（TAG=PluginWebConsole），插件 JS 报错不再是盲盒
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    // Cancel any previous callback
                                    webViewFileChooserCallback?.onReceiveValue(null)
                                    webViewFileChooserCallback = filePathCallback

                                    val acceptTypes = fileChooserParams?.acceptTypes
                                        ?.filter { it.isNotBlank() }
                                        ?.toTypedArray()
                                        ?: arrayOf("*/*")
                                    // If no meaningful accept types, use */*
                                    if (acceptTypes.isEmpty()) {
                                        htmlFileChooserLauncher.launch(arrayOf("*/*"))
                                    } else {
                                        htmlFileChooserLauncher.launch(acceptTypes)
                                    }
                                    return true
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    val msg = consoleMessage ?: return true
                                    val text = "[${pluginInfo.manifest.id}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                                    when (msg.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> Log.e("PluginWebConsole", text)
                                        ConsoleMessage.MessageLevel.WARNING -> Log.w("PluginWebConsole", text)
                                        else -> Log.i("PluginWebConsole", text)
                                    }
                                    return true
                                }
                            }

                            // 禁用原生长按选择菜单 - 通过CSS/JS控制，不再用原生拦截
                            // （原生setOnLongClickListener会阻止批注模式的文字选择）

                            val htmlFile = File(pluginInfo.directory, htmlEntryPath)
                            if (htmlFile.exists()) {
                                loadUrl("file://${htmlFile.absolutePath}")
                            } else {
                                loadData(
                                    "<html><body><h2>\u9875\u9762\u6587\u4EF6\u4E0D\u5B58\u5728</h2><p>$htmlEntryPath</p></body></html>",
                                    "text/html",
                                    "UTF-8"
                                )
                            }

                            webView = this@apply
                        }
                    },
                    update = { wv ->
                        webView = wv
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Clean up main WebView
            webView?.destroy()
            // Clean up overlay WebView
            overlayWebView?.let { overlay ->
                try {
                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    windowManager?.removeView(overlay)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing overlay on dispose", e)
                }
                overlay.destroy()
            }
            // Stop timer service
            PomodoroTimerService.stop(context)
        }
    }
}

private class PluginWebViewClient(
    private val pluginInfo: PluginInfo,
    private val dataStore: PluginDataStore,
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val pluginRepository: PluginRepository,
    private val onPickImage: (callbackId: String) -> Unit,
    private val onPickFile: (callbackId: String) -> Unit,
    private val onPickBinaryFile: (callbackId: String) -> Unit,
    private val onImportAudioFile: (callbackId: String) -> Unit,
    private val onSaveFileAs: (callbackId: String, fileName: String, base64Data: String) -> Unit,
    private val onClose: () -> Unit,
    private val onStartTimer: (webView: WebView, seconds: Int) -> Unit,
    private val onStopTimer: () -> Unit,
    private val onShowOverlay: (webView: WebView, html: String) -> Unit,
    private val onHideOverlay: () -> Unit
) : WebViewClient() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.startsWith("bridge://")) {
            handleBridgeCall(view!!, url)
            return true
        }
        if (!url.startsWith("file://") && !url.startsWith("about:blank")) {
            // Open external URLs (e.g. Supabase download links) in system browser
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                view?.context?.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open external URL: $url", e)
            }
            return true
        }
        return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(bridgeJavascript, null)
        // 插件环境 v2（A4）：页面加载完成后主动触发一次重绘，缓解部分机型首帧不刷新
        view?.postInvalidate()
    }

    // 插件环境 v2（A1）：资源加载失败进 logcat——配合 onConsoleMessage，插件 UI 的
    // 失败路径（JS 报错 / 资源 404 / http 错误）从此全部可见，不再静默
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request != null && error != null) {
            Log.w(TAG, "WebView resource error: url=${request.url}, code=${error.errorCode}, desc=${error.description}")
        }
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (request != null && errorResponse != null) {
            Log.w(TAG, "WebView http error: url=${request.url}, status=${errorResponse.statusCode}")
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    /**
     * 插件环境 v2（A3）：创建 Bridge v2 原生通道对象，经 addJavascriptInterface 注入为
     * window.NativePluginBridge。所有调用最终都汇入 [handleBridgeCallCore]（主线程执行，
     * 与旧 iframe 通道完全同构），或走 [handleSyncBridgeCall]（同步 KV，桥线程执行）。
     */
    fun createNativeBridge(webView: WebView): NativePluginBridge =
        NativePluginBridge(
            webView = webView,
            asyncDispatch = { wv, method, params -> handleBridgeCallCore(wv, method, params) },
            syncDispatch = { method, params -> handleSyncBridgeCall(method, params) }
        )

    private fun handleBridgeCall(webView: WebView, url: String) {
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        handleBridgeCallCore(webView, method, params)
    }

    /**
     * Bridge 调用统一分发：旧 iframe 通道与 v2 原生通道的公共处理器。
     * 必须在主线程调用（原生通道入口已 post 到主线程）。
     */
    private fun handleBridgeCallCore(webView: WebView, method: String, params: Map<String, String>) {
        when (method) {
            "getPluginConfig" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val savedConfig = pluginRepository.getPluginConfig(pluginInfo.manifest.id)
                        val mergedConfig = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()

                        pluginInfo.manifest.config.forEach { field ->
                            if (savedConfig.containsKey(field.name)) {
                                mergedConfig[field.name] = savedConfig[field.name]!!
                            } else if (field.default != null) {
                                mergedConfig[field.name] = field.default
                            }
                        }

                        savedConfig.forEach { (key, value) ->
                            if (!mergedConfig.containsKey(key)) {
                                mergedConfig[key] = value
                            }
                        }

                        val jsonObj = JsonObject(mergedConfig)
                        val result = json.encodeToString(JsonObject.serializer(), jsonObj)
                        Log.d(TAG, "getPluginConfig for ${pluginInfo.manifest.id}: $result")
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', $result);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get plugin config", e)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', {});", null
                            )
                        }
                    }
                }
            }

            "getData" -> {
                val key = params["key"] ?: ""
                val value = dataStore.getData(key)
                val result = if (value != null) {
                    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                } else "null"
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', $result);", null
                    )
                }
            }

            "setData" -> {
                val key = params["key"] ?: ""
                val value = params["value"] ?: ""
                dataStore.setData(key, value)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', true);", null
                    )
                }
            }

            "deleteData" -> {
                val key = params["key"] ?: ""
                dataStore.deleteData(key)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', true);", null
                    )
                }
            }

            "listData" -> {
                val keys = dataStore.listData()
                val jsonArray = JSONArray(keys)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', ${jsonArray.toString()});", null
                    )
                }
            }

            "pickImage" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickImage(callbackId)
            }

            "pickFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickFile(callbackId)
            }

            "pickBinaryFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickBinaryFile(callbackId)
            }

            "importAudioFile" -> {
                val callbackId = params["callbackId"] ?: ""
                onImportAudioFile(callbackId)
            }

            "writeFile" -> {
                val fileName = params["fileName"] ?: ""
                val base64Data = params["data"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = File(dir, fileName)
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    file.writeBytes(bytes)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', '${file.absolutePath}');", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', null);", null
                        )
                    }
                }
            }

            "readFile" -> {
                val fileName = params["fileName"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = File(dir, fileName)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', '$base64');", null
                            )
                        }
                    } else {
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', null);", null
                            )
                        }
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', null);", null
                        )
                    }
                }
            }

            "listFiles" -> {
                val dirPath = params["dir"] ?: ""
                val baseDir = if (dirPath.isEmpty()) dataStore.getDataDir()
                              else File(dataStore.getDataDir(), dirPath)
                val files = if (baseDir.exists() && baseDir.isDirectory) {
                    baseDir.listFiles()?.map { it.name } ?: emptyList()
                } else emptyList()
                val jsonArray = JSONArray(files)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', ${jsonArray.toString()});", null
                    )
                }
            }

            "deleteFile" -> {
                val fileName = params["fileName"] ?: ""
                val dir = dataStore.getDataDir()
                val file = File(dir, fileName)
                val result = file.delete()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', $result);", null
                    )
                }
            }

            "musicPlay" -> {
                val filePath = params["filePath"] ?: ""
                val title = params["title"] ?: ""
                val artist = params["artist"] ?: ""
                try {
                    MusicPlayerService.play(webView.context, filePath, title, artist)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:false,error:'$errMsg'});", null
                        )
                    }
                }
            }

            "musicPause" -> {
                try {
                    MusicPlayerService.pause(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:false});", null
                        )
                    }
                }
            }

            "musicResume" -> {
                try {
                    MusicPlayerService.resume(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:false});", null
                        )
                    }
                }
            }

            "musicStop" -> {
                try {
                    MusicPlayerService.stop(webView.context)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:true});", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {success:false});", null
                        )
                    }
                }
            }

            "musicSeek" -> {
                val position = params["position"]?.toIntOrNull() ?: 0
                try {
                    MusicPlayerService.seekTo(webView.context, position)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult('${params["callbackId"]}', {success:true});", null) }
                } catch (e: Exception) {
                    Log.e(TAG, "musicSeek failed, position=$position, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult('${params["callbackId"]}', {success:false});", null) }
                }
            }

            "musicGetProgress" -> {
                try {
                    val position = MusicPlayerService.getCurrentPosition()
                    val duration = MusicPlayerService.getDuration()
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', {position:$position,duration:$duration});", null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "musicGetProgress failed, err=${e.message}", e)
                    webView.post { webView.evaluateJavascript("window.__bridgeResult('${params["callbackId"]}', {position:0,duration:0});", null) }
                }
            }

            "close" -> {
                onClose()
            }

            "callTool" -> {
                val toolName = params["toolName"] ?: ""
                val toolParams = params["params"] ?: "{}"
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val result = callPluginTool(toolName, toolParams)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', ${result});", null
                            )
                        }
                    } catch (e: Exception) {
                        val errorResult = """{"success":false,"error":"${e.message}"}"""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', $errorResult);", null
                            )
                        }
                    }
                }
            }

            "callAI" -> {
                val callbackId = params["callbackId"] ?: ""
                val prompt = params["prompt"] ?: ""
                val contextJson = params["context"] ?: "{}"

                if (!pluginInfo.manifest.permissions.contains("ai_chat")) {
                    val errorResult = """{"success":false,"error":"Permission denied: ai_chat permission not declared in manifest"}"""
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', $errorResult);", null
                        )
                    }
                    return
                }

                if (prompt.isBlank()) {
                    val errorResult = """{"success":false,"error":"prompt is required"}"""
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('$callbackId', $errorResult);", null
                        )
                    }
                    return
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val aiResult = callAI(prompt, contextJson)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('$callbackId', $aiResult);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "callAI failed", e)
                        val errorResult = """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('$callbackId', $errorResult);", null
                            )
                        }
                    }
                }
            }

            "notifyHook" -> {
                val callbackId = params["callbackId"] ?: ""
                val hookName = params["hookName"] ?: ""
                val hookContextJson = params["context"] ?: "{}"

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val hookResult = handleHookTrigger(webView, hookName, hookContextJson)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('$callbackId', $hookResult);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "notifyHook failed", e)
                        val errorResult = """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('$callbackId', $errorResult);", null
                            )
                        }
                    }
                }
            }

            "startTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                val seconds = params["seconds"]?.toIntOrNull() ?: (25 * 60)
                onStartTimer(webView, seconds)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true});", null
                    )
                }
            }

            "stopTimer" -> {
                val callbackId = params["callbackId"] ?: ""
                onStopTimer()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true});", null
                    )
                }
            }

            "getTimerState" -> {
                val callbackId = params["callbackId"] ?: ""
                val running = PomodoroTimerService.isRunning()
                val remaining = PomodoroTimerService.getRemainingSeconds()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {running:$running,remaining:$remaining});", null
                    )
                }
            }

            "showOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                val html = params["html"] ?: ""
                onShowOverlay(webView, html)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true});", null
                    )
                }
            }

            "saveFileAs" -> {
                val callbackId = params["callbackId"] ?: ""
                val fileName = params["fileName"] ?: "untitled.json"
                val base64Data = params["data"] ?: ""
                onSaveFileAs(callbackId, fileName, base64Data)
            }

            "hideOverlay" -> {
                val callbackId = params["callbackId"] ?: ""
                onHideOverlay()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true});", null
                    )
                }
            }

            // ===== 插件环境 v2 新增 =====

            // B1：环境能力探测——插件不再盲写，可按 capabilities 降级
            "getEnvInfo" -> {
                val callbackId = params["callbackId"] ?: ""
                val transport = if (params["__transport"] == "native") "native" else "iframe"
                val envInfo = buildJsonObject {
                    put("envVersion", me.rerere.rikkahub.plugin.model.PluginEnv.VERSION)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("pluginId", pluginInfo.manifest.id)
                    put("pluginName", pluginInfo.manifest.name)
                    put("transport", transport)
                    put("capabilities", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("async_bridge"))
                        add(kotlinx.serialization.json.JsonPrimitive("sync_storage"))
                        add(kotlinx.serialization.json.JsonPrimitive("toast"))
                        add(kotlinx.serialization.json.JsonPrimitive("events"))
                        add(kotlinx.serialization.json.JsonPrimitive("console_log"))
                    })
                }
                val result = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), envInfo)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', $result);", null
                    )
                }
            }

            // B3：原生 Toast 轻反馈
            "showToast" -> {
                val callbackId = params["callbackId"] ?: ""
                val message = params["message"] ?: ""
                if (message.isNotBlank()) {
                    Toast.makeText(webView.context, message, Toast.LENGTH_SHORT).show()
                }
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:true});", null
                    )
                }
            }
        }
    }

    /**
     * 插件环境 v2（B2）：同步 Bridge 调用处理。在 NativePluginBridge 的桥线程上执行，
     * 因此只允许无 UI、线程安全的操作（SharedPreferences 读写）。返回值是 JSON 字符串，
     * 由 JS 侧同步接收——这是 getDataSync/setDataSync 的底层实现，
     * 用于消灭「把 Promise 当同步用」这类高频误用。
     */
    private fun handleSyncBridgeCall(method: String, params: Map<String, String>): String {
        return try {
            when (method) {
                "getData" -> {
                    val key = params["key"] ?: ""
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    val value = dataStore.getData(key)
                    if (value != null) {
                        """{"success":true,"value":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(value))}}"""
                    } else {
                        """{"success":true,"value":null}"""
                    }
                }
                "setData" -> {
                    val key = params["key"] ?: ""
                    val value = params["value"] ?: ""
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    dataStore.setData(key, value)
                    """{"success":true}"""
                }
                "deleteData" -> {
                    val key = params["key"] ?: ""
                    if (key.isBlank()) return """{"success":false,"error":"key is required"}"""
                    dataStore.deleteData(key)
                    """{"success":true}"""
                }
                "listData" -> {
                    val keys = dataStore.listData()
                    """{"success":true,"keys":${org.json.JSONArray(keys).toString()}}"""
                }
                else -> """{"success":false,"error":"sync bridge does not support method: $method"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync bridge call failed: method=$method", e)
            """{"success":false,"error":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(e.message ?: "Unknown error"))}}"""
        }
    }

    private suspend fun callAI(prompt: String, contextJson: String): String {
        val settingsStore: SettingsStore = org.koin.java.KoinJavaComponent.get(SettingsStore::class.java)
        val providerManager: ProviderManager = org.koin.java.KoinJavaComponent.get(ProviderManager::class.java)
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel()
            ?: return """{"success":false,"error":"No chat model configured"}"""
        val providerSetting = model.findProvider(settings.providers)
            ?: return """{"success":false,"error":"Provider not found for model"}"""

        val providerImpl = providerManager.getProviderByType(providerSetting)

        val systemPrompt = buildString {
            append("\u4F60\u662F\u4E00\u4E2A\u9605\u8BFB\u52A9\u624B\u3002\u8BF7\u6839\u636E\u7528\u6237\u7684\u95EE\u9898\u7ED9\u51FA\u6709\u6DF1\u5EA6\u7684\u3001\u6E29\u67D4\u7684\u56DE\u7B54\u3002")
            if (contextJson.isNotBlank() && contextJson != "{}") {
                try {
                    val ctx = JSONObject(contextJson)
                    append("\n\n\u5F53\u524D\u9605\u8BFB\u4E0A\u4E0B\u6587\uFF1A")
                    if (ctx.has("book")) append("\n\u4E66\u540D\uFF1A").append(ctx.getString("book"))
                    if (ctx.has("chapter")) append("\n\u7AE0\u8282\uFF1A\u7B2C").append(ctx.getString("chapter")).append("\u7AE0")
                    if (ctx.has("page")) append("\n\u9875\u7801\uFF1A\u7B2C").append(ctx.getString("page")).append("\u9875")
                    if (ctx.has("annotations")) append("\n\u5DF2\u6709\u6279\u6CE8\uFF1A").append(ctx.getString("annotations"))
                    if (ctx.has("content")) append("\n\u9875\u9762\u5185\u5BB9\uFF1A").append(ctx.getString("content"))
                    if (ctx.has("quote")) append("\n\u5F15\u7528\u539F\u6587\uFF1A").append(ctx.getString("quote"))
                    if (ctx.has("note")) append("\n\u7528\u6237\u6279\u6CE8\uFF1A").append(ctx.getString("note"))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse context JSON", e)
                }
            }
        }

        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(systemPrompt))
            ),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(prompt))
            )
        )

        val textGenParams = TextGenerationParams(
            model = model,
            tools = emptyList(),
            temperature = 0.7f
        )

        return try {
            val response = providerImpl.generateText(providerSetting, messages, textGenParams)
            val responseText = response.choices.firstOrNull()?.message?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.firstOrNull()?.text ?: ""

            val escaped = responseText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            """{"success":true,"text":"$escaped"}"""
        } catch (e: Exception) {
            Log.e(TAG, "AI generation failed", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
        }
    }

    private suspend fun handleHookTrigger(webView: WebView, hookName: String, contextJson: String): String {
        val hookConfig = pluginInfo.manifest.hookConfigs[hookName]
            ?: return """{"success":false,"error":"Hook '$hookName' not configured in manifest"}"""

        when (hookConfig.action) {
            "call_js_function" -> {
                val functionName = hookConfig.function
                    ?: return """{"success":false,"error":"function is required for call_js_function action"}"""

                if (!hookConfig.autoTrigger) {
                    return """{"success":false,"error":"Hook '$hookName' is not auto-triggered"}"""
                }

                val jsCode = "try { if(typeof $functionName === 'function') { $functionName(${escapeJsString(contextJson)}); } } catch(e) { console.error('Hook JS error:', e); }"
                webView.post {
                    webView.evaluateJavascript(jsCode, null)
                }
                return """{"success":true,"action":"call_js_function","function":"$functionName"}"""
            }

            "call_ai" -> {
                val template = hookConfig.promptTemplate
                    ?: return """{"success":false,"error":"promptTemplate is required for call_ai action"}"""

                val contextObj = try {
                    JSONObject(contextJson)
                } catch (e: Exception) {
                    return """{"success":false,"error":"Invalid context JSON"}"""
                }

                var prompt = template
                val variableKeys = listOf("book", "chapter", "page", "quote", "note", "content", "annotations")
                for (key in variableKeys) {
                    if (contextObj.has(key)) {
                        val value = contextObj.getString(key)
                        prompt = prompt.replace("{$key}", value)
                    }
                }

                val aiResult = callAI(prompt, contextJson)
                return aiResult
            }

            else -> {
                return """{"success":false,"error":"Unknown hook action: ${hookConfig.action}"}"""
            }
        }
    }

    private fun escapeJsString(str: String): String {
        val escaped = str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private suspend fun callPluginTool(toolName: String, params: String): String {
        return try {
            val loadedPlugin: LoadedPlugin? = pluginLoader.getAllLoadedPlugins().find { plugin: LoadedPlugin ->
                plugin.info.manifest.tools.any { toolDef -> toolDef.name == toolName }
            }

            if (loadedPlugin == null) {
                return """{"success":false,"error":"Tool not found: $toolName"}"""
            }

            val jsonParams = Json.parseToJsonElement(params)

            val result = pluginLoader.callTool(
                pluginId = loadedPlugin.id,
                toolName = toolName,
                params = jsonParams
            )

            return result.fold(
                onSuccess = { jsonElement: kotlinx.serialization.json.JsonElement ->
                    Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
                },
                onFailure = { error: Throwable ->
                    """{"success":false,"error":"${error.message}"}"""
                }
            )
        } catch (e: Exception) {
            """{"success":false,"error":"${e.message}"}"""
        }
    }
}

/**
 * 插件环境 v2（A3）：Bridge v2 原生通道。
 *
 * 通过 addJavascriptInterface 注入为 window.NativePluginBridge：
 * - [call]：异步调用。JS 传 (method, paramsJson)，本对象 post 回主线程后走与
 *   iframe 通道完全相同的分发逻辑；结果仍经 window.__bridgeResult(callbackId, result)
 *   回调，因此插件侧 Promise API 完全不变。
 * - [callSync]：同步调用（桥线程执行，立即返回 JSON 字符串），仅用于 KV 存储等
 *   无 UI 的线程安全操作，支撑 Bridge.getDataSync/setDataSync。
 *
 * 注意：本类方法名被 @JavascriptInterface 暴露给 JS，release 混淆需保留
 * （已在 proguard-rules.pro 添加 keep 规则）。
 */
class NativePluginBridge(
    private val webView: WebView,
    private val asyncDispatch: (webView: WebView, method: String, params: Map<String, String>) -> Unit,
    private val syncDispatch: (method: String, params: Map<String, String>) -> String
) {
    companion object {
        private const val TAG = "NativePluginBridge"
    }

    @JavascriptInterface
    fun call(method: String, paramsJson: String) {
        val params = parseParams(paramsJson)
        webView.post {
            try {
                asyncDispatch(webView, method, params)
            } catch (e: Exception) {
                Log.e(TAG, "Native bridge async dispatch failed: method=$method", e)
                val callbackId = params["callbackId"] ?: ""
                if (callbackId.isNotEmpty()) {
                    val errMsg = e.message?.replace("\\", "\\\\")?.replace("'", "\\'") ?: "Unknown error"
                    webView.evaluateJavascript(
                        "window.__bridgeResult('$callbackId', {success:false,error:'$errMsg'});", null
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun callSync(method: String, paramsJson: String): String {
        return try {
            syncDispatch(method, parseParams(paramsJson))
        } catch (e: Exception) {
            Log.e(TAG, "Native bridge sync dispatch failed: method=$method", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"") ?: "Unknown error"}"}"""
        }
    }

    private fun parseParams(paramsJson: String): Map<String, String> {
        return try {
            val obj = org.json.JSONObject(paramsJson.ifBlank { "{}" })
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.opt(key)
                map[key] = if (value == null || value == org.json.JSONObject.NULL) "" else value.toString()
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse native bridge params: ${e.message}")
            emptyMap()
        }
    }
}

private const val bridgeJavascript = """
(function() {
    if (window.Bridge) return;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    // ===== 插件环境 v2：传输层 =====
    // 优先走原生通道（window.NativePluginBridge，无 URL 长度上限、无 iframe 开销）；
    // 原生通道不可用或抛错时自动回落旧 iframe 通道——旧插件零改动，行为下限不变。
    function hasNativeBridge() {
        try {
            return !!(window.NativePluginBridge && window.NativePluginBridge.call);
        } catch (e) { return false; }
    }

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;

            if (hasNativeBridge()) {
                try {
                    var payload = {};
                    if (params) {
                        for (var nk in params) {
                            if (params.hasOwnProperty(nk)) payload[nk] = String(params[nk]);
                        }
                    }
                    payload.callbackId = callbackId;
                    payload.__transport = 'native';
                    window.NativePluginBridge.call(method, JSON.stringify(payload));
                    return;
                } catch (e) {
                    console.warn('Native bridge call failed, falling back to iframe transport:', e);
                }
            }

            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }

            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() {
                document.body.removeChild(iframe);
            }, 100);
        });
    }

    window.Bridge = {
        getPluginConfig: function() {
            return bridgeCall('getPluginConfig', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        deleteData: function(key) {
            return bridgeCall('deleteData', {key: key});
        },
        listData: function() {
            return bridgeCall('listData', {});
        },
        pickImage: function() {
            return bridgeCall('pickImage', {});
        },
        pickFile: function() {
            return bridgeCall('pickFile', {});
        },
        pickBinaryFile: function() {
            return bridgeCall('pickBinaryFile', {});
        },
        importAudioFile: function() {
            return bridgeCall('importAudioFile', {});
        },
        callTool: function(toolName, params) {
            return bridgeCall('callTool', {toolName: toolName, params: params || '{}'});
        },
        writeFile: function(fileName, base64Data) {
            return bridgeCall('writeFile', {fileName: fileName, data: base64Data});
        },
        saveFileAs: function(fileName, base64Data) {
            return bridgeCall('saveFileAs', {fileName: fileName, data: base64Data});
        },
        readFile: function(fileName) {
            return bridgeCall('readFile', {fileName: fileName});
        },
        listFiles: function(dirPath) {
            return bridgeCall('listFiles', {dir: dirPath || ''});
        },
        deleteFile: function(fileName) {
            return bridgeCall('deleteFile', {fileName: fileName});
        },
        close: function() {
            bridgeCall('close', {});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        },
        notifyHook: function(hookName, context) {
            return bridgeCall('notifyHook', {hookName: hookName, context: context || '{}'});
        },
        startTimer: function(seconds) {
            return bridgeCall('startTimer', {seconds: seconds});
        },
        stopTimer: function() {
            return bridgeCall('stopTimer', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        showOverlay: function(html) {
            return bridgeCall('showOverlay', {html: html});
        },
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        musicPlay: function(filePath, title, artist) {
            return bridgeCall('musicPlay', {filePath: filePath, title: title || '', artist: artist || ''});
        },
        musicPause: function() {
            return bridgeCall('musicPause', {});
        },
        musicResume: function() {
            return bridgeCall('musicResume', {});
        },
        musicStop: function() {
            return bridgeCall('musicStop', {});
        },
        musicSeek: function(position) {
            return bridgeCall('musicSeek', {position: position});
        },
        musicGetProgress: function() {
            return bridgeCall('musicGetProgress', {});
        },

        // ===== 插件环境 v2 新增 API =====

        // B1：环境能力探测。返回 {envVersion, appVersion, pluginId, pluginName,
        // transport, capabilities[]}——新插件建议开局先探测，按能力降级
        getEnvInfo: function() {
            return bridgeCall('getEnvInfo', {});
        },

        // B3：原生 Toast 轻反馈
        showToast: function(message) {
            return bridgeCall('showToast', {message: String(message == null ? '' : message)});
        },

        // B4：订阅 App → UI 事件（如 'timerEnd' / 'musicCompleted'）。
        // 旧的 window.onTimerEnd / window.onMusicCompleted 继续有效，二选一即可
        on: function(event, handler) {
            if (!event || typeof handler !== 'function') return;
            if (!window.__bridgeEventHandlers) window.__bridgeEventHandlers = {};
            if (!window.__bridgeEventHandlers[event]) window.__bridgeEventHandlers[event] = [];
            window.__bridgeEventHandlers[event].push(handler);
        },

        // B2：同步 KV 读写（仅原生通道可用；旧环境调用会抛错，
        // 建议先 getEnvInfo 探测 capabilities 含 'sync_storage' 再用）。
        // 返回值/参数语义与 getData/setData 一致，但立即完成，无 Promise
        getDataSync: function(key) {
            if (!hasNativeBridge() || !window.NativePluginBridge.callSync) {
                throw new Error('Bridge.getDataSync requires plugin env 2.0 native bridge');
            }
            var r = JSON.parse(window.NativePluginBridge.callSync('getData', JSON.stringify({key: key})));
            if (!r.success) throw new Error(r.error || 'getDataSync failed');
            return (r.value === undefined || r.value === null) ? null : r.value;
        },
        setDataSync: function(key, value) {
            if (!hasNativeBridge() || !window.NativePluginBridge.callSync) {
                throw new Error('Bridge.setDataSync requires plugin env 2.0 native bridge');
            }
            var r = JSON.parse(window.NativePluginBridge.callSync('setData', JSON.stringify({key: key, value: String(value)})));
            if (!r.success) throw new Error(r.error || 'setDataSync failed');
            return true;
        },
        deleteDataSync: function(key) {
            if (!hasNativeBridge() || !window.NativePluginBridge.callSync) {
                throw new Error('Bridge.deleteDataSync requires plugin env 2.0 native bridge');
            }
            var r = JSON.parse(window.NativePluginBridge.callSync('deleteData', JSON.stringify({key: key})));
            return r.success === true;
        }
    };

    // B4：通用事件分发入口（由 App 侧 evaluateJavascript 调用）
    if (!window.__bridgeEventHandlers) window.__bridgeEventHandlers = {};
    window.__bridgeEvent = function(name, dataJson) {
        var list = window.__bridgeEventHandlers[name];
        if (!list || !list.length) return;
        var data = null;
        try { data = dataJson ? JSON.parse(dataJson) : null; } catch (e) {}
        for (var i = 0; i < list.length; i++) {
            try { list[i](data); } catch (e) { console.error('Bridge event handler error (' + name + '):', e); }
        }
    };

    window.onTimerEnd = window.onTimerEnd || function() {};
    window.onMusicCompleted = window.onMusicCompleted || function() {};

    console.log('Bridge API initialized (env ' + (hasNativeBridge() ? '2.0 native' : '1.0 iframe') + ')');
})();
"""

private const val overlayBridgeJavascript = """
(function() {
    if (window.__overlayBridgeReady) return;
    window.__overlayBridgeReady = true;

    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;

    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };

    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }
            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() { document.body.removeChild(iframe); }, 100);
        });
    }

    window.Bridge = {
        hideOverlay: function() {
            return bridgeCall('hideOverlay', {});
        },
        getTimerState: function() {
            return bridgeCall('getTimerState', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        callAI: function(prompt, context) {
            return bridgeCall('callAI', {prompt: prompt, context: context || '{}'});
        }
    };

    window.onTimerEnd = function() {};

    console.log('Overlay Bridge API initialized');
})();
"""

private fun uriToBase64(context: android.content.Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return "null"
    val bytes = inputStream.readBytes()
    inputStream.close()
    return "\"" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\""
}