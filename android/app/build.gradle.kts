plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// バージョンはビルド時に -PvCode=4 -PvName=1.4 で渡す(GitHub Actionsが自動決定)
val vCode = (findProperty("vCode") as String?)?.toInt() ?: 1
val vName = (findProperty("vName") as String?) ?: "dev"

android {
    namespace = "com.example.discordcleaner"
    compileSdk = 34

    defaultConfig {
        // v2.1から別アプリとして配布(旧 com.example.discordcleaner は別キー版が端末に残り上書きできなかったため)
        applicationId = "com.dcleaner.app"
        minSdk = 24
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    // 署名: 環境変数 KS(.jksのパス) / KSPASS。無ければ未署名(CIのテストビルド用)
    val ksPath = System.getenv("KS")
    val ksPass = System.getenv("KSPASS")
    if (!ksPath.isNullOrEmpty() && !ksPass.isNullOrEmpty()) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = ksPass
            keyAlias = "cleaner"
            keyPassword = ksPass
            // v1.3以前(uber-apk-signer)と同じ v1+v2+v3 で署名する。
            // v2のみだと一部端末で「既存のパッケージと競合」になり上書きできないため。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; compose = true }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
