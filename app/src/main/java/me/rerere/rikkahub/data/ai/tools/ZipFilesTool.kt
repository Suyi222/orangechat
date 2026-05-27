package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * 构建 write_files 工具
 * AI 可以直接将文件内容打包成 ZIP 供用户下载
 * 不再依赖从消息代码块中提取内容
 */
fun buildWriteFilesTool(): Tool = Tool(
    name = "write_files",
    description = """
        Package files into a ZIP archive for the user to download. The file contents are passed directly in the tool parameters.

        IMPORTANT: Always use actual filenames as code block language tags. For example:
        - Use ```MainActivity.kt instead of ```kotlin
        - Use ```index.html instead of ```html
        - Use ```styles.css instead of ```css
        - Use ```app.js instead of ```javascript
        If the file is in a subdirectory, include the path: ```src/main/java/com/example/App.kt

        Example:
        {"zip_name":"my-project.zip","files":[{"name":"MainActivity.kt","content":"package com.example..."},{"name":"index.html","content":"<html>..."}]}
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("files", buildJsonObject {
                    put("type", "array")
                    put("description", "List of files to package. Each file must have a 'name' (filename with extension, can include subdirectory path) and 'content' (the file content as a string).")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Filename with extension, e.g. 'MainActivity.kt', 'index.html'. Can include subdirectory path like 'src/main/App.kt'.")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "The full content of the file as a string.")
                            })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("name"))
                            add(JsonPrimitive("content"))
                        })
                    })
                })
                put("zip_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the ZIP archive (must end with .zip). Choose a meaningful name like 'my-project.zip'.")
                })
            },
            required = listOf("files", "zip_name")
        )
    },
    execute = {
        val params = it.jsonObject
        val files = params["files"]?.jsonArray
            ?: error("files is required")

        if (files.isEmpty()) {
            error("files list cannot be empty")
        }

        val zipName = params["zip_name"]?.jsonPrimitive?.contentOrNull
            ?: error("zip_name is required")

        if (!zipName.endsWith(".zip")) {
            error("zip_name must end with .zip")
        }

        val fileList = files.map { fileElement ->
            val obj = fileElement.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: error("each file must have a 'name' field")
            val content = obj["content"]?.jsonPrimitive?.contentOrNull
                ?: error("each file must have a 'content' field")
            if (name.isBlank()) error("file name cannot be empty")
            mapOf("name" to name, "content" to content)
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("zip_name", zipName)
                    put("files", buildJsonArray {
                        fileList.forEach { file ->
                            add(buildJsonObject {
                                put("name", file["name"]!!)
                                put("size", file["content"]!!.length)
                            })
                        }
                    })
                    put("total_files", fileList.size)
                    put("message", "ZIP package '$zipName' is ready with ${fileList.size} file(s). A download button will appear for the user.")
                }.toString()
            )
        )
    }
)

// Keep backward compatibility alias
@Deprecated("Use buildWriteFilesTool instead", ReplaceWith("buildWriteFilesTool()"))
fun buildZipFilesTool(): Tool = buildWriteFilesTool()