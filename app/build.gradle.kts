plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // kotlinx.serialization 编译器插件：为 @Serializable 类生成 serializer()。
    // 只加 runtime 依赖是编译不过的，必须同时应用这个插件。
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "top.dsh.splayerremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.dsh.splayerremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标：只用 PlayArrow / Pause / SkipNext 等通用图标，
    // 它们都在 material3 传递引入的 icons-core 里（十个左右的小包）。
    // 不要换成 material-icons-extended——那是 7000+ 图标、83MB，
    // 会让 AAPT2 与 D8 多跑一大截，这里完全用不到。

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 专辑封面加载。Coil 走 OkHttp，和项目已有依赖共用一套网络栈。
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
