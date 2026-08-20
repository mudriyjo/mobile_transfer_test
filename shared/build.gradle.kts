import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinxCoroutinesCore)
            implementation(libs.kotlinxSerializationJson)
            implementation(libs.ktorClientCore)
            implementation(libs.ktorClientContentNegotiation)
            implementation(libs.ktorClientLogging)
            implementation(libs.ktorSerializationJson)
            implementation(libs.sqldelightRuntime)
            implementation(libs.sqldelightCoroutines)
            implementation(libs.koinCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinxCoroutinesTest)
            implementation(libs.ktorClientMock)
            implementation(libs.koinTest)
        }
        androidMain.dependencies {
            implementation(libs.ktorClientOkhttp)
            implementation(libs.sqldelightAndroidDriver)
            implementation(libs.androidxLifecycleRuntime)
            implementation(libs.androidxLifecycleProcess)
            implementation(libs.androidxBiometric)
        }
        jvmMain.dependencies {
            implementation(libs.ktorClientCio)
            implementation(libs.sqldelightSqliteDriver)
        }
        iosMain.dependencies {
            implementation(libs.ktorClientDarwin)
            implementation(libs.sqldelightNativeDriver)
        }
    }
}

android {
    namespace = "com.bank.mobile.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}

sqldelight {
    databases {
        create("MobileBankDatabase") {
            packageName.set("com.bank.mobile.db")
        }
    }
}
