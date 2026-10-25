# NoMediaIsland（音乐不上超级岛 · libxposed 模块）

一个基于现代 **libxposed API（io.github.libxposed:api:101.0.1）** 的 Xposed / LSPosed 模块，
面向 **HyperOS 3.0.3（Android 15）** 实测调校。

作用：拦截 HyperOS「超级岛 / 通知岛（DynamicIsland）」把媒体渲染成顶部胶囊的逻辑，
让音乐（及所有媒体）不再上岛，同时**音乐 App 自身的 MediaStyle 通知仍正常留在通知栏**。

## 功能（v2.2 全部可在设置界面开关）

安装模块后，桌面会出现 **NoMediaIsland** 图标，点进去是标准设置页。

> **生效方式（v2.2 起）**：开关值在 SystemUI **启动时读取一次**，修改开关后需要
> **重启 SystemUI 才能生效**（保存时界面会弹出提示）。这样做的好处是：
> **关闭的功能完全不加载任何钩子**，运行时成本与未安装模块完全一致——
> 不存在「钩子常驻、每次方法调用都白跑一次分发」的开销。
> 如果你更习惯「改完即时生效」，可以用仓库里的 v2.1 版本（钩子常驻 + 实时门控）。

| 功能 | 默认 | 说明 |
| --- | --- | --- |
| 阻止媒体上超级岛 | 开 | 拦截媒体进入灵动岛/超级岛，音乐改用自身播放通知显示 |
| 音乐 App 显示状态栏图标 | 开 | 播放时在状态栏显示网易云音乐/QQ音乐等应用图标 |
| 隐藏系统媒体卡片 | 关 | 隐藏 HyperOS 自带的「系统风格媒体卡」（大封面那张），改为显示音乐 App 自己的播放通知（HyperOS 2.0 的效果）；开启后横滑切换与页码指示点同时失效 |
| 媒体卡横滑切换 | 开 | 通知栏媒体卡片上左右横滑，切换上一个/下一个媒体会话（同时播放多个 App 时有用） |
| 显示页码指示点 | 开 | 多个媒体会话时，卡片底部显示当前第几页的小圆点 |
| 隐藏原始媒体通知行 | 开 | 折叠通知栏里多余的原始媒体通知行，避免重复（「隐藏系统媒体卡片」开启时本开关无效，始终显示 App 自己的通知） |
| 仅对列表内应用生效 | 关 | 开启后仅处理下面列表中的应用；默认对所有应用生效 |

「生效应用列表」一行一个包名，内置：

```
com.netease.cloudmusic
com.tencent.qqmusic
```

## 重启 SystemUI 的方法

修改开关后，任选其一：

- 电脑连接后执行：`adb shell killall com.android.systemui`（最常用，状态栏会重绘，数据不丢）
- 部分 HyperOS 桌面/快捷设置里有「重启 SystemUI」类工具，亦可使用
- 直接重启手机（必然生效）

## 安装与使用

1. 设备已安装 **LSPosed**（或支持 libxposed 的框架）。
2. 安装 `v2.2-NoMediaIsland-release.apk`（与旧版签名一致，可直接覆盖安装）。
3. LSPosed 中把本模块**作用域勾选 `com.android.systemui`**（仓库内 `META-INF/xposed/scope.list` 已静态声明）。
4. 重启 SystemUI / 设备。
5. 打开桌面上的 **NoMediaIsland** 图标进入设置界面，按需开关功能。
6. 播放音乐：顶部「超级岛」不再出现媒体胶囊，通知栏仍能看到音乐 App 自己的播放通知；
   同时播放两个音乐 App 时，在通知栏媒体卡片上左右横滑即可切换。
7. **每次修改开关后，按上面「重启 SystemUI 的方法」重启一次**，新设置才会生效。

> 模块未激活时设置界面会提示；激活后界面与 Hook 通过 libxposed Remote Preferences
> 共享数据（group = `noisland_settings`），开关值保存后重启 SystemUI 即应用。

## 工作原理

