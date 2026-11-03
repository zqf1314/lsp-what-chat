import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 固定签名：CI 里由 secret 解出一份 keystore，保证每次构建指纹一致、可覆盖升级。
// 本地没有 keystore 时自动退回 debug 签名（仅用于临时验证）。
val signingKsPath: String? = System.getenv("SIGNING_KEYSTORE")
val signingStorePass: String = System.getenv("SIGNING_STORE_PASS") ?: ""
val signingKeyAlias: String = System.getenv("SIGNING_KEY_ALIAS") ?: "whatchat"
val hasStableSigning: Boolean = signingKsPath != null && File(signingKsPath).exists()

android {
    namespace = "com.whatchat.mcp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whatchat.mcp"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = File(signingKsPath!!)
                storePassword = signingStorePass
                keyAlias = signingKeyAlias
                keyPassword = signingStorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasStableSigning) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}