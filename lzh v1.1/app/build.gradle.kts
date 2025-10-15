plugins {
    id("com.android.application")
    // 如果你的项目确实用不上 Kotlin, 不需要 kotlin-android 插件
    // id("org.jetbrains.kotlin.android") version "1.9.10" // 若用 Kotlin 才启用
}

android {
    namespace = "com.example.keshe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.keshe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // AndroidX / 测试依赖（明确坐标）
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.activity:activity:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // TensorFlow Lite（把模型放在 app/src/main/assets/）
    implementation("org.tensorflow:tensorflow-lite:2.15.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.15.0")
}
