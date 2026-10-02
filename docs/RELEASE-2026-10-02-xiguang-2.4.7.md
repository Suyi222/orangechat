# 隙光 OrangeChat · 2.4.7 更新说明（OOM 根治·第二刀 ＋ 安心包 · 国庆批）

> 日期：2026-10-02（傍晚场）｜ 分支：master ｜ versionCode：**172** ｜ 定位：**第二刀砍掉请求路径的四块大分配（D1-D4）＋ 安心包三件（E1 全角清污 v2 / E2 晨信双缺口补偿 / E3 静默看门狗）**
> 施工依据：[返修版任务队列](../../tree-plan/projects/04-orangechat-frontend/docs/update-plan-2026-09-16-xiguang-返修版任务队列.md) §「▶2.4.7 国庆批」
> ⚠️ **真机验收延后**（她明示：测试往后挪）。能自证的都已在 JVM 里自证完：**7/7 单测绿**（D2 字节 diff 2 + E1 对拍 5）。验收清单在下方「装机验收（延后）」，随时可照着跑。

## 🔥 这版在治什么

- **OOM 第二刀**：9-27 崩溃取证 = **13-15MB 连续 char[]** 大块分配（`StringFactory.newStringFromChars ← Json.encodeToString ← ChatCompletionsAPI.streamText`）。512MB 堆（largeHeap）下，大窗口一次发送就要一整块 13-15MB 连续内存 ×（序列化 String + toByteArray byte[]）**双份**——碎片化后直接 `OutOfMemoryError: Failed to allocate a 15204368 byte allocation`。第一刀（2.4.6.4）砍掉了日志囤积的「整会话副本」，这一刀砍的是**请求路径本身的大块分配**。
- **安心包**：L1 全角特殊 token 穿透（9-18 实锤 `<｜begin_of_sentence｜>` 上屏入库）、L2 晨信双缺口（9-26 网络错误静默吞信 / 9-20 claim 失败静默丢信）、L10 静默流占 claim（9-20 15:05 案：网关黑洞 43min 零内容）。
- 范围铁律全程遵守：**只动内存/储存/触发层**，LLM 响应解析（SpecialTokenFilter/StreamTokenBuffer/delta 路径）与请求组装（prompt/tools/history/transformers）零改动；「组装语义不变」用 JVM 字节 diff 单测自证。

## ✨ 本版内容（七件 · 每件独立 commit 可单独回滚）

### D1 删除 release 日志里的重复整请求体序列化

`ChatCompletionsAPI.generateText/streamText` 开头各有一行 `Log.i(TAG, json.encodeToString(requestBody))`——release 包 proguard **没有** Log 剥离规则，这行照跑：7-8MB 请求体 = 每次发送**额外**一块 13-15MB char[]。改成轻量摘要（model/消息数/工具数），零请求体引用。

### D2 请求体流式序列化（本刀主件）

新增 `StreamingJsonRequestBody`（ai 模块）：`contentLength()` 用**两段计数**（第一遍 `encodeToStream` 进只计数不落地的 OutputStream），`writeTo()` 用 `encodeToStream` **直写 okio sink**——全程不物化 String、不物化 byte[]，13-15MB 连续大块从根上消失。

- **对计划的有意偏离**：计划书首选 chunked（`contentLength()=-1`）。实装选了**两段计数**形态：线上字节与旧实现**逐字节一致**（含 Content-Length 头），对两平台网关**零风险**；chunked 需要真机对两平台各验一轮（验收延后 + 考前最后一个施工窗口 ⇒ 选零风险形态）。chunked 仍作为已论证的备选记录在 KDoc。
- **自证**：`StreamingJsonRequestBodyTest` 2/2 绿——含 CJK/emoji/转义/嵌套/base64/长文本的 payload，`writeTo` 输出与 `encodeToString().toByteArray()` **字节级相等**，contentLength 精确，可重放（isOneShot=false），Content-Type 不变。

### D3 请求体体积守卫

新增 `RequestSizeEstimator`（ai 模块）：发送前廉价估算（逐字符精确 UTF-8 长度、base64≈字符数、file:// 图片按磁盘字节×4/3 上界、tools schema 按名缓存）。三处可见：① 发送日志摘要带 `estBody≈X`，**>4MB 打 warn（只告警不截断**——截断=改请求语义，铁律禁动）；② AILogging 摘要行/开发者页卡片显示「请求体约 X MB」；③ 与落盘 `req_*.json` 对账 = 水位取证（第三刀「图片减负」是否立项的证据）。

