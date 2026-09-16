# 隙光 OrangeChat · 2.4.6 更新说明（止血 hotfix 批）

> 日期：2026-09-16 ｜ 分支：master ｜ versionCode：168 ｜ 定位：2.4.6 止血（2.5.0 前置）
> 施工依据：[返修版任务队列](../../tree-plan/projects/04-orangechat-frontend/docs/update-plan-2026-09-16-xiguang-返修版任务队列.md) §三 · 2.4.6 止血批（H1~H6）
> 本版只做六件「小改、确定性高」的止血；**内存层根治（热窗口/单行落库/onTrimMemory）在 2.5.0-beta.2**。

## 🔥 这版在治什么

2.4.5 半月实测爆发 OOM 连锁（主动消息消失 / 插件调用失败 / 树影下总结断档 / 相机不可用）。逐条核查后确认：

- 数据库层（`message_node` 表 + 分页读 + diff 写）**早已拆好**，卡的是**内存对象层**——为读一个时间戳或一个标题，把整个会话的全部消息节点加载进堆；
- `KeepAliveService` 是全 App 唯一 dataSync 前台服务，撞上 Android 14+ 的 6h/24h 配额被系统处决（9.10~9.11 断档时间线吻合）。

这版先砍掉最肥的两处内存热点（H3/H4）+ 堵住被处决的服务（H2）+ 让三处静默失败可见（H1/H5/H6）。

## ✨ 本版内容（六件）

### 件①（H1）模型特殊 token 清洗 —— 正文不再出现 `<|begin_of_sentence|>`

- **现象**：工具调用后的续写新开 stream 时，模型把控制 token 原样带进正文（9-15 set_timer、9-16 calendar_tool 两次复现）。
- **修法**：新增 `SpecialTokenFilter`（ai 模块）——
  - 每次清洗「**累积全文**」而非只看当前 delta → 跨 chunk 被切开的半截 token（`<|begin_` + `of_sentence|>`）在拼上后一并清掉；
  - ``` 围栏与行内 `code` 内的 `<|x|>` 字样保留（防误伤示例代码）；
  - 不含 `<|` 时一次 indexOf 短路返回，零额外开销。
- **接入点**：流式 `appendChunk`（实时清洗）+ 落库前兜底（`insertConversation` / `updateConversation`，覆盖非流式等其它写入路径）。
- 验收：复现两个历史场景无泄露；含 `<|x|>` 的代码块不被误删。

### 件②（H2）保活前台服务救火：dataSync → specialUse

- **现象**：`ForegroundServiceDidNotStopInTimeException`，进程被系统处决 → 总结 Worker / 主动消息调度一起停摆。
- **修法**：`KeepAliveService` 前台服务类型改 `specialUse`（与本 App 其余 11 个服务一致，Manifest 补 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）+ 新增 `onTimeout` 优雅退出路径（把「进程被杀」降级为「服务主动停止」，不连坐其它服务）。
- **保活能力不减反增**：现状 dataSync 会被 6h/24h 配额处决，改型后躲开配额。通知、开关行为全部不变。
- 验收：不再出现该崩溃；保活开关与通知照旧。

### 件③（H3）Recent Chats 轻量化 —— 头号内存热点

- **现象**：`buildRecentChatsPrompt` 每次 AI 生成都加载最近 10 个会话的**全部消息节点**，却只用 title + 更新日期。
- **修法**：新增 `getRecentConversationSummaries`（DAO 只取轻列、零节点加载），提示词拼接改走它。**提示词内容逐字不变**。
- 验收：5500 条窗口连发 10 条消息，Profiler 堆峰值对比记入施工记录。

### 件④（H4）最后消息时间轻量化 —— 去掉「双重全量加载」

- **现象**：主动消息查岗 / 工作流条件评估 / Bot 消息，为读一个时间戳（甚至只要一个会话 id）全量加载整会话；`ProactiveMessageService` 更是一次触发连加载两遍。
- **修法**：新增 DAO 尾查询（`ORDER BY node_index DESC LIMIT 1` + `json_extract` 直接取字段，**不把 messages blob 读进堆**）+ `getRecentConversationId`；替换五处调用（Proactive ×2 / ContextProvider / 微信 Bot / QQ Bot）。
- 保留一处全量加载：主动消息触发要把完整会话塞进 session 防流式覆盖历史，这是必要的；去掉的只是重复的那一遍。
- 验收：工作流条件评估 / 主动消息判定不再全量加载；时间戳与旧逻辑抽样对拍一致。

### 件⑤（H5）总结失败可见 + 指数退避

- **现象**：总结失败只留 `Log.w`（用户不可见）；失败后 baseCount 不推进 → 每 15 分钟原样重试 → 每次全量加载，反而加剧内存压力。
- **修法**：失败按 15 → 30 → 60 → 120 → 240 分钟阶梯退避（封顶 4 小时）；失败原因写本地日志文件（超 256KB 自动保留后半段）；连续失败 ≥3 次在树影下时间线插一条系统提示；成功即清零退避。
- **新增导出入口**：设置 → 系统工具 → 「导出自动总结日志」，把日志文件直接分享出来定位。
- 验收：断网造失败 → 时间线可见提示、不再 15 分钟连环重试、日志可导出。

### 件⑥（H6）插件调用自愈 + 加载失败可见

- **现象**：内存压力下 QuickJS 加载失败 → 已注册插件「缺员」→ 调用直接抛 `Tool not found`，模型对用户转述成「插件不存在」，不可见也不可恢复。
- **修法**：调用 miss（`Tool not found` / `Plugin not loaded`）时对该插件**重载一次再试**（只一次，防循环）；插件列表页加载失败加**红点**（详情页/列表页红字文案原已就位）。
- 验收：手动破坏一个插件目录 → 调用报错并触发一次自愈；恢复目录后自愈成功；红点可见。

## 📦 安装

```
adb install -r app-arm64-v8a-release.apk
```

桌面交付：`隙光-2.4.6-arm64-release.apk`（覆盖 2.4.5 数据无损）

## ✅ 装机验收（关键五条）

1. 大窗口（5500 条）连发消息不 OOM；记录 `dumpsys meminfo` 峰值
2. 工具调用后的续写不再出现 `<|...|>`；含 `<|x|>` 的代码块正常显示
3. 后台保活 24h 不再出 `ForegroundServiceDidNotStopInTimeException`
4. 断网 → 时间线出现「自动总结连续失败」提示；设置页可导出日志
5. 插件加载失败 → 列表红点；恢复目录后调用自愈成功

##  已知边界

- 本版是**止血**不是根治：热窗口（最近 1000 条）/ 单行落库 / onTrimMemory 在 2.5.0-beta.2（整批验收：5500 条窗口峰值 < 300MB）。
- H7 主动消息取证：装机后复现「消失」时导出 ProactiveTrace 日志，用来给 beta.2 的主动消息根治确诊。
