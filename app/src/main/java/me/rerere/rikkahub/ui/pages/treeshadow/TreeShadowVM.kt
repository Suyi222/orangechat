/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.treeshadow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.TreeShadowEntry
import me.rerere.rikkahub.data.service.TreeShadowService

/**
 * 「树影下」页面 ViewModel
 * - 今天：状态卡 + 时间线 + 回声（含绑定回声与自由回声）
 * - 往日：已归档日期列表，点开看完整记录
 */
class TreeShadowVM(
    private val treeShadowService: TreeShadowService,
) : ViewModel() {

    private val _stateCard = MutableStateFlow<TreeShadowEntry?>(null)
    val stateCard: StateFlow<TreeShadowEntry?> = _stateCard.asStateFlow()

    private val _timeline = MutableStateFlow<List<TreeShadowEntry>>(emptyList())
    val timeline: StateFlow<List<TreeShadowEntry>> = _timeline.asStateFlow()

    private val _echoes = MutableStateFlow<List<TreeShadowEntry>>(emptyList())
    val echoes: StateFlow<List<TreeShadowEntry>> = _echoes.asStateFlow()

    private val _archivedDates = MutableStateFlow<List<String>>(emptyList())
    val archivedDates: StateFlow<List<String>> = _archivedDates.asStateFlow()

    private val _selectedDate = MutableStateFlow<String?>(null)
    val selectedDate: StateFlow<String?> = _selectedDate.asStateFlow()

    private val _archivedDay = MutableStateFlow<List<TreeShadowEntry>>(emptyList())
    val archivedDay: StateFlow<List<TreeShadowEntry>> = _archivedDay.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _today = MutableStateFlow(TreeShadowService.today())
    val today: StateFlow<String> = _today.asStateFlow()

    init {
        refreshToday()
        loadArchivedDates()
    }

    /** 刷新「今天」全部数据 */
    fun refreshToday() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _today.value = TreeShadowService.today()
                _stateCard.value = treeShadowService.getActiveStateCard(_today.value)
                _timeline.value = treeShadowService.getActiveTimeline(_today.value)
                _echoes.value = treeShadowService.getActiveEchoes(_today.value)
            } finally {
                _loading.value = false
            }
        }
    }

    /** 自由回声栏：「今天我想说」 */
    fun addFreeEcho(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            treeShadowService.addFreeEcho(_today.value, content.trim())
            refreshToday()
        }
    }

    /** 绑定回声：在某条时间线下写一句感受 */
    fun addBoundEcho(timelineId: Int, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            treeShadowService.addBoundEcho(_today.value, timelineId, content.trim())
            refreshToday()
        }
    }

    /** 手动归档今天（决策 13 保险二） */
    fun archiveToday() {
        viewModelScope.launch {
            treeShadowService.archive(_today.value)
            refreshToday()
            loadArchivedDates()
        }
    }

    /** 加载已归档日期列表 */
    fun loadArchivedDates() {
        viewModelScope.launch {
            _archivedDates.value = treeShadowService.getArchivedDates()
        }
    }

    /** 选中某归档日期，加载完整记录 */
    fun selectArchivedDate(date: String) {
        viewModelScope.launch {
            _selectedDate.value = date
            _archivedDay.value = treeShadowService.getDay(date)
        }
    }
}
