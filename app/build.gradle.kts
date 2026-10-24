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
        versionCode = 17
        versionName = "1.17"
    }

    buildTypes {
        release {
            // 默认不混淆，省心；如需混淆请启用下面两行，并在 proguard-rules.pro 中保留 README 规则
            isMinifyEnabled = false
            // isMinifyEnabled = true
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}
