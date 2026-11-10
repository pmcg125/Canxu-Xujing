plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.xujing"
    compileSdk = 36
    defaultConfig { applicationId = "app.xujing"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "0.2.1" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // -Pxujing.onlyAbi=arm64-v8a builds a single ABI without the universal APK.
    val onlyAbi = providers.gradleProperty("xujing.onlyAbi").orNull
    splits { abi { isEnable = true; reset(); if (onlyAbi != null) include(onlyAbi) else include("arm64-v8a", "armeabi-v7a", "x86", "x86_64"); isUniversalApk = onlyAbi == null } }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
