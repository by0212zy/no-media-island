# NoMediaIsland（音乐不上超级岛 · libxposed 模块）

一个基于现代 **libxposed API（io.github.libxposed:api:102.0.0）** 的 Xposed / LSPosed 模块。

作用：拦截 HyperOS「超级岛 / 通知岛（DynamicIsland）」把媒体渲染成顶部胶囊的**唯一汇聚点**
`MiuiIslandMediaController.addDynamicIslandView(...)`，让音乐（及所有媒体）不再上岛，
但**音乐 App 自身的 MediaStyle 通知仍正常留在通知栏**（两条独立通道，互不干扰）。

> 原理基于对本机 `MiuiSystemUI.apk` 的反编译定位：媒体会话变化、投屏变化、配置重 inflate 三处
> 最终都调用 `addDynamicIslandView`，因此只要让该方法直接 `return`，媒体就不再上岛。

## 工作原理

Hook 写法（拦截器链，不调用 `chain.proceed()` 即等价于原方法直接 return）：

```java
hook(hooked)
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept((XposedInterface.Chain chain) -> {
        if (ONLY_BLOCK_LISTED) {
            Object mediaData = chain.getArgs().get(2); // 第三个参数即 MediaData
            String pkg = readPackageName(mediaData);
            if (pkg == null || !BLOCKED_PACKAGES.contains(pkg)) {
                return chain.proceed(); // 不在名单内，照常上岛
            }
        }
        return null; // 不调用 proceed → 媒体不上岛
    });
```

## 过滤策略

默认 `ONLY_BLOCK_LISTED = false`：把所有媒体都挡在「超级岛」之外。

若只想排除特定几个音乐 App、其余媒体仍上岛，请把 `NoMediaIsland.java` 中：

```java
private static final boolean ONLY_BLOCK_LISTED = false;
private static final Set<String> BLOCKED_PACKAGES = Set.of(
        "com.netease.cloudmusic",   // 网易云音乐
        "com.tencent.qqmusic"       // QQ 音乐
);
```

改为 `ONLY_BLOCK_LISTED = true` 并填好包名即可（已内置通过反射读取 `MediaData.packageName`）。

## 构建

- 需要 **Android SDK（compileSdk 36 / build-tools 35.0.0+）** 与 **JDK 17+**。
- 依赖 `io.github.libxposed:api:101.0.1`（aar 要求 `compileSdk ≥ 36`）。
- 本项目自带 Gradle 8.10.2 wrapper（AGP 8.7.0 兼容 Gradle 8.9–8.11，不兼容 Gradle 9）。
- 用 Android Studio 打开根目录直接构建，或在命令行：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # 生成未签名的模块 APK
```

> 关于版本：libxposed API 最新稳定版是 `102.0.0`，但它的 aar 要求 `compileSdk ≥ 37`
> （需本地装有 `android-37` 平台）。本仓库默认用 `101.0.1`（`minCompileSdk = 36`）以保证在只装到
> `android-36` 的 SDK 上也能编译。若你的 SDK 已含 android-37，把 `app/build.gradle.kts` 里的
> `api:101.0.1` 改回 `api:102.0.0`、`compileSdk/targetSdk` 改回 `37`、`module.prop` 改回 `101→102`
> 即可，模块源码无需任何改动（两个版本的 Hook API 完全一致）。

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装与使用

1. 设备已安装 **LSPosed**（或支持 libxposed 的框架）。
2. 安装构建出的模块 APK。
3. LSPosed 中把本模块**作用域勾选 `com.android.systemui`**（SystemUI）。
4. 重启 SystemUI / 设备。
5. 播放音乐：顶部「超级岛」不再出现媒体胶囊，通知栏仍能看到音乐 App 自己的播放通知。

> 入口与元数据（现代 libxposed 规范，非旧版 `assets/xposed_init`）：
> - `META-INF/xposed/java_init.list` → 入口类全名
> - `META-INF/xposed/scope.list` → `com.android.systemui`
> - `META-INF/xposed/module.prop` → `minApiVersion=102 / targetApiVersion=102 / staticScope=true`

## 配套产物说明

本仓库是「运行时 Hook 模块」。另有一份**重编 SystemUI 的改法**（不依赖 LSPosed）：
对 `MiuiSystemUI.apk` 的 `classes3.dex` 中 `MiuiIslandMediaController.addDynamicIslandView`
首条指令改为 `return-void`，回编 + 平台签名后刷入。详见随附的《音乐播放器不上超级岛-修改方案.md》。

## 免责声明

- 结论基于静态反编译定位 + DEX 可解析校验 + 模块源码编译校验，真机效果请自测。
- 修改系统组件 / 刷机有风险，操作前请备份数据。
