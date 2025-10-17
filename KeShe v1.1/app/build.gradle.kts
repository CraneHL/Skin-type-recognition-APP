plugins {
    alias(libs.plugins.androidApplication)
    // 如果你使用 Kotlin/Android，请在 libs.toml 中添加 kotlin 插件并在此取消注释：
    // id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.keshe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.keshe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 确保 tflite 文件不被压缩（便于使用 AssetFileDescriptor / mmap）
    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // TensorFlow Lite（显式写坐标：catalog 里没有 tflite）
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.15.0")
}
