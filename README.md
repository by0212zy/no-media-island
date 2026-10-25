# NoMediaIsland（音乐不上超级岛 · libxposed 模块）

一个基于现代 **libxposed API（io.github.libxposed:api:101.0.1）** 的 Xposed / LSPosed 模块，
面向 **HyperOS 3.0.3（Android 15）** 实测调校。

作用：拦截 HyperOS「超级岛 / 通知岛（DynamicIsland）」把媒体渲染成顶部胶囊的逻辑，
让音乐（及所有媒体）不再上岛，同时**音乐 App 自身的 MediaStyle 通知仍正常留在通知栏**。

## 功能（v2.0 全部可在设置界面开关）

安装模块后，桌面会出现 **NoMediaIsland** 图标，点进去是标准设置页。所有开关即时生效（无需重启 SystemUI）：

| 功能 | 默认 | 说明 |
| --- | --- | --- |
| 阻止媒体上超级岛 | 开 | 拦截媒体进入灵动岛/超级岛，音乐改用自身播放通知显示 |
| 音乐 App 显示状态栏图标 | 开 | 播放时在状态栏显示网易云音乐/QQ音乐等应用图标 |
| 媒体卡横滑切换 | 开 | 通知栏媒体卡片上左右横滑，切换上一个/下一个媒体会话（同时播放多个 App 时有用） |
| 显示页码指示点 | 开 | 多个媒体会话时，卡片底部显示当前第几页的小圆点 |
| 隐藏原始媒体通知行 | 开 | 折叠通知栏里多余的原始媒体通知行，避免重复（不影响状态栏图标） |
| 仅对列表内应用生效 | 关 | 开启后仅处理下面列表中的应用；默认对所有应用生效 |

「生效应用列表」一行一个包名，内置：

```
com.netease.cloudmusic
com.tencent.qqmusic
```

## 安装与使用

1. 设备已安装 **LSPosed**（或支持 libxposed 的框架）。
2. 安装 `v2.0-NoMediaIsland-release.apk`。
3. LSPosed 中把本模块**作用域勾选 `com.android.systemui`**（仓库内 `META-INF/xposed/scope.list` 已静态声明）。
4. 重启 SystemUI / 设备。
5. 打开桌面上的 **NoMediaIsland** 图标进入设置界面，按需开关功能。
6. 播放音乐：顶部「超级岛」不再出现媒体胶囊，通知栏仍能看到音乐 App 自己的播放通知；
   同时播放两个音乐 App 时，在通知栏媒体卡片上左右横滑即可切换。

> 模块未激活时设置界面会提示；激活后界面与 Hook 通过 libxposed Remote Preferences
> 实时同步（group = `noisland_settings`），改开关不需要重启。

## 工作原理

- **阻止上岛**：hook `MiuiIslandMediaController.addDynamicIslandView(3参)` 直接拦截，
  并对三个调用方（`onMediaDataChanged` / `onCastInfoChanged` / `reInflateView$1`）
  `deoptimize` 防止 ART 内联导致回调不触发；同时拦掉
  `mediaDataChangeListener$1.onMediaDataChanged` 数据入口，双保险确保岛不出现。
  （早期版本还 hook 了落岛接口 `DynamicIslandContent.add/updateDynamicIslandView`，
  但本机实测接口抽象方法无法 hook——`Cannot hook abstract methods`，已移除。）
  「仅对列表内应用生效」开启时，上述两处都会读取 MediaData.packageName，
  不在名单内的媒体照常上岛。
- **状态栏图标**：hook `MediaCoordinator$1.shouldFilterOut`，对媒体通知强制返回 `false`，
  使音乐 App 图标不被抽走到「背景图标区」，正常显示在状态栏。
- **横滑切换**：hook `MiuiMediaViewController.attach` 拿到卡片 View，注册
  `MediaDataManager.Listener` 收集全部会话，在 `View.dispatchTouchEvent` 上以整卡根容器
  的屏幕坐标做几何命中（封面/标题/进度条/按钮任意位置可滑），横滑时调用
  `bindMediaData(另一个会话)` 完成切换；进度条区域只拖动不切卡。
- **页码指示点**：把 SystemUI 原生 `com.android.systemui.qs.PageIndicator` 作为 overlay
  挂在卡片 FrameLayout 底部（不撑高卡片、不产生空白），会话 <2 时自动隐藏。
- **隐藏原始通知行**：媒体行 `getIntrinsicHeight` 返回 0 + `onAttachedToWindow` 置 GONE +
  兜底拦截 `setVisibility(VISIBLE)`，彻底消除折叠残留占位。

## 构建

- 需要 **Android SDK（compileSdk 36）** 与 **JDK 17+**。
- 依赖 `io.github.libxposed:api:101.0.1`（aar 要求 `compileSdk ≥ 36`）。
- 本项目自带 Gradle 8.10.2 wrapper（AGP 8.7.0）。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease   # 生成已签名的正式版 APK
```

产物在 `app/build/outputs/apk/release/app-release.apk`，复制为
`v2.0-NoMediaIsland-release.apk` 即可发布。

> Release 签名使用本地生成的 keystore（`app/noislandmedia-release.jks`，**不入库**）。
> 自行构建发布时请在 `app/build.gradle.kts` 的 `signingConfigs.release` 里换成你自己的密钥；
> 升级已安装模块必须沿用同一 keystore，否则无法覆盖安装。

## 免责声明

- 结论基于静态反编译定位 + 真机（HyperOS 3.0.3）实测，其它机型/版本效果请自测。
- 修改系统组件有风险，操作前请备份数据。
