import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.guiderunningfortheblind"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.guiderunningfortheblind"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 高德导航SDK支持的ABI架构
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // ═══════════════════════════════════════════════════════════
        //  【迁移】DashScope API Key 注入 BuildConfig
        //  直接读取 local.properties 中的 dashscope_api_key 字段
        //  注意：project.findProperty() 不会读取 local.properties！
        //        必须用 Properties.load() 显式读取文件
        // ═══════════════════════════════════════════════════════════
        val localProperties = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { localProperties.load(it) }
        }
        val dashscopeKey = localProperties.getProperty("dashscope_api_key", "")
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$dashscopeKey\"")
    }

    buildTypes {
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    // 防止SO库冲突
    packaging {
        jniLibs {
            useLegacyPackaging = false   // 使用 uncompressed native libs，配合 16KB 对齐
        }
    }
}

//configurations.configureEach {
//    exclude(group = "com.amap.api", module = "location")
//}


dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Retrofit & Gson
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation("com.google.guava:guava:32.1.3-android")

    // Health Connect
    implementation(libs.health.connect)

    // Google Play Services Location
    implementation(libs.play.services.location)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // ═══════════════════════════════════════════════════════════
    //  【迁移】已删除 Gemini Generative AI 依赖
    //  通义千问通过 Retrofit + Gson 直接调用 REST API，无需额外 SDK
    // ═══════════════════════════════════════════════════════════

    // 高德地图SDK - 导航核心
    implementation("com.amap.api:navi-3dmap-location-search:10.0.700_3dmap10.0.700_loc6.4.5_sea9.7.2")
    // 如果你还用到了 POI 搜索、路径搜索 RouteSearch，可以保留 search
    //implementation("com.amap.api:search:9.7.4")
    //implementation("com.amap.api:location:6.4.7")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
