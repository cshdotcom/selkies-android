plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cshdotcom.selkies"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cshdotcom.selkies"
        minSdk = 26            // Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // 发布签名：使用仓库根目录 keystore/ 下的密钥（见 keystore/keystore-info.txt）
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/selkies-release.keystore")
            storePassword = "selkies2025"
            keyAlias = "selkies"
            keyPassword = "selkies2025"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // 保持关闭，保证 OkHttp/反射零风险
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // WebSocket + HTTP 探测 + 文件上传
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
