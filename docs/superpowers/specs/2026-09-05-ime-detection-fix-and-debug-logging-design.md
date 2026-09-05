# 设计：IME 可见性检测修复 + 调试文件日志

日期：2026-09-05
状态：已与用户确认（范围：IME 修复 + 方案 A 文件日志；日志默认关）

## 背景与问题

用户开启「仅在桌面显示」（`onlyOnHome`）后：

1. **键盘场景（经常复现）**：输入法打开时侧边栏把手仍显示，挡住键盘右侧导致无法打字。
2. **误触发（低概率）**：偶尔在其他应用上也能触发把手/面板。

根因分析（含实机实证，荣耀 BKQ-AN10 / MagicOS）：

- `PanelAccessibilityService.checkImeVisibilityFromEvent` 判定 IME 可见只靠 `event.className.contains("InputMethod")`。实机验证用户全部输入法的服务组件类名均不含该串：
  - 微信输入法 `com.tencent.wetype/.plugin.hld.WxHldService`
  - 豆包输入法 `com.bytedance.android.doubaoime/.ImeService`
  - 百度荣耀版 `com.baidu.input_hihonor/com.baidu.input_honor.ImeService`
  - 另有系统安全键盘 `com.hihonor.secime`（密码框场景）
  → `isImeVisible` 永远 false → `addEdgeHandle()` 不因 IME 隐藏把手。
- 代码中已从 `Settings.Secure.DEFAULT_INPUT_METHOD` 算出输入法包名，但仅用于前台包名过滤，未参与可见性判断；`ENABLED_INPUT_METHODS` 一次可取全部已启用输入法（实机返回 4 个，`:` 分隔）。
- `isCurrentPackageLauncher()` 在 `currentForegroundPackage` 为空或为自身时**假定在桌面**，是误触发的候选路径之一，需日志定位。

## 目标

1. 修复 IME 可见性检测：主信号 = 事件包名 ∈ 已启用输入法集合；兜底 = 原 className 检测（覆盖安全键盘等）。
2. 新增文件级调试日志（默认关）：开关 + 导出；对显隐判定链路全量埋点，供低概率误触发长期观测。

## 非目标

- 不改 `onlyOnHome` 判定逻辑本身（等日志数据定位后再修）。
- 不引入第三方日志库（F-Droid 约束、无新依赖）。
- 不做 logcat 子进程捕获 / 内存环形缓冲（评估过并否决：ROM 兼容风险、进程重启丢证据）。

## IME 检测修复（`PanelAccessibilityService`）

1. 新增 `cachedImePackages: Set<String>`：解析 `Settings.Secure.ENABLED_INPUT_METHODS`（按 `:` 分割取每段 `包名/组件` 的包名），30 秒缓存（参照现有 `cachedLauncherPkgs` 模式）。
2. `checkImeVisibilityFromEvent` 的可见判定改为：
   `className.contains("InputMethod", ignoreCase = true) || event.packageName in cachedImePackages`
3. 前台包名跟踪过滤增强：排除 `packageName in cachedImePackages`（现状只排除默认输入法单个包——输入法切换期间的事件会污染 `currentForegroundPackage`）。
4. 清除逻辑不变（`lastImeVisible && 事件来自非 IME 应用` → false）。

## DebugLog 组件（新文件 `DebugLog.kt`，扁平包）

```kotlin
object DebugLog {
    fun i(tag: String, msg: String)      // 关闭时短路空操作
    fun e(tag: String, msg: String, tr: Throwable? = null)
    fun session(context: Context)        // 写进程启动头
    fun shutdown()                       // 开关关闭时优雅停止
}
```

- 存储：`filesDir/logs/smartedge.log` + `smartedge.log.1`；单文件 2MB 上限，超限轮转（.log→.log.1，删旧 .1）；总量约 4MB，可存数天。
- 线程：单个 `HandlerThread`（串行有序、离开主线程）；**每条 flush**（频率低，进程被杀丢窗口最小）。
- 行格式：`MM-dd HH:mm:ss.SSS T<n> TAG: msg`；异常记录堆栈。
- session 头：时间、`Build.MODEL`/`VERSION.RELEASE`/SDK、应用版本、pid、关键偏好快照（onlyOnHome、panelSide、gestures/tap 等触发开关、useAutomationForGestures）。
- 开关状态读 `PanelPreferences.debugLogEnabled`；`i()/e()` 先查偏好（内存缓存，避免每条读 SharedPreferences），未初始化或偏好关时零开销。
- 初始化点有两处：设置开关开启时调 `DebugLog.session(this)`；`FloatingPanelService.onCreate` 也调用 `session(this)`——进程被杀重启后只要偏好仍开即自动恢复记录。

## 偏好（PanelPreferences）

- `var debugLogEnabled: Boolean`，key `debug_log_enabled`，默认 `false`。
- 不加入备份导出白名单（设备级调试状态）。

## 埋点清单（全部走 DebugLog；TAG 用类名）

