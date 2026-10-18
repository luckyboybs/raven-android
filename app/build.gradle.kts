import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建期默认网关地址来自本机 local.properties（已被 .gitignore，永不入库）：
//   gateway.url  完整地址，如 https://gateway.example.com
//   gateway.host  intent-filter 用的 host（缺省时从 gateway.url 解析）
// 公开 clone 没有这两个键时回退占位地址，首启由用户手输真实网关地址。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val gatewayUrl = (localProperties.getProperty("gateway.url") ?: "https://gateway.example.com").trimEnd('/')
val gatewayHost = localProperties.getProperty("gateway.host")
    ?: runCatching { URI(gatewayUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: "gateway.example.com"

android {
    namespace = "com.raven.mobile"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.raven.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        manifestPlaceholders["gatewayHost"] = gatewayHost
        resValue("string", "home_url", gatewayUrl)
        resValue("string", "home_host", gatewayHost)
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
