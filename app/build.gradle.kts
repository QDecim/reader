plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Fail early with a clear message instead of an UnsatisfiedLinkError on the phone.
val ortFiles = listOf("libs/onnxruntime-classes.jar", "src/main/jniLibs/arm64-v8a/libortjruntime.so", "src/main/jniLibs/arm64-v8a/libonnxruntime4j_jni.so")
val ortMissing = ortFiles.filter { !file(it).exists() }
if (ortMissing.isNotEmpty()) throw GradleException("Missing $ortMissing: run setup.ps1 (it prepares onnxruntime for the StyleTTS2 benchmark)")

android {
    namespace = "ua.vatsuo.chytalka"
    compileSdk = 34
    defaultConfig {
        applicationId = "ua.vatsuo.chytalka"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // Dimensity 1200 = arm64
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx") }
}

dependencies {
    implementation(fileTree("libs") { include("*.aar") }) // sherpa-onnx (run setup.ps1)
    implementation(fileTree("libs") { include("*.jar") }) // ORT Java classes only; native libs are patched copies in src/main/jniLibs (tools/ort_android.py)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
