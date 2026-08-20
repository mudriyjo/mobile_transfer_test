plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlinAndroid) apply false
}

group = "com.bank.mobile"
version = "1.0.0"

tasks.register("candidatePreflight") {
    group = "verification"
    description = "Runs the fast candidate-visible baseline checks."
    dependsOn(":shared:jvmTest", ":backendStub:test")
}

tasks.register("verifyAndroid") {
    group = "verification"
    description = "Compiles and tests the Android application and shared code."
    dependsOn(":shared:jvmTest", ":backendStub:test", ":androidApp:assembleDebug", ":androidApp:assembleRelease")
}

tasks.register("verifyIosSimulator") {
    group = "verification"
    description = "Runs Kotlin/Native and XCTest checks and links the iOS Compose framework."
    dependsOn(
        ":shared:iosSimulatorArm64Test",
        ":composeApp:iosSimulatorArm64Test",
        ":composeApp:linkDebugFrameworkIosSimulatorArm64",
        ":composeApp:linkReleaseFrameworkIosSimulatorArm64",
        "verifyIosXcode",
        "verifyIosXCTest",
    )
}

tasks.register<Exec>("verifyIosXcode") {
    group = "verification"
    description = "Builds the Swift host against the linked Compose framework without signing."
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    commandLine(
        "xcodebuild",
        "-project", "iosApp/iosApp.xcodeproj",
        "-scheme", "MobileBank",
        "-sdk", "iphonesimulator",
        "-destination", "generic/platform=iOS Simulator",
        "CODE_SIGNING_ALLOWED=NO",
        "build",
    )
}

tasks.register<Exec>("verifyIosXCTest") {
    group = "verification"
    description = "Runs the Swift XCTest smoke suite on an available iPhone simulator."
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    mustRunAfter("verifyIosXcode")
    commandLine("bash", "scripts/verify-ios-xctest.sh")
}

tasks.register("verifyAssessment") {
    group = "verification"
    description = "Runs every Gradle verification available on a macOS assessment host."
    dependsOn("candidatePreflight", "verifyAndroid", "verifyIosSimulator")
}
