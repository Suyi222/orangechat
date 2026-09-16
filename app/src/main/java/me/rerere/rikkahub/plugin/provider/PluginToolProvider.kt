/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.provider

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginToolDefinition

private const val TAG = "PluginToolProvider"

/**
 * 插件工具提供者
 * 将插件工具转换为AI可用的Tool对象
 */
class PluginToolProvider(
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取所有插件提供的工具
     * 会等待插件初始化完成，确保竞态条件下不会返回空列表
     */
    suspend fun getTools(): List<Tool> {
        // 等待插件初始化完成，避免竞态条件导致工具列表为空
        pluginManager.awaitInitialization()
        return pluginLoader.getAllLoadedPlugins().flatMap { plugin ->
            plugin.info.manifest.tools.map { toolDef ->
                createTool(plugin, toolDef)
            }
        }
    }

    /**
     * 获取指定插件的工具
     */
    suspend fun getPluginTools(pluginId: String): List<Tool> {
        pluginManager.awaitInitialization()
        val plugin = pluginLoader.getLoadedPlugin(pluginId) ?: return emptyList()
        return plugin.info.manifest.tools.map { toolDef ->
            createTool(plugin, toolDef)
        }
    }

    /**
     * 创建Tool对象
     */
    private fun createTool(plugin: LoadedPlugin, toolDef: PluginToolDefinition): Tool {
        return Tool(
            name = ToolNaming.buildPluginToolName(plugin.id, toolDef.name),
            description = buildDescription(plugin, toolDef),
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildParameters(toolDef),
                    required = toolDef.parameters.filter { it.required }.map { it.name }
                )
            },
            execute = { params ->
                executeTool(plugin, toolDef, params)
            }
        )
    }

    /**
     * 构建工具描述
     */
    private fun buildDescription(plugin: LoadedPlugin, toolDef: PluginToolDefinition): String {
        val sb = StringBuilder()
        sb.appendLine(toolDef.description)
        sb.appendLine()
        sb.appendLine("Provided by plugin: ${plugin.info.manifest.name} (${plugin.info.manifest.id})")
        return sb.toString().trim()
    }

    /**
     * 构建参数定义
     */
    private fun buildParameters(toolDef: PluginToolDefinition): JsonObject {
        return buildJsonObject {
            toolDef.parameters.forEach { param ->
                put(param.name, buildJsonObject {
                    put("type", param.type)
                    if (param.description != null) {
                        put("description", param.description)
                    }
                    // 根据类型添加额外信息
                    when (param.type) {
                        "array" -> {
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        }
                        "object" -> {
                            // 可以在这里添加properties定义
                        }
                    }
                })
            }
        }
    }

    /**
     * 执行工具
     */
    private suspend fun executeTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        params: JsonElement
    ): List<UIMessagePart> {
        var result = pluginLoader.callTool(
            pluginId = plugin.id,
            toolName = toolDef.name,
            params = params
        )

        // 2.4.6 H6：调用 miss 时自愈一次。
        // 内存压力下 QuickJS 加载失败 / 沙箱异常会让「已注册的插件缺员」，旧行为直接把
        // "Tool not found" 抛回给模型，模型对用户转述成「插件不存在」——不可见也不可恢复。
        // 这里卸载重载后再试一次（只一次，防循环）。
        if (result.isFailure && needsSelfHeal(result)) {
            val reason = result.exceptionOrNull()?.message ?: "unknown"
            Log.w(TAG, "tool call miss, self-heal reload once: plugin=${plugin.id}, tool=${toolDef.name}, reason=$reason")
            val reloaded = try {
                pluginLoader.unloadPlugin(plugin.id)
                pluginLoader.loadPlugin(plugin.info)
            } catch (e: Exception) {
                Log.e(TAG, "self-heal reload failed: plugin=${plugin.id}", e)
                Result.failure(e)
            }
            if (reloaded.isSuccess) {
                result = pluginLoader.callTool(
                    pluginId = plugin.id,
                    toolName = toolDef.name,
                    params = params
                )
                if (result.isSuccess) {
                    Log.i(TAG, "self-heal succeeded: plugin=${plugin.id}, tool=${toolDef.name}")
                }
            }
        }

        return result.fold(
            onSuccess = { jsonElement ->
                val resultStr = json.encodeToString(JsonElement.serializer(), jsonElement)
                listOf(UIMessagePart.Text(resultStr))
            },
            onFailure = { error ->
                val errorObj = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Unknown error")
                }
                listOf(UIMessagePart.Text(errorObj.toString()))
            }
        )
    }

    /**
     * 哪些失败值得「重载一次再试」：插件未加载（Plugin not loaded）或工具缺员（Tool not found），
     * 二者都是内存压力下 QuickJS 加载失败族的表象。参数错误、沙箱业务异常等不在此列，
     * 避免把真正的调用错误也重试一遍。
     */
    private fun needsSelfHeal(result: Result<JsonElement>): Boolean {
        val message = result.exceptionOrNull()?.message ?: return false
        return message.startsWith("Tool not found") || message.startsWith("Plugin not loaded")
    }

    /**
     * 获取插件的提示词注入
     *
     * 第一个元素: 自动生成的"插件能力总览"(当存在声明了工具的插件时),
     *             让模型不仅"看见"插件工具, 还被明确提醒要主动使用.
     * 后续元素: 各插件 manifest.promptTemplate 中开启了 inject_as_prompt 的模板(保留原机制).
     */
    suspend fun getPluginPromptInjections(): List<String> {
        // 等待插件初始化完成，避免竞态条件
        pluginManager.awaitInitialization()

        val pluginsWithTools = pluginLoader.getAllLoadedPlugins()
            .filter { it.info.manifest.tools.isNotEmpty() }

        // 只保留"主动性引导"这句话 + 插件名字列表,
        // 不再逐条重复每个工具的 name/description——完整的工具定义
        // (含 name/description/参数 schema) 已独立存在于发给模型的 tools 参数里,
        // 在这里重复列一遍是纯粹的体积浪费。
        val overview = if (pluginsWithTools.isNotEmpty()) {
            val pluginNames = pluginsWithTools.joinToString("、") { it.info.manifest.name }
            "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：${pluginNames}。" +
                "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
                "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。"
        } else {
            null
        }

        val manualTemplates = pluginLoader.getAllLoadedPlugins().mapNotNull { plugin ->
            val manifest = plugin.info.manifest
            val promptTemplate = manifest.promptTemplate ?: return@mapNotNull null
            // 检查 inject_as_prompt 配置是否开启
            val injectConfig = plugin.info.config["inject_as_prompt"]
            val shouldInject = when (injectConfig) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    injectConfig.content == "true"
                }
                else -> false
            }
            if (shouldInject) promptTemplate else null
        }

        return buildList {
            if (overview != null) add(overview)
            addAll(manualTemplates)
        }
    }

    /**
     * 获取工具统计信息
     */
    fun getToolStats(): ToolStats {
        val plugins = pluginLoader.getAllLoadedPlugins()
        val totalTools = plugins.sumOf { it.info.manifest.tools.size }

        return ToolStats(
            totalPlugins = plugins.size,
            totalTools = totalTools,
            pluginDetails = plugins.map { plugin ->
                PluginToolDetail(
                    pluginId = plugin.id,
                    pluginName = plugin.info.manifest.name,
                    toolCount = plugin.info.manifest.tools.size,
                    toolNames = plugin.info.manifest.tools.map { it.name }
                )
            }
        )
    }

    /**
     * 工具统计信息
     */
    data class ToolStats(
        val totalPlugins: Int,
        val totalTools: Int,
        val pluginDetails: List<PluginToolDetail>
    )

    /**
     * 插件工具详情
     */
    data class PluginToolDetail(
        val pluginId: String,
        val pluginName: String,
        val toolCount: Int,
        val toolNames: List<String>
    )
}