/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup.tabs

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.data.sync.webdav.WorkspaceManifestEntry
import me.rerere.rikkahub.data.sync.webdav.WorkspaceRestoreTarget
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // 件④ G1（2.4.5）：工作区恢复面板状态——备份 zip 含 workspaces/ 且设备已有工作区时弹出
    var workspaceRestoreState by remember {
        mutableStateOf<WorkspaceRestoreUiState?>(null)
    }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入，cherry 为 Cherry Studio 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件
                    val exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // 清理临时文件
                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "local" -> {
                            // 本地备份导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 件④ G1（2.4.5）：备份含工作区时走恢复面板（勾选/自动下载），
                            // 否则保持旧语义直接恢复（聊天+设置）
                            val manifest = vm.inspectLocalBackup(tempFile)
                            if (manifest.isNullOrEmpty()) {
                                vm.restoreFromLocalFile(tempFile)
                                // 清理临时文件
                                tempFile.delete()
                            } else {
                                val existingRoots = vm.existingWorkspaces()
                                if (existingRoots.isEmpty()) {
                                    // 全新安装：默认全选自动重建（沿用备份 id，assistant.workspaceId 自动对齐）
                                    val plan = vm.buildWorkspacePlan(
                                        manifest = manifest,
                                        selectedRoots = manifest.map { it.root }.toSet(),
                                        existingRoots = existingRoots,
                                    )
                                    vm.restoreFromLocalFile(tempFile, plan)
                                    tempFile.delete()
                                    toaster.show("已恢复聊天、设置与全部工作区文件。rootfs 需重新下载：到 工作区 详情页重装", type = ToastType.Success)
                                    onShowRestartDialog()
                                } else {
                                    // 弹恢复面板：逐工作区勾选（默认全选）+ 目标说明 + 可勾自动下载 rootfs
                                    isRestoring = false
                                    workspaceRestoreState = WorkspaceRestoreUiState(
                                        file = tempFile,
                                        manifest = manifest,
                                        existingRoots = existingRoots,
                                    )
                                    return@launch
                                }
                            }
                        }

                        "chatbox" -> {
                            // Chatbox导入：处理json文件
                            val tempFile =
                                File(context.cacheDir, "temp_chatbox_${System.currentTimeMillis()}.json")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Chatbox文件恢复
                            vm.restoreFromChatBox(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }

                        "cherry" -> {
                            // Cherry Studio导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_cherry_${System.currentTimeMillis()}.zip")

                            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                FileOutputStream(tempFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            // 从Cherry Studio备份恢复
                            vm.restoreFromCherryStudio(tempFile)

                            // 清理临时文件
                            tempFile.delete()
                        }
                    }

                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                    onShowRestartDialog()
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "local"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_import)) },
                    supportingContent = {
                        Text(
                            if (isRestoring) {
                                stringResource(R.string.backup_page_importing)
                            } else {
                                stringResource(R.string.backup_page_import_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "chatbox"
                            openDocumentLauncher.launch(arrayOf("application/json"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_chatbox)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_chatbox_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "chatbox") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )

                item(
                    onClick = if (!isRestoring) {
                        {
                            importType = "cherry"
                            openDocumentLauncher.launch(arrayOf("application/zip"))
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_import_from_cherry_studio)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_import_cherry_studio_desc)) },
                    leadingContent = {
                        if (isRestoring && importType == "cherry") {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.FileImport, null)
                        }
                    },
                )
            }
        }
    }

    // 件④ G1（2.4.5）：工作区恢复面板（备份含工作区且设备已有工作区时弹出）
    workspaceRestoreState?.let { wsState ->
        WorkspaceRestorePanel(
            state = wsState,
            existingSelection = null,
            onConfirm = { plan, autoInstall ->
                workspaceRestoreState = null
                isRestoring = true
                scope.launch {
                    runCatching {
                        vm.restoreFromLocalFile(wsState.file, plan)
                        if (autoInstall) vm.autoInstallRootfs(plan)
                    }.onSuccess {
                        wsState.file.delete()
                        toaster.show(
                            if (autoInstall) "恢复完成，rootfs 正在后台下载" else "恢复完成。rootfs 需重新下载：到 工作区 详情页重装",
                            type = ToastType.Success,
                        )
                        onShowRestartDialog()
                    }.onFailure { e ->
                        e.printStackTrace()
                        wsState.file.delete()
                        toaster.show(
                            context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                            type = ToastType.Error,
                        )
                    }
                    isRestoring = false
                }
            },
            onDismiss = { wsState.file.delete(); workspaceRestoreState = null },
        )
    }
}

/**
 * 件④ G1（2.4.5）：工作区恢复面板状态容器。
 */
private data class WorkspaceRestoreUiState(
    val file: File,
    val manifest: List<WorkspaceManifestEntry>,
    val existingRoots: List<String>,
)

/**
 * 件④ G1（2.4.5）工作区恢复面板（她的三点要求）：
 * 逐工作区勾选（默认全选）+ 目标说明（按 root 匹配现有 → 覆盖合并；无匹配 → 新建）
 * + 完成后可选自动下载 rootfs。恢复前 WebDavSync 已对覆盖目标打快照，写失败自动回滚。
 */
@Composable
private fun WorkspaceRestorePanel(
    state: WorkspaceRestoreUiState,
    existingSelection: Set<String>? = null,
    onConfirm: (List<WorkspaceRestoreTarget>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(state) {
        mutableStateMapOf<String, Boolean>().apply {
            state.manifest.forEach { put(it.root, true) }
            existingSelection?.forEach { put(it, true) }
        }
    }
    var autoInstallRootfs by remember(state) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复工作区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "备份里包含工作区文件。选择要恢复的工作区：",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.manifest.forEach { entry ->
                    val isMerge = entry.root in state.existingRoots
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected[entry.root] = !(selected[entry.root] ?: true) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected[entry.root] ?: true,
                            onCheckedChange = { selected[entry.root] = it },
                        )
                        Column {
                            Text(
                                entry.name.ifBlank { entry.root },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (isMerge) "覆盖合并到现有工作区（恢复前自动打快照）" else "作为新工作区重建",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = autoInstallRootfs,
                        onCheckedChange = { autoInstallRootfs = it },
                    )
                    Text(
                        "恢复后自动下载 rootfs（Linux 环境，较大）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "提示：rootfs 不在备份里，恢复后需要重新下载；不勾选可稍后在 工作区 详情页重装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (plan, auto) = buildWorkspacePlan(
                    state = state,
                    selectedRoots = selected.filterValues { it }.keys,
                    autoInstall = autoInstallRootfs,
                )
                onConfirm(plan, auto)
            }) { Text("恢复") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun buildWorkspacePlan(
    state: WorkspaceRestoreUiState,
    selectedRoots: Set<String>,
    autoInstall: Boolean,
): Pair<List<WorkspaceRestoreTarget>, Boolean> {
    val targets = state.manifest
        .filter { it.root in selectedRoots }
        .map { entry ->
            WorkspaceRestoreTarget(
                srcRoot = entry.root,
                targetRoot = entry.root,
                createRecord = if (entry.root in state.existingRoots) null else entry,
            )
        }
    return targets to autoInstall
}
