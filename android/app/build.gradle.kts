plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// バージョンはビルド時に -PvCode=4 -PvName=1.4 で渡す(GitHub Actionsが自動決定)
val vCode = (findProperty("vCode") as String?)?.toInt() ?: 1
val vName = (findProperty("vName") as String?) ?: "dev"

android {
    namespace = "com.example.discordcleaner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.discordcleaner"
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
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // inject.js はリポジトリ直下の app/assets を使う(従来と同じ場所)
    sourceSets["main"].assets.srcDirs("../../app/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
