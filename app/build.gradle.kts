plugins {
    // libxposed api:101.0.1 的 aar 要求 compileSdk >= 36
    // （最新版 102.0.0 要求 compileSdk >= 37，需本地装有 android-37 平台；若已安装可改回 102.0.0）
    id("com.android.application") version "8.7.0"
}

android {
    namespace = "com.example.noislandmedia"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.noislandmedia"
        minSdk = 26
        targetSdk = 36
        versionCode = 220
        versionName = "2.2"
    }

    signingConfigs {
        create("release") {
            // release 签名密钥（keytool 生成；文件不入库，密码仅本机使用）。
            // 升级模块需沿用同一 keystore，否则无法覆盖安装。
            storeFile = file("noislandmedia-release.jks")
            storePassword = "NoMediaIsland2026"
            keyAlias = "noisland"
            keyPassword = "NoMediaIsland2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // 确保 META-INF/xposed/* 资源被打进最终的 APK
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 现代 libxposed API（仅编译期需要，运行期由框架提供）
    compileOnly("io.github.libxposed:api:101.0.1")

    // 设置界面（标准 Preference 页）
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference:1.2.1")

    // libxposed 远程偏好：App 侧（设置界面）写入，Hook 侧（SystemUI）读取
    implementation("io.github.libxposed:service:101.0.0")
}
