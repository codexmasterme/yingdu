plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.yingdu"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.yingdu"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// 不依赖任何 AndroidX 库，只用系统 API
dependencies {
    implementation("io.github.jaredmdobson:concentus:1.0.2")   // 纯 Java 的 Opus 解码（眼镜麦克风），BSD 许可
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 单元测试里 android.jar 的 org.json 只是空壳
}