钩子按开关**条件注册**（v2.2）：关闭的功能不注册对应钩子，开启的功能常驻注册。

- **阻止上岛**（`block_island`）：hook `MiuiIslandMediaController.addDynamicIslandView(3参)` 直接拦截，
  并对三个调用方（`onMediaDataChanged` / `onCastInfoChanged` / `reInflateView$1`）
  `deoptimize` 防止 ART 内联导致回调不触发；同时拦掉
  `mediaDataChangeListener$1.onMediaDataChanged` 数据入口，双保险确保岛不出现。
  （早期版本还 hook 了落岛接口 `DynamicIslandContent.add/updateDynamicIslandView`，
  但本机实测接口抽象方法无法 hook——`Cannot hook abstract methods`，已移除。）
  「仅对列表内应用生效」开启时，上述两处都会读取 MediaData.packageName，
  不在名单内的媒体照常上岛。
- **状态栏图标**（`status_icon`）：hook `MediaCoordinator$1.shouldFilterOut`，对媒体通知强制返回 `false`，
  使音乐 App 图标不被抽走到「背景图标区」，正常显示在状态栏。
- **横滑切换**（`card_swipe`）：hook `MiuiMediaViewController.attach` 拿到卡片 View，注册
  `MediaDataManager.Listener` 收集全部会话，在 `View.dispatchTouchEvent` 上以整卡根容器
  的屏幕坐标做几何命中（封面/标题/进度条/按钮任意位置可滑），横滑时调用
  `bindMediaData(另一个会话)` 完成切换；进度条区域只拖动不切卡。
- **页码指示点**（`indicator`）：把 SystemUI 原生 `com.android.systemui.qs.PageIndicator` 作为 overlay
  挂在卡片 FrameLayout 底部（不撑高卡片、不产生空白），会话 <2 时自动隐藏。
- **隐藏原始通知行**（`hide_raw` 且 `hide_miuicard` 关）：媒体行 `getIntrinsicHeight` 返回 0 +
  `onAttachedToWindow` 置 GONE + 兜底拦截 `setVisibility(VISIBLE)`，彻底消除折叠残留占位。
- **隐藏系统媒体卡片**（`hide_miuicard`）：拦掉 `MiuiMediaNotificationController` 的两个数据入口
  （`mediaDataListener$1.onMediaDataLoaded` / `MediaDataChangeListener.onMediaDataChanged`），
  控制器拿不到数据就不渲染卡片，通知栏改为显示音乐 App 自己的播放通知。

## 构建

- 需要 **Android SDK（compileSdk 36）** 与 **JDK 17+**。
- 依赖 `io.github.libxposed:api:101.0.1`（aar 要求 `compileSdk ≥ 36`）。
- 本项目自带 Gradle 8.10.2 wrapper（AGP 8.7.0）。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease   # 生成已签名的正式版 APK
```

产物在 `app/build/outputs/apk/release/app-release.apk`，复制为
`v2.2-NoMediaIsland-release.apk` 即可发布。

> Release 签名使用本地生成的 keystore（`app/noislandmedia-release.jks`，**不入库**）。
> 自行构建发布时请在 `app/build.gradle.kts` 的 `signingConfigs.release` 里换成你自己的密钥；
> 升级已安装模块必须沿用同一 keystore，否则无法覆盖安装。

## 版本策略说明

- **v2.2（当前）**：启动时条件注册钩子，改开关需重启 SystemUI；运行成本与未装模块一致。
- **v2.1**：钩子常驻 + 实时门控，改开关即时生效；但即使开关关闭，被 hook 的方法
  （如 `View.dispatchTouchEvent`）每次被调用仍会有一次 LSPosed 分发开销。
  在意这点系统开销就用 v2.2，在意即时生效就用 v2.1，功能完全一致。

## 免责声明

- 结论基于静态反编译定位 + 真机（HyperOS 3.0.3）实测，其它机型/版本效果请自测。
- 修改系统组件有风险，操作前请备份数据。
