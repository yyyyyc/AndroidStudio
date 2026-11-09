plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.facefilters"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.facefilters"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Phones. MediaPipe has no x86_64 build: emulators run the ARM one.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // MediaPipe reads its model straight from the APK.
    androidResources { noCompress += "task" }

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
}

dependencies {
    val cameraxVersion = "1.4.2"

    implementation("androidx.core:core-ktx:1.15.0")
    // Which way up the user's photos are, for pictures used as stickers.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    // Draws filters onto the very camera frame they were found in, for the preview and videos.
    implementation("androidx.camera:camera-effects:$cameraxVersion")

    // Face landmarks: 478 points in 3D, and the head's pose. The model is in assets: works offline.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    testImplementation("junit:junit:4.13.2")
}