| # | 位置 | 内容（单行） |
|---|---|---|
| 1 | `onAccessibilityEvent`（TYPE_WINDOW_STATE_CHANGED） | `pkg=… cls=…(截断40字符) filter=<system|ime|own|app> fgChange=<旧→新>`（fgChange 仅变化时记） |
| 2 | `setImeVisible` | `ime <旧>→<新> by=<cls\|imePkg>`（仅转变时） |
| 3 | `onLauncherContentEvent` | `launcherContent pkg=… throttled=<bool> refreshSent=<bool>` |
| 4 | `FloatingPanelService.onStartCommand` | `action=…` |
| 5 | 把手显隐判定（`addEdgeHandle` 与 ACTION_REFRESH 分支共用一处辅助函数） | `handle onlyOnHome=? fg=? isLauncher=? by=<empty\|own\|default\|anyLauncher\|systemui> imeVis=? engine=? triggers=? => SHOW/HIDE` |
| 6 | `isCurrentPackageLauncher` | 返回值 + 命中分支（供 #5 的 by 字段） |
| 7 | 服务生命周期 | `service onCreate/onDestroy`、session 头 |

事件埋点（#1）频率高（每次窗口切换），2MB 轮转下预计覆盖 1–3 天——满足「长时间使用才复现」的观测需求；如实际过快可在观测期由用户反馈再调（如过滤 app 过滤类）。

## 设置 UI（MiscellaneousSettingsActivity）

- 「调试日志」`MaterialSwitch`（id `feature_debug_log`），默认关；开启 → `DebugLog.session()`；关闭 → `DebugLog.shutdown()`。
- 「导出日志」按钮（id `btn_export_log`）：复用现有 `exportSettingsToDownloads` 的 MediaStore（Q+）/直写（<Q）模式 → `Downloads/SidePanel/smartedge_log_<yyyyMMdd_HHmmss>.txt`；按时间序合并 `.log.1` + `.log`；无日志文件时 toast「没有日志」。
- 文案三语（en/zh/es）；zh 半角标点无句尾句号。

## 边界与错误处理

- 进程死亡：每条 flush + 新进程 session 头；日志中断可辨识。
- 轮转覆盖最旧数据：接受（4MB 容量权衡）。
- 隐私：日志含前台应用包名；仅存应用私有目录，导出由用户主动触发——开关描述文案说明。
- 磁盘异常（写失败）：捕获、单次 toast 不打扰（后续条目继续尝试）。
- 现有 `Log.x` 调用保持不动。

## 验证计划

1. `./gradlew assembleDebug` 通过；lint 不新增错误（存量 55 个见台账）。
2. 模拟器冒烟：开关开/关；导出文件存在且含 session 头与埋点行；轮转（灌入超量日志验证 .1 生成）。
3. 实机（荣耀，微信+豆包输入法）：各开合键盘一次 → 导出日志 → 应见 `ime false→true by=imePkg` 与 `handle … => HIDE`；键盘收起 → `SHOW`（在桌面时）。此即修复生效证据。
4. 观测流程演练：导出 → 文件内容可读、时间序正确。

## 实现范围（文件清单）

1. `PanelAccessibilityService.kt` — IME 检测修复 + 埋点 #1/2/3。
2. 新文件 `DebugLog.kt`。
3. `FloatingPanelService.kt` — 埋点 #4/5/6/7（`isCurrentPackageLauncher` 返回命中分支）。
4. `PanelPreferences.kt` — `debugLogEnabled`。
5. `MiscellaneousSettingsActivity.kt` + 布局 — 开关 + 导出按钮。
6. `values*/strings.xml` ×3 — 文案。

## 附录：实现期发现（2026-09-05，模拟器验收后）

1. **输入法包名集合改由 IMM 公开 API 获取（SecurityException）**：`Settings.Secure.ENABLED_INPUT_METHODS` 仅对 targetSdk ≤33 的应用可读；API 35 模拟器实测（SDK 34+ 目标）读取即抛 `SecurityException`，令 a11y 服务在每次 TYPE_WINDOW_STATE_CHANGED 上崩溃循环。实际实现改由公开 API `InputMethodManager.enabledInputMethodList` 取包名集合（`runCatching` 包裹、30 秒缓存不变），修复见 commit a7d2c9d。正文中「解析 `Settings.Secure.ENABLED_INPUT_METHODS`」的表述（「背景与问题」末条与「IME 检测修复」第 1 条）以本附录为准。
2. **埋点 #3 日志位置调整**：原计划在每个 launcher content-changed 事件上记录 `throttled` 状态；终稿改为仅在通过 800ms 节流、真正发出 refresh 时记录 `refreshSent=true`——MagicOS 等 OEM 桌面该事件频率极高，逐条记录会迅速轮转掉 2MB 日志。
3. **IME 可见性清除信号改为窗口列表权威判定（首次实机复现后）**：实机日志（2026-09-05 17:56:57，桌面搜索框 + 豆包输入法）证实事件式清除不可靠——键盘弹出 0.44 秒后，桌面自身发出的 ListView 窗口杂音被误判为「键盘收起」，把手在键盘仍打开时重新显示并挡住右侧打字。终稿改为：窗口事件仅作触发，真值来自无障碍窗口列表中是否存在 `AccessibilityWindowInfo.TYPE_INPUT_METHOD` 窗口（需 `flagRetrieveInteractiveWindows`，已加入服务配置，更新后需重新开启一次无障碍服务生效）；包名/className 匹配保留为打开信号的或条件，清除仅在窗口列表可用且不含 IME 窗口时进行，列表不可用时回退旧启发式。
