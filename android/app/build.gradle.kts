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
    compileSdk = 34

    defaultConfig {
        applicationId = "org.pimalaya.cardamum"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
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

    // JVM-only test dependencies (nothing ships in the APK). The org.json
    // artifact stands in for the android.jar stubs so Mapping runs on the
    // host JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
