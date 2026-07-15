plugins {
    id("com.android.library")
}

android {
    namespace = "org.pimalaya.cardamum.client"
    compileSdk = 35
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // Same floor as :app, which carries the rationale.
        minSdk = 26

        ndk {
            // The four ABIs Android ships on; the Rust .so is built per ABI.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // Keep the JNI-referenced symbols when the app shrinks with R8.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // libcardamum.so lands here, one folder per ABI (see cargoNdkBuild).
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // JVM-only test dependencies (nothing ships in the AAR). The
    // org.json artifact stands in for the android.jar stubs so the
    // reply parsing runs on the host JVM, like in :app.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Cross-compiles the Rust bridge for every ABI and drops each
// libcardamum.so into jniLibs. Runs before the Android build so the
// .so is always in sync with the bridge source.
val cargoNdkBuild by
    tasks.registering(Exec::class) {
        // cargo-ndk runs `cargo metadata` in the working dir and ignores a
        // trailing --manifest-path, so run it from the crate and emit to an
        // absolute jniLibs path.
        workingDir = file("$projectDir/../../rust")
        commandLine(
            "cargo",
            "ndk",
            "-t",
            "arm64-v8a",
            "-t",
            "armeabi-v7a",
            "-t",
            "x86_64",
            "-t",
            "x86",
            "-o",
            file("$projectDir/src/main/jniLibs").absolutePath,
            "build",
            "--release",
        )
    }

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
