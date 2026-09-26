/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.developer

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileScript
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AILogging
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 1 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Developer Page",
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = "Developer")
                    },
                    icon = {
                        Icon(HugeIcons.FileScript, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val dumpEnabled by vm.dumpEnabled.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 2.4.6.4 第一刀③：请求体按需落盘开关（网关取证用；开=每次生成异步写完整请求体到文件，密钥脱敏）
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "请求体落盘（网关取证）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "打开后每次生成把完整请求体异步写入（密钥脱敏、默认关，用完即关）：\n${vm.dumpPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = dumpEnabled,
                        onCheckedChange = { vm.setDumpEnabled(it) },
                    )
                }
            }
        }
        items(logs) { log ->
            when (log) {
                is AILogging.Generation -> {
                    // 2.4.6.4：日志卡片展示瘦身摘要（旧实现此卡是空壳，且背后每条日志囤一整份会话副本 → OOM 真凶）
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = log.modelName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                            )
                            Text(
                                text = buildString {
                                    append(logTimeFormat.format(Date(log.timestamp)))
                                    append(" · ")
                                    append(if (log.stream) "stream" else "non-stream")
                                    append(" · 消息 ")
                                    append(log.messageCount)
                                    append(" · 工具 ")
                                    append(log.toolNames.size)
                                    append(" · 驻留 ~")
                                    append(log.estimatedBytes / 1024)
                                    append("KB")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            log.tailDigests.forEach { digest ->
                                Text(
                                    text = buildString {
                                        append("[")
                                        append(digest.role)
                                        append("] ")
                                        append(digest.textPreview.ifBlank { "（无文本）" })
                                        if (digest.attachments.isNotEmpty()) {
                                            append("  {")
                                            append(digest.attachments.joinToString(" | "))
                                            append("}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
