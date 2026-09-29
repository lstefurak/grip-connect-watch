import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * The shared base. Pure Kotlin: device protocols, parsing, session math.
 * No Bluetooth dependency, so it runs identically on:
 *   - jvm      -> consumed directly by the Wear OS app (and runs the unit tests fast)
 *   - watchos* -> packaged as GripCore.xcframework for the Apple Watch app
 */
kotlin {
    jvmToolchain(17)

    jvm()

    val xcf = XCFramework("GripCore")
    listOf(
        watchosArm64(),          // Series 4-8, SE (arm64_32)
        watchosDeviceArm64(),    // Series 9+, Ultra 2+ (arm64)
        watchosSimulatorArm64(), // Apple silicon simulator
    ).forEach { target ->
        target.binaries.framework {
            baseName = "GripCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
