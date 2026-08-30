# 隙光 OrangeChat · 2.4.5 更新说明（稳定性 + 数据安全批）

> 日期：2026-08-30~31 ｜ 分支：master ｜ versionCode：167 ｜ 定位：2.5.0-beta.1
> 施工依据：[2.4.5 任务书](../../tree-plan/projects/04-orangechat-frontend/docs/update-plan-2026-08-30-xiguang-2.4.5-任务书.md)
> 把她这两天反馈的全部「体感坏了」治掉 + 数据安全补全。**不含**拆表 / Mini Apps / 同步（9 月分批）。

## ✨ 本版内容（六件）

### 件① 问题A迁移：老助手自动补 🌳树影下
- **现状**：老助手升级后 localTools 缺 TreeShadow → 不注入状态/没有记录工具/不自动总结，且零提示。
- **修法**：一次性标记位迁移（`treeshadow_migrated`）——升级后遍历助手自动补开；**标记存在后永不再动**（用户此后手动关闭的尊重）；系统工具页展示一次「已为你的助手自动开启树影下」。
- 验收：旧数据升级后树影下已开；手动关闭再重启不会被再次强制打开。

### 件② 问题B到点即结：闲置到点自动总结，不用回来说话
- **现状**：总结检查只在发送链——不发消息永不总结。
- **修法**（双保险）：退后台（ON_STOP）即查 + 新增 15 分钟周期 Worker（`TreeShadowIdleWorker`）兜底；共享 `checkIdleTriggerAndSummarize`（含闸门/防重入/baseCount 幂等）。
- 验收：阈值 1 分钟 → 发一条 → 退后台 2 分钟 → 时间线出总结，全程没发第二条消息。

### 件③ 晨信双信根治（三小件）
1. **计数语义对齐**：`claimFire` 拆 `checkCap`（触发时只读）+ `markFired`（真实执行后 +1）——SKIPPED_* 不再吃随机触发器配额，与引擎层语义一致。
2. **重启判重**：进程重启后今日已真实 fire 过的随机工作流直接排明日窗口（晨信双信链全断）。
3. **RUNNING 先行落盘**：fire 入口预插 RUNNING 行、终态原地 update——崩溃后 run 历史可见残迹。

### 件④ G1 备份扩展（数据安全）
- 备份 zip 新增：`plugin_data.json`（树邮局信件/算卦记录等插件态）+ `workspaces/manifest.json` + `workspaces/<root>/...`（工作区文件区，天然排除 rootfs）。
- 恢复：插件数据原样写回；工作区恢复面板——逐工作区勾选（默认全选）+ 目标说明（覆盖合并/新建）+ 可勾恢复后自动下载 rootfs；覆盖合并前自动打快照，写失败回滚；全新安装默认全选自动重建（assistant.workspaceId 自动对齐）。

### 件⑤ E1 插件页渲染进程恢复
- vivo 每 ~2 分钟杀 WebView 渲染进程 → 白屏。补 `onRenderProcessGone`：销毁旧 WebView → 重建并恢复插件页；插件内部状态靠 env v2 同步 KV 自恢复。

### 件⑥ 小件
- **F2**：树影下已归档 Tab 顶部月份跳转 chips（点击展开该月并定位）——B4.2 了结，不做拖动滑条。
- **F3**：`annual_ring` 本地工具（记录者=树时可亲手写年轮）+ 系统提示触发提示；见证门槛判定不变。
- **C**：工作流卡顿基线脚本（`tree-plan/projects/02-tree-tools/scripts/xiguang-workflow-baseline.sh`），装机后跑出耗时分布。

## 📦 安装

```
adb install -r app-arm64-v8a-release.apk
```
桌面交付：`隙光-2.4.5-arm64-release.apk`（覆盖 2.4.4 数据无损；首次启动触发件①迁移）

## ✅ 验收

详见 [beta.1 自测清单](../../tree-plan/projects/04-orangechat-frontend/docs/xiguang-2.4.5-beta1-自测清单.md)。关键四条：
1. 旧助手不手动开 → 树影下已自动开启 + 见过一次提示
2. 阈值 1 分钟 → 发一条 → 退后台 → 2 分钟后时间线出总结（没发第二条消息）
3. 随机工作流触发后强杀重启 → 当日不再二次触发；run 历史有 RUNNING 残迹
4. 备份 → 卸载重装 → 恢复 → 插件数据 + 年轮/self.md 全回来；工作区可勾选/跳过

## 🚧 已知边界

- 主动消息不触发（B）随真机日志 9 月初修；A 拆表 9/1~9/2（beta.2）；F4 同步 9/3~9/4（beta.3）。
- 云端（WebDAV/S3）恢复路径工作区条目暂按跳过处理（面板挂本地导入入口）；工作区记录靠数据库恢复对齐。