- 对计划偏离：估算用**精确 UTF-8 计数**替代草案「长度×3」（×3 对 ASCII 高估 3 倍，达不成「误差<10%」验收口径）。
- 已知口径差：file:// 图片按磁盘×4/3 是**上界**——实际发送走 `compressAndEncode`（quality 85）重压缩，大图估算会偏大；纯文本大会话（水位取证主场景）不受影响。

### D4 请求体落盘改流式

`AILogging.dumpFullRequest` 的 pretty String 物化（16MB 级）改 `encodeToStream` 直写文件流。落盘取证（2.4.6.4 件③）从此可以放心开半天——这正是「水位取证」的前置。

### E1 全角特殊 token 清污 v2（L1-T2 · 纯储存层）

9-18 实锤：模型偶发把特殊 token 吐成**全角变体**（全角竖线 ｜ U+FF5C、全角尖括号 ＜＞ U+FF1C/FF1E），半角写死的四层防线全部穿透 → 脏数据入库 → 随请求回灌 → 模型模仿再吐 → 雪球。

- 新增 `StorageTokenSanitizer`（ai 模块，纯 JVM）：宽体 regex（尖括号/竖线各自允许半全角混搭）+ L1-ext 跨流后半形态（`|xxx|>` **仅锚定文本开头**，防误伤 markdown 表格）+ 代码块/行内代码保护（与解析层同语义）。
- **半角行为与 `SpecialTokenFilter.sanitizeFinal` 逐字一致**——`StorageTokenSanitizerTest` 5/5 绿（对拍 + 宽体剥离 + 开头残段 + 代码保护 + 快检一致性）。解析层 `SpecialTokenFilter` **零改动**（T1 铁律）。
- 落库兜底（ConversationRepository 保存路径）换宽体：新脏数据不再进库；一次性存量清污 v2：DAO 扫描 SQL 加宽（全角混搭 LIKE 组 + JSON 键前缀锚定开头残段），SP 标记**版本化 schema=2**（老 boolean true 折算 v1，升级自动重扫一次）。L6 抽查随案闭环。
- **已知残留**（记录在案）：流式上屏瞬间的显示仍走解析层原防线——全角形态当轮渲染中可能短暂可见，重进会话（从库加载）即消失。根治在 T1（解析层加宽），待批。

### E2 晨信双缺口补偿（L2）

- **A 网络缺口**（9-26 案：凌晨 doze 断网，IOException 静默吞掉本轮晨信）：网络类失败（IOException 家族，cause 链 ≤6 层判定）→ **15-30min 后补偿重试 ≤2 次**；设备事件源除外（上下文过时，补偿出去是没头没尾的消息）。
- **B claim 缺口**（9-20 案：会话被正常聊天/树影占用，晨信无声消失）：claim 失败 → **30-60s 后重选重试 ≤2 次**（重试走全流程 = 天然重选最新会话；30-60s 足够短，各触发源一视同仁）。计划未定次数，取 2（与 A 对齐）。
- 机制：`AlarmManager.setAndAllowWhileIdle`（doze 可唤醒、不要求 exact-alarm 权限）→ Receiver（**已修**：extras 原样转发，旧实现裸 intent 会把重试计数丢光）→ 重走全流程。重试 intent 带 force（绕过 min-interval——时间戳首触发已写）+ 独立 requestCode 10002/10003（PendingIntent 身份=requestCode+filterEquals，与定时链 10001 互不覆盖/误伤）。
- 全程 trace 可验证：`entry ... compRetry=N claimRetry=M`、`comp-retry(net:Xxx/stall): scheduled/exhausted/skipped`、`claim-retry: scheduled/exhausted`。

### E3 静默看门狗（L10）

9-20 15:05 案：网关黑洞只吐 keep-alive 空帧，okhttp readTimeout(10min) 被空帧骗过，**43min 零内容占着 claim**，后续晨信/设备事件全部 claim failed 而死。

