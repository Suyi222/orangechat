/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.webdav.WorkspaceManifestEntry
import me.rerere.rikkahub.data.sync.webdav.WorkspaceRestoreTarget
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

/** 件④ G1（2.4.5）：恢复后自动下载 rootfs 的默认镜像（与 RootfsInstallUrlDialog 默认值一致） */
private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    // 件④ G1（2.4.5）：恢复面板用的工作区数据源（Koin lazy 取，不改构造器）
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? by lazy {
        runCatching { org.koin.core.context.GlobalContext.get()
            .get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
    }
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(settings.value.webDavConfig.copy())
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File, workspacePlan: List<WorkspaceRestoreTarget> = emptyList()) {
        webDavSync.restoreFromLocalFile(file, settings.value.webDavConfig, workspacePlan)
    }

    /** 件④ G1（2.4.5）：读本地备份 zip 里的工作区清单（无 workspaces/ → null） */
    suspend fun inspectLocalBackup(file: File): List<WorkspaceManifestEntry>? =
        webDavSync.inspectWorkspaceManifest(file)

    /** 件④ G1（2.4.5）：设备上现有工作区记录（数据库恢复过就有） */
    suspend fun existingWorkspaces(): List<String> =
        workspaceRepository?.listFlow()?.first()?.map { it.root }.orEmpty()

    /**
     * 件④ G1（2.4.5）：构建恢复计划。
     * 勾选的备份工作区按 root 匹配现有 → 覆盖合并（只写文件）；无匹配 → 新建（沿用备份 id/name，
     * assistant.workspaceId 关联随 settings.json 自动对齐）。未勾选的不进 plan（zip 循环里跳过）。
     */
    fun buildWorkspacePlan(
        manifest: List<WorkspaceManifestEntry>,
        selectedRoots: Set<String>,
        existingRoots: Collection<String>,
    ): List<WorkspaceRestoreTarget> =
        manifest.filter { it.root in selectedRoots }.map { entry ->
            WorkspaceRestoreTarget(
                srcRoot = entry.root,
                targetRoot = entry.root,
                createRecord = if (entry.root in existingRoots) null else entry,
            )
        }

    /**
     * 件④ G1（2.4.5）：恢复完成后可选自动下载 rootfs（面板勾选）。
     * 逐个目标工作区后台安装，用 RootfsInstallUrlDialog 同款默认镜像；失败只记日志不阻断。
     */
    fun autoInstallRootfs(targets: List<WorkspaceRestoreTarget>) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { target ->
                runCatching {
                    workspaceRepository?.installRootfs(
                        target.targetRoot,
                        DEFAULT_ROOTFS_URL,
                    ) { }
                }.onFailure { Log.w(TAG, "autoInstallRootfs failed for root=${target.targetRoot}", it) }
            }
        }
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        val payload = ChatboxImporter.import(
            file = file,
            assistantId = settings.value.assistantId,
            providers = settings.value.providers,
        )
        val targetAssistantId = settings.value.assistantId
        val mergedProviders = payload.providers + settings.value.providers
        val shouldAllowConversationSystemPrompt = payload.conversations.conversations.any {
            !it.customSystemPrompt.isNullOrBlank()
        }
        settingsStore.update(
            settings.value.copy(
                providers = mergedProviders,
                assistants = settings.value.assistants.map { assistant ->
                    if (shouldAllowConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        )

        var importedConversations = 0
        var skippedExistingConversations = 0
        payload.conversations.conversations.forEach { conversation ->
            if (conversationRepository.existsConversationById(conversation.id)) {
                skippedExistingConversations++
            } else {
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${payload.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${payload.conversations.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = payload.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = payload.conversations.skippedImageParts,
            skippedEmptyMessages = payload.conversations.skippedEmptyMessages,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)
