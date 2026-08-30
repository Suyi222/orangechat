/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.treeshadow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkahub.data.db.entity.TreeShadowEntry
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「树影下」页面（决策 12）
 * - 今天标签：状态卡 + 时间线 + 回声 + 自由回声栏
 * - 往日标签：按日期的归档列表，点开看完整记录
 * - 视觉风格：暖、树影、光斑（暖色容器卡片）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeShadowPage(
    onBack: () -> Unit,
) {
    val vm: TreeShadowVM = koinViewModel()
    val stateCard by vm.stateCard.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    val echoes by vm.echoes.collectAsStateWithLifecycle()
    val archivedDates by vm.archivedDates.collectAsStateWithLifecycle()
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    val archivedDay by vm.archivedDay.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) }
    // 绑定回声目标（点时间线记录 -> 写一句感受）
    var echoTarget by remember { mutableStateOf<TreeShadowEntry?>(null) }
    // 编辑 / 删除目标（B4.3 小园丁手动改）
    var editTarget by remember { mutableStateOf<TreeShadowEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<TreeShadowEntry?>(null) }
    // 往日日期详情
    var archivedDetailDate by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshToday()
        vm.loadArchivedDates()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("树影下") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = { vm.archiveToday() }) {
                        Text("归档今天")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("今天") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("往日") },
                )
            }

            when (selectedTab) {
                0 -> TodayTab(
                    stateCard = stateCard,
                    timeline = timeline,
                    echoes = echoes,
                    onTimelineClick = { echoTarget = it },
                    onEdit = { editTarget = it },
                    onDelete = { deleteTarget = it },
                    onSendFreeEcho = { vm.addFreeEcho(it) },
                )

                1 -> ArchivedTab(
                    archivedDates = archivedDates,
                    onDateClick = {
                        archivedDetailDate = it
                        vm.selectArchivedDate(it)
                    },
                )
            }
        }
    }

    // 绑定回声对话框
    echoTarget?.let { target ->
        BoundEchoDialog(
            target = target,
            onDismiss = { echoTarget = null },
            onSubmit = { content ->
                vm.addBoundEcho(target.id, content)
                echoTarget = null
            }
        )
    }

    // 编辑记录对话框（B4.3）
    editTarget?.let { target ->
        EditEntryDialog(
            entry = target,
            onDismiss = { editTarget = null },
            onSave = { content ->
                vm.updateEntry(target.id, content)
                editTarget = null
            }
        )
    }

    // 删除确认对话框（B4.3）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条记录？") },
            text = {
                Text("「${target.content.take(40)}${if (target.content.length > 40) "…" else ""}」将永久删除，其下绑定的感受也会一并删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteEntry(target.id)
                    deleteTarget = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 往日详情对话框
    archivedDetailDate?.let { date ->
        val dayEntries = if (date == selectedDate) archivedDay else emptyList()
        ArchivedDayDialog(
            date = date,
            entries = dayEntries,
            onDismiss = { archivedDetailDate = null },
        )
    }
}

@Composable
private fun TodayTab(
    stateCard: TreeShadowEntry?,
    timeline: List<TreeShadowEntry>,
    echoes: List<TreeShadowEntry>,
    onTimelineClick: (TreeShadowEntry) -> Unit,
    onEdit: (TreeShadowEntry) -> Unit,
    onDelete: (TreeShadowEntry) -> Unit,
    onSendFreeEcho: (String) -> Unit,
) {
    val freeEchoes = echoes.filter { it.parentId == null }
    val boundEchoes = echoes.filter { it.parentId != null }
    var echoInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 状态卡
        if (stateCard != null) {
            item {
                StateCardView(stateCard)
            }
            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        // 时间线
        item {
            SectionTitle("时间线")
        }
        if (timeline.isEmpty()) {
            item {
                EmptyHint("今天还没有时间线记录，AI 会在聊完后帮你记下重要的事。")
            }
        }
        items(timeline, key = { it.id }) { entry ->
            TimelineCard(
                entry = entry,
                boundEchoes = boundEchoes.filter { it.parentId == entry.id },
                onClick = { onTimelineClick(entry) },
                onEdit = { onEdit(entry) },
                onDelete = { onDelete(entry) },
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        // 自由回声栏
        item {
            SectionTitle("今天我想说")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = echoInput,
                    onValueChange = { echoInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("留下一句想说的话…") },
                    maxLines = 3,
                )
                OutlinedButton(
                    onClick = {
                        if (echoInput.isNotBlank()) {
                            onSendFreeEcho(echoInput)
                            echoInput = ""
                        }
                    },
                    enabled = echoInput.isNotBlank(),
                ) {
                    Icon(HugeIcons.PlusSign, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("发送")
                }
            }
        }

        if (freeEchoes.isEmpty()) {
            item {
                EmptyHint("这里的留言会被 AI 在聊天时悄悄翻看。")
            }
        }
        items(freeEchoes, key = { it.id }) { echo ->
            EchoCard(echo)
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun ArchivedTab(
    archivedDates: List<String>,
    onDateClick: (String) -> Unit,
) {
    // B4.4 按月自动分组：YYYY-MM → List<date>（时间倒序）
    val months = remember(archivedDates) {
        archivedDates.groupBy { it.take(7) }
    }
    // 月份折叠状态：默认全部折叠，点月份展开看日期
    val expandedMonths = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // B4.2 了结（2.4.5 F2）：月份跳转 chips——长列表快速定位，不做拖动滑条。
    fun scrollToMonth(month: String) {
        expandedMonths[month] = true
        // 目标 item 序号 = 标题(1) + 之前各月 (月份行 1 + 已展开的日期数)
        var index = 1
        for ((m, dates) in months) {
            if (m == month) break
            index += 1 + (if (expandedMonths[m] == true) dates.size else 0)
        }
        scope.launch { listState.animateScrollToItem(index) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle("已归档的日子")
        }
        if (archivedDates.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    months.keys.forEach { month ->
                        FilterChip(
                            selected = false,
                            onClick = { scrollToMonth(month) },
                            label = { Text(month) },
                        )
                    }
                }
            }
        }
        if (archivedDates.isEmpty()) {
            item {
                EmptyHint("还没有归档的日子。点右上角「归档今天」或让 AI 道晚安时归档。")
            }
        } else {
            months.forEach { (month, dates) ->
                item(key = "month_$month") {
                    Surface(
                        onClick = { expandedMonths[month] = !(expandedMonths[month] ?: false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                HugeIcons.Book01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = month,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "${dates.size} 天",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                if (expandedMonths[month] == true) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (expandedMonths[month] == true) {
                    items(dates, key = { "date_$it" }) { date ->
                        Surface(
                            onClick = { onDateClick(date) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = date.takeLast(5),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "查看",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 状态卡（素描散文 + 可选备注） */
@Composable
private fun StateCardView(entry: TreeShadowEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    HugeIcons.InLove,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "状态卡 · ${entry.dateGroup}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyLarge,
            )
            // 备注（AI 可选项，有内容才显示）
            entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = note,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** 时间线记录 + 嵌套的绑定回声（B4.1 展开/收起，B4.3 编辑/删除） */
@Composable
private fun TimelineCard(
    entry: TreeShadowEntry,
    boundEchoes: List<TreeShadowEntry>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    HugeIcons.Clock02,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTime(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                // 编辑 / 删除（小园丁手动改）
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Edit01, contentDescription = "编辑", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(HugeIcons.Delete01, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
            // 展开/收起（B4.1，内容被截断时才显示）
            if (entry.content.length > 100) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (expanded) "收起" else "展开全文",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = onClick) {
                        Text(
                            text = "写一句感受",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClick) {
                        Text(
                            text = "写一句感受",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            boundEchoes.forEach { echo ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            HugeIcons.InLove,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Column {
                            Text(
                                text = formatTime(echo.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = echo.content,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 自由回声卡片 */
@Composable
private fun EchoCard(echo: TreeShadowEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                HugeIcons.InLove,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Column {
                Text(
                    text = formatTime(echo.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = echo.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 绑定回声输入对话框 */
@Composable
private fun BoundEchoDialog(
    target: TreeShadowEntry,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写一句感受") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "「${target.content.take(40)}${if (target.content.length > 40) "…" else ""}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("此刻的心情…") },
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(content) },
                enabled = content.isNotBlank(),
            ) {
                Text("写下")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 编辑记录对话框（B4.3 小园丁手动改） */
@Composable
private fun EditEntryDialog(
    entry: TreeShadowEntry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    val typeLabel = when (entry.type) {
        TreeShadowEntry.STATE_CARD -> "状态卡"
        TreeShadowEntry.TIMELINE -> "时间线"
        TreeShadowEntry.ECHO_BOUND -> "绑定回声"
        else -> "回声"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑$typeLabel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${entry.dateGroup} · ${formatTime(entry.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(content) },
                enabled = content.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 往日日期详情对话框（状态卡 + 时间线 + 回声完整保留） */
@Composable
private fun ArchivedDayDialog(
    date: String,
    entries: List<TreeShadowEntry>,
    onDismiss: () -> Unit,
) {
    val stateCard = entries.firstOrNull { it.type == TreeShadowEntry.STATE_CARD }
    val timeline = entries.filter { it.type == TreeShadowEntry.TIMELINE }
    val echoes = entries.filter { it.type == TreeShadowEntry.ECHO_BOUND || it.type == TreeShadowEntry.ECHO_FREE }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date) },
        text = {
            Column(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (stateCard != null) {
                        item {
                            Text(
                                text = stateCard.content,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            stateCard.note?.takeIf { it.isNotBlank() }?.let { note ->
                                Text(
                                    text = note,
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    timeline.forEach { entry ->
                        item {
                            Column {
                                Text(
                                    text = "${formatTime(entry.createdAt)} ${entry.content}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                echoes.filter { it.parentId == entry.id }.forEach { echo ->
                                    Text(
                                        text = "  ↳ ${echo.content}",
                                        modifier = Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    echoes.filter { it.parentId == null }.forEach { echo ->
                        item {
                            Text(
                                text = "${formatTime(echo.createdAt)} ${echo.content}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                text = "暂无记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
