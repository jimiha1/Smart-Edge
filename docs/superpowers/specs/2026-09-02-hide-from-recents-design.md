# 设计：从最近任务隐藏（防止侧边栏被误杀）

日期：2026-09-02
状态：已与用户确认（方案 A：双 activity-alias + 组件启停；含服务加固）

## 背景与问题

Smart Edge 的核心体验依赖 `FloatingPanelService`（specialUse 前台服务）。在激进后台管理的 OEM ROM（MIUI、OriginOS、ColorOS 等）上，「从最近任务划掉卡片」经常直接杀死整个进程，面板随之消失——这是用户最主要的误杀路径。原生 Android 上 `stopWithTask` 默认即为 `false`（[官方文档](https://developer.android.com/guide/topics/manifest/service-element)），划掉任务本不会停止服务；问题集中在 OEM 的划卡片杀进程行为。

方案：把应用任务从最近任务里彻底隐藏，没有卡片可划，误杀路径随之消失。做成设置开关（默认开启），并通过双 activity-alias 实现运行时切换。

## 目标行为

1. 开关**默认开启**：应用任务不出现在最近任务中。
2. 开关关闭：最近任务行为与现状完全一致。
3. 无论开关状态，`FloatingPanelService` 显式声明 `android:stopWithTask="false"`（防回归、向 ROM 表达意图）。

## 非目标（Non-goals）

- 不做 `onTaskRemoved` 里的闹钟重启自愈（前台服务在原生行为下已存活，YAGNI）。
- 不处理 OEM 白名单/自启动管理引导（已有 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 相关能力）。
- 不改动 `ToggleActivity`（它已有 `excludeFromRecents="true"` + `taskAffinity=""` + `singleInstance`）。

## Manifest 改动（`app/src/main/AndroidManifest.xml`）

### MainActivity 去入口化

- 移除 MAIN/LAUNCHER intent-filter 与 `android.app.shortcuts` meta-data。
- 改为 `android:exported="false"`（仅被别名及应用内显式 Intent 启动）。

### 新增两个 activity-alias

```xml
<activity-alias
    android:name=".LauncherHidden"
    android:targetActivity=".MainActivity"
    android:exported="true"
    android:enabled="true"
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <meta-data
        android:name="android.app.shortcuts"
        android:resource="@xml/shortcuts" />
</activity-alias>

<activity-alias
    android:name=".LauncherVisible"
    android:targetActivity=".MainActivity"
    android:exported="true"
    android:enabled="false">
    <!-- 同样的 filter 与 meta-data，无 excludeFromRecents -->
</activity-alias>
```

要点：

- manifest 默认状态 = 隐藏开（`LauncherHidden` enabled），与偏好默认值一致，首次安装即生效，无需「先启动再切换」。
- 两个别名共用 application 级 icon/label，桌面图标外观不变。
- shortcuts meta-data 必须挂在别名上（系统从「带 LAUNCHER filter 的组件」读取静态快捷方式）；禁用别名的静态快捷方式自动隐藏。
- `excludeFromRecents` 是任务级属性，取自任务根组件；`MainActivity` 任务内的所有设置页（`SettingsMainActivity` 等）随之一起隐藏。

### 服务加固

`FloatingPanelService` 声明加 `android:stopWithTask="false"`，附一行注释说明目的。

## 偏好与同步逻辑

### PanelPreferences（沿用现有访问器模式）

- `isHideFromRecents(): Boolean`，默认 `true`。
- `setHideFromRecents(enabled: Boolean, commit: Boolean = false)`。

### 新文件 RecentsHideHelper（匹配 *Helper 命名惯例）

```kotlin
object RecentsHideHelper {
    fun apply(context: Context, hidden: Boolean)
    // setComponentEnabledSetting 翻转两个别名，flags 带 DONT_KILL_APP

    fun sync(context: Context)
    // 读取偏好，比对组件实际启用状态；不一致（如清除数据后残留）则修正
}
```

- `apply`：`PackageManager.setComponentEnabledSetting(ComponentName(pkg, "$pkg.LauncherHidden"), ...)` 与 `LauncherVisible` 成对翻转，`DONT_KILL_APP` 保证切换不重启进程、前台服务不受影响。
- `sync`：调用点 `SidePanelApp.onCreate()`；开关回调调用 `apply`。
- `apply` 捕获 `Exception`（个别 ROM 上 setComponentEnabledSetting 异常）：回滚偏好值，Toast 提示失败。

## 设置 UI

- 位置：`MiscellaneousSettingsActivity` 功能开关区，紧邻 `featureHideNotification`。
- 新增 Switch（ViewBinding id 建议 `featureHideFromRecents`），初始化时读偏好，回调写偏好 + `RecentsHideHelper.apply`。
- 文案三语（values / values-es / values-zh）：

| 语言 | 标题 | 描述 |
|---|---|---|
| en | Hide from Recents | Keeps the panel alive by hiding this app from Recents. Takes effect on next launch; the home-screen icon may refresh when toggled. |
| zh | 从最近任务隐藏 | 将应用从最近任务中隐藏，防止清理后台时误杀侧边栏。对新启动生效；切换开关时桌面图标可能短暂刷新，属正常现象。 |
| es | Ocultar de recientes | Oculta la app de las apps recientes para evitar que el panel se cierre al limpiar la memoria. Surte efecto en el próximo inicio; el icono puede actualizarse brevemente. |

## 数据流

```
切换开关
  → PanelPreferences.setHideFromRecents()
  → RecentsHideHelper.apply()
  → PackageManager 组件状态翻转（DONT_KILL_APP，服务存活）
  → 桌面收到 PACKAGE_CHANGED，刷新图标
  → 此后从图标启动走启用的别名 → 任务按新可见性行为
```

## 边界与错误处理

| 场景 | 行为 |
|---|---|
| 切换时前台服务运行中 | `DONT_KILL_APP`，服务与通知不受影响 |
| 当前已存在的最近任务卡片 | 以旧 baseIntent 保留到任务结束；新启动立即按新状态（文案已说明「对新启动生效」） |
| 清除数据后组件状态残留（PM 状态不随数据清除） | 下次 `SidePanelApp.onCreate()` 的 `sync()` 修正为偏好值 |
| `setComponentEnabledSetting` 抛异常 | 捕获 → 回滚偏好 → Toast |
| 升级安装（现有用户） | manifest 默认即隐藏开；偏好默认值同为 true，`sync()` 无操作，无感知 |
| 静态快捷方式（长按图标） | 挂在两个别名上；实现时在模拟器验证两态下均可见、可用 |

## 验证计划

1. `./gradlew assembleDebug` 构建通过；`./gradlew lint` 通过（`abortOnError = true`）。
2. Android 模拟器（本机 android-emulator 工具链）手动验收清单：
   - [ ] 全新安装：桌面图标正常，打开应用正常，按 Home 后最近任务中无本应用卡片。
   - [ ] 进入任一设置页 → Home → 最近任务无卡片。
   - [ ] 关闭开关 → 重新从图标启动 → 最近任务出现卡片。
   - [ ] 重新开启开关 → 最近任务不再出现新卡片。
   - [ ] 开关切换瞬间：前台服务通知常驻、面板句柄不消失。
   - [ ] 长按桌面图标：静态快捷方式在两种状态下可用。
   - [ ] 清除应用数据 → 启动 → 组件状态自动纠正为默认隐藏。
   - [ ] 开关切换时 Toast 无异常报错；`adb logcat` 无崩溃。

## 实现范围（文件清单）

1. `app/src/main/AndroidManifest.xml` — MainActivity 去入口、双别名、stopWithTask。
2. `PanelPreferences.kt` — 新访问器。
3. 新文件 `RecentsHideHelper.kt`。
4. `SidePanelApp.kt` — onCreate 调 `sync()`。
5. `MiscellaneousSettingsActivity.kt` + 对应布局 — 新 Switch。
6. `values/strings.xml`、`values-es/strings.xml`、`values-zh/strings.xml` — 文案。
7. `app/src/main/res/xml/shortcuts.xml` — 确认目标组件不受别名影响（只读检查）。
