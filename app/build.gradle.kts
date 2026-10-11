plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.selkies.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.selkies.client"
        minSdk = 26          // Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 签名配置：默认读取项目根目录 keystore 目录；可用环境变量覆盖
            val ksFile = file("../keystore/selkies-release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("SELKIES_STORE_PASS") ?: "SelkiesAndroid2024!"
                keyAlias = System.getenv("SELKIES_KEY_ALIAS") ?: "selkies"
                keyPassword = System.getenv("SELKIES_KEY_PASS") ?: "SelkiesAndroid2024!"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // WebSocket 客户端（支持自定义请求头，Basic Auth / Cloudflare 兼容必需）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON 序列化（服务器配置存储）
    implementation("com.google.code.gson:gson:2.11.0")
}