- `generateWithTools` 的 `.collect{}` 改 `produceIn` + deadline 循环：**90s 零「内容增量」**即抛 `ProactiveStallException`。判据识破空帧——只有真实增量（文本/思考/工具/finishReason）顺延 deadline，keep-alive 空帧不顺延；思考模型的 reasoning 包算内容，不会误杀。
- 触发即 trace `FAIL: stall (pre-first-packet|mid-stream zero content for 90s) step=N conversationId=...`；上游异常从 closed channel 原样重抛（网络类判定/用户取消语义与旧 collect 一致）；finally 掐掉上游流（okhttp 连接归还）；claim 经外层 finally **即时释放**。
- stall 的恢复走 E2 补偿重试同机制（`comp-retry(stall)`），设备事件源照旧除外。

## 📱 装机验收（延后 · 她说测就测，照单跑）

| # | 项 | 动作 | 期望 |
|:---:|:---|:---|:---|
| 1 | 第一刀补票+压测 | 装 vc172（覆盖 vc171，数据零丢失），开 5093 条大窗口连发 5 条 | 峰值仍在、**收尾回落基线**，无 +29MB 台阶（vc172 同时复验 vc171）；可选连发 20 条 → <300MB 无 OOM |
| 2 | 第二刀曲线 | 同口径再采样；两平台各发一轮（DeepSeek 官方 + token-plan chat/completions，都走 ChatCompletionsAPI） | 峰值下降（无 13-15MB char[] 分配）；两平台首轮响应正常（D2 保 Content-Length，线上字节与旧版逐字节一致，理论零风险） |
| 3 | 安心包 | 挂 3 天看 proactive_trace.log | 全角 token 不再入库/不再上屏（**残留**：流式瞬间可能短暂可见，重进会话即消失）；晨信网络/claim 失败后能看到 comp-retry/claim-retry 行；stall 触发能看到 FAIL: stall 行且后续触发不再被 claim 卡死 |
| 4 | D3 读数 | 开发者页开「请求体落盘」，对比卡片「请求体约 X MB」与 `ai_request_dump/req_*.json` 实际大小 | 误差 <10%（纯文本会话；带 file:// 图片的会话估算偏大属口径内，见 D3 已知口径差） |
| 5 | 水位取证 | 落盘开半天 → 取 req_*.json 大小分布 + meminfo | 定第三刀「图片减负」是否立项（D4 后落盘本身不再吃内存，可放心开） |

## ⚠️ 已知未修（不在这批里）

- **L11 后台工具面缺 workflow_***：她的裁决「拿不准，待 trace 对质」，本批不动（`localTools.getTools(...)` 单参调用原样保留）；
- **T1 解析层加宽**：E1 的根治位（流式上屏瞬间的全角显示残留），范围铁律禁动，待批；
- **B4 热窗口/session 治理**、**图片缓存时效 P1**：仍在 beta.2 排期；
- **网关测试本体**（两平台请求体/回复结构对比）：D3/D4 基建就位，测试本身随验收延后。

## 📦 交付

- **编译**：`:app:assembleRelease --offline` ✅ BUILD SUCCESSFUL in 6m39s（404 tasks，R8 + lintVital 全过）
- **产物**：`app-universal-release.apk`（57.0MB，sha256 `03020EAE71810B03A2FD36EA9FE23771E2ECD9B9C91CB23FD56851C766A16B56`），桌面副本 `隙光2.4.7-OOM第二刀安心包-20261002.apk`
- **自证**：ai 模块 JVM 单测 7/7 绿（StreamingJsonRequestBodyTest 2 + StorageTokenSanitizerTest 5）；`:ai:compileReleaseKotlin` `:app:compileReleaseKotlin` 通过；组装语义不变 = D2 字节 diff 单测；半角行为不变 = E1 对拍单测
- **爆炸半径**：D1-D4 只在 ChatCompletionsAPI/AILogging（请求线上字节不变）；E1 只在储存层（解析层零改动）；E2/E3 只在主动消息链路（正常聊天生成路径零改动）
- **回滚**：七件各一个 commit（D1 `59d32672` / D4 `6c9c65f7` / D2 `09599554` / D3 `eb1fcec9` / E1 `caa10c42` / E2 `558fc549` / E3 `467849f2`），单件 `git revert` 即可

---

*🌲 第二刀砍在请求路径的大块分配上，安心包把三个「无声失败」全部变成「有声可查」。考前最后一个施工窗口，此后原则上冻结。*
