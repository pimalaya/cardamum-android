plugins {
    id("com.android.application")
}

// Release signing is driven entirely by the environment so no keystore or
// password ever lands in the repo (CI decodes a base64 secret to a file).
// When CARDAMUM_KEYSTORE is unset (local debug, forks without secrets) the
// release APK is simply left unsigned.
val releaseKeystore = System.getenv("CARDAMUM_KEYSTORE")?.let { file(it) }

android {
    namespace = "org.pimalaya.cardamum"
    compileSdk = 35
    // Pinned to the one build-tools the devshell installs (see flake.nix),
    // newer than AGP's default, so AGP finds it instead of a missing one.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "org.pimalaya.cardamum"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                // PKCS12 (the default keystore format) forces the key
                // password to equal the store password, so one suffices.
                val password = System.getenv("CARDAMUM_KEYSTORE_PASSWORD")
                storeFile = releaseKeystore
                storePassword = password
                keyAlias = "cardamum"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }

        // The Google Play build: the FOSS release plus Google Play
        // Billing, built and uploaded to Play by hand. Kept a separate
        // build type on purpose, so `assembleRelease` stays FOSS-only
        // (GitHub CI is untouched) and this just adds `assembleGoogle` /
        // `bundleGoogle`. The matching-fallback lets it consume the
        // :client `release`.
        create("google") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    // The billing seam swaps by variant, so the interface lives in
    // `main` and each build binds its own implementation: the FOSS
    // `src/foss` no-op is shared with debug and release, while `google`
    // gets the Play Billing binding from its own source set. The FOSS
    // APK never sees any Google code.
    sourceSets {
        getByName("debug").java.srcDir("src/foss/java")
        getByName("release").java.srcDir("src/foss/java")
    }

    // One APK per ABI (smaller per-device downloads) plus a universal APK
    // that runs anywhere; the native libcardamum.so is the only per-ABI part.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The app's only door to the native bridge: sockets, JNI and Rust stay inside.
    implementation(project(":client"))

    // The addressbooks drawer. A tiny, standalone AndroidX ViewGroup (no
    // AppCompat/Material theme needed), the one Jetpack concession the
    // framework never offered a built-in for; the sibling himalaya-android
    // uses the same.
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Pull-to-refresh over the contacts list (triggers an account sync).
    // Another small, standalone AndroidX ViewGroup, no theme needed.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Google Play Billing, pulled into the google build only, so the
    // FOSS builds carry no Google dependency. Its manifest merge is what
    // adds the com.android.vending.BILLING capability that unlocks
    // subscriptions/products in the Play Console.
    "googleImplementation"("com.android.billingclient:billing:7.1.1")

    // JVM-only test dependencies (nothing ships in the APK). The org.json
    // artifact stands in for the android.jar stubs so Mapping runs on the
    // host JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
