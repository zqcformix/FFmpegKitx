buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

tasks.register<Delete>("cleanBuild") {
    delete(rootProject.layout.buildDirectory)
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

extra["keystore_path"] = "./ffmpegkit.jks"
extra["keystore_alias_password"] = "123456"
extra["keystore_password"] = "123456"
extra["keystore_alias"] = "ffmpeg"
extra["minSdk"] = "26"
extra["targetSdk"] = "37"
extra["compileSdk"] = "37"
extra["versionCode"] = "4"
extra["versionName"] = "1.0.3"
extra["applicationId"] = "io.github.nova.ffmpegkit"
extra["otherName"] = "FFmpegKit"