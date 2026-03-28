// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace   = "org.rhanet.roverctrl"
    compileSdk  = 34

    defaultConfig {
        applicationId  = "org.rhanet.roverctrl"
        minSdk         = 24        // Android 7 — Camera2 стабилен с API 24
        targetSdk      = 34
        versionCode    = 1
        versionName    = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    val navVer  = "2.7.7"
    val lcVer   = "2.7.0"

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:$navVer")
    implementation("androidx.navigation:navigation-ui-ktx:$navVer")

    // ViewModel + Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lcVer")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lcVer")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX (камера телефона, 120fps ML pipeline)
    val cameraVer = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraVer")
    implementation("androidx.camera:camera-camera2:$cameraVer")
    implementation("androidx.camera:camera-lifecycle:$cameraVer")
    implementation("androidx.camera:camera-view:$cameraVer")

    // TFLite — YOLOv8 inference
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // GPU delegate — используем api совместимый с 2.14.0
    // gpu-delegate-plugin удалён: вызывает NoClassDefFoundError (GpuDelegateFactory$Options)
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
}
