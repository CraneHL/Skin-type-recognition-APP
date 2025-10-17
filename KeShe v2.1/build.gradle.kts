// build.gradle.kts (项目根目录)
plugins {
    // 使用 libs.toml 中定义的插件别名（确保与 toml 中一致）
    alias(libs.plugins.androidApplication) apply false
}
