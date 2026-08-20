@file:Suppress("DEPRECATION")
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinxCoroutinesCore)
            implementation(libs.koinCore)
        }
        androidMain.dependencies {
            implementation("androidx.fragment:fragment-ktx:1.8.9")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.uiTest)
        }
    }
}

extensions.configure<LibraryExtension> {
    namespace = "com.bank.mobile.compose"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}
