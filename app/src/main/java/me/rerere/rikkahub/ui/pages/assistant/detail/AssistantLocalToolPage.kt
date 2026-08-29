/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.TreeHeartService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("主动语音通话") },
                supportingContent = { Text("允许 AI 在合适时机主动发起语音通话, 弹出来电界面邀请你接听。开启后 AI 可能会在觉得语音更合适时打来电话") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.RequestVoiceCall),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.RequestVoiceCall, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("日历读写") },
                supportingContent = { Text("允许AI读取、创建和删除日历事件，需要日历权限") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            item(
                headlineContent = { Text("允许跳过回复") },
                supportingContent = { Text("允许AI在认为无需回复时跳过，回复 [SKIP] 的消息将被隐藏") },
                trailingContent = {
                    Switch(
                        checked = assistant.allowSkipReply,
                        onCheckedChange = {
                            val newLocalTools = if (it) {
                                assistant.localTools + LocalToolOption.AllowSkipReply
                            } else {
                                assistant.localTools - LocalToolOption.AllowSkipReply
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools, allowSkipReply = it))
                        }
                    )
                }
            )
            item(
                headlineContent = { Text("工作流") },
                supportingContent = { Text("开启后 AI 可创建事件驱动的自动化工作流（触发器+条件->动作）") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Workflows),
                        onCheckedChange = {
                            val newLocalTools = if (it) {
                                assistant.localTools + LocalToolOption.Workflows
                            } else {
                                assistant.localTools - LocalToolOption.Workflows
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )
            item(
                headlineContent = { Text("🌳 树影下") },
                supportingContent = { Text("开启后 AI 会持续维护你的状态卡（含备注）与时间线，聊天时自动注入上下文，并可改/删/读往日记录") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TreeShadow),
                        onCheckedChange = {
                            val newLocalTools = if (it) {
                                assistant.localTools + LocalToolOption.TreeShadow
                            } else {
                                assistant.localTools - LocalToolOption.TreeShadow
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )
            item(
                headlineContent = { Text("屏幕自动化") },
                supportingContent = { Text("点击/滑动/输入/读取界面元素/截图。需在系统设置->无障碍中启用橘瓣") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenAutomation),
                        onCheckedChange = {
                            val newLocalTools = if (it) {
                                assistant.localTools + LocalToolOption.ScreenAutomation
                            } else {
                                assistant.localTools - LocalToolOption.ScreenAutomation
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )
            item(
                headlineContent = { Text("SSH 远程连接") },
                supportingContent = { Text("远程执行命令、SFTP 上传下载、保存主机凭据") },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Ssh),
                        onCheckedChange = {
                            val newLocalTools = if (it) {
                                assistant.localTools + LocalToolOption.Ssh
                            } else {
                                assistant.localTools - LocalToolOption.Ssh
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )
        }

        // 🌲 树的自我（C 模块：指针开关 + 自定义指针 + 同步到云端）
        TreeHeartCard(
            assistant = assistant,
            onUpdate = onUpdate,
        )
    }
}

@Composable
private fun TreeHeartCard(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeHeartService = koinInject<TreeHeartService>()

    CardGroup(title = { Text("🌲 树的自我") }) {
        item(
            headlineContent = { Text("启用树的自我") },
            supportingContent = { Text("开启后，每次会话开场会注入【身份】与【当下自我】（读本助手工作区 tree_heart/self/树的自我指针.md + 最近年轮）。关闭则完全不注入") },
            trailingContent = {
                Switch(
                    checked = assistant.treeHeartEnabled,
                    onCheckedChange = { enabled -> onUpdate(assistant.copy(treeHeartEnabled = enabled)) }
                )
            }
        )
        item(
            headlineContent = { Text("自定义自我指针") },
            supportingContent = {
                // M3 ListItem 槽位只排版单一根节点：Text 与输入框必须包进同一个 Column（2.4.4 修——此前输入框被静默丢弃，只见开关）
                Column {
                    Text("留空 = 读工作区 self.md「注入文本」段的一行指针；本地也没有 = 用默认指针。可在工作区用 workspace 工具直接改 self.md（2.4.3 起只取一行，见证列表不再注入）")
                    OutlinedTextField(
                        value = assistant.treeHeartPointer,
                        onValueChange = { text -> onUpdate(assistant.copy(treeHeartPointer = text)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("例如：我是你的 AI 伙伴，一棵陪你慢慢长大的树。") },
                        maxLines = 3,
                        shape = MaterialTheme.shapes.small,
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    )
                }
            }
        )
        item(
            headlineContent = { Text("导出 self") },
            supportingContent = { Text("把本地 self.md 内容导出（分享到云端备份/笔记）。主要树建议定期导出做永久备份，临时助手可只留本地") },
            trailingContent = {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            // 2.4.3：导出读全文（档案含见证列表）；注入另有行提取，互不影响
                            val self = treeHeartService.readLocalSelfFull(assistant.workspaceId)
                                ?: "（本地工作区还没有 self.md，树暂时使用默认指针）\n\n可在工作区创建 tree_heart/self/树的自我指针.md"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, self)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, "导出 self 备份")) }
                        }
                    },
                    enabled = assistant.workspaceId != null,
                ) {
                    Text("导出")
                }
            }
        )
    }
}
