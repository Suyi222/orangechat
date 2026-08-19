# 隙光 OrangeChat · 2.4.1 更新说明（插件环境 v2）

> 日期：2026-08-19 ｜ 分支：master ｜ versionCode：163
> 本版为插件环境专项小版本：**插件 UI 环境全面增强（env v2）**。聊天/工作流/主动唤醒等工具行为与 2.4.0 完全一致，QuickJS 沙箱与工具注册链路零改动，**现有插件零改动直接受益**。

---

## 🔧 A · 插件环境加固（治「盲盒」）

1. **JS 日志面**：插件 UI 的 `console.log/warn/error` 进 logcat（TAG=`PluginWebConsole`），资源加载失败（onReceivedError/onReceivedHttpError）也有日志——「静默失败无痕迹」时代结束
2. **远程调试**：debug 包开启 `WebContentsDebugging`，电脑 Chrome `chrome://inspect` 直接调试插件页面（release 自动关闭）
3. **Bridge v2 原生通道**：桥调用改走 `addJavascriptInterface` 注入的 `NativePluginBridge` 原生接口直连——**无 URL 长度上限**（此前 iframe URL 传参超限导致 setData 静默失败），无 iframe 创建/销毁开销。注入脚本自动探测原生通道，异常自动回落旧 iframe 通道，行为下限 = 2.4.0
4. **渲染兜底**：manifest 可声明 `uiOptions.softwareRender`（软件渲染，治「数据到了画面不刷新」机型问题）+ 页面加载完成后主动重绘

## 🚀 B · 插件环境新能力（Bridge API 加法）

| 新 API | 说明 |
|:---|:---|
| `Bridge.getEnvInfo()` | 返回 envVersion/appVersion/pluginId/capabilities[]/transport —— 插件可探测环境能力做降级 |
| `Bridge.getDataSync/setDataSync/deleteDataSync` | 同步 KV 读写（仅原生通道可用）——专治「把 Promise 当同步用」的误用 |
| `Bridge.showToast(msg)` | 原生 Toast 轻反馈 |
| `Bridge.on(event, handler)` | App→UI 通用事件通道（timerEnd/musicCompleted 已接入；旧 onTimerEnd/onMusicCompleted 写法仍兼容） |

体验增强：插件页字号跟随系统缩放（textZoom 80%~200%）、宽视口、输入法弹起页面自适应（adjustResize）。

## 📜 C · 环境契约

- envVersion = `"2.0"`（`getEnvInfo` 可查）
- manifest 新增可选 `minEnvVersion`：环境过旧时插件详情页给出提示（不拦截加载；建议插件 UI 内优先用 `getEnvInfo()` 探测能力自行降级）

## 🛡️ 兼容性说明（重要）

- **所有现有插件零改动**：Bridge 是 App 注入的，函数签名不变；升级后自动走新通道，失败回落旧通道
- **QuickJS 沙箱侧完全没动**：manifest tools / main.js / `plg_*` 工具名、dataStore/fetch/memoryBank 全部不变
- ProGuard 已加 `NativePluginBridge` @JavascriptInterface keep 规则（release minify 不影响桥）

---

## 📝 配套（非 App 本体）

- 算卦小屋插件 v1.1：修复「历史记录丢失」（UI 误把 Promise 返回的 Bridge.getData 当同步用）+ 打开页面回显当日最近一卦
- 橘瓣插件仓库《插件使用指南》更新到环境 v2（新增 Bridge API 全表与踩坑写法）

---

## 📦 安装

正式版包名：`xiguang.orangechat`（与 2.4.0 一致，覆盖安装数据无损）

```
adb install -r app-arm64-v8a-release.apk
```

---

## 🔭 已知遗留

- 🔴 主动消息不触发（8.13 反馈）：静态审计未见 2.4 回归点，需真机 logcat 定位
- ⚡ 工作流唤醒卡顿：backlog，需真机性能数据
