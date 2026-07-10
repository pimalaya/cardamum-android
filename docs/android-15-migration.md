# Android 15 (API 35) migration

Google Play requires new apps and updates to target API level 35. This migration raised the compile and target SDK to 35 across the FOSS and Google builds. It is done and verified by a build of every variant; this note records what changed and how to check it.

## What changed

Toolchain (flake.nix):

- `platformVersions = [ "35" ]` (was 34).
- `buildToolsVersion = "36.1.0"` (was 34.0.0): the one build-tools the pinned nixpkgs androidenv packages, newer than AGP's default, so it is pinned explicitly (below) rather than left to AGP's missing default.
- `ndkVersion = "29.0.14206865"` (was 26.3.11579264): NDK r29, past the r27 that aligns native libraries to 16 KB pages by default.

Gradle plugin (build.gradle.kts): Android Gradle Plugin `8.7.3` (was 8.5.2), which supports compileSdk 35. The devshell Gradle (8.14.4) already satisfied its minimum.

SDK levels:

- app/build.gradle.kts: `compileSdk = 35`, `targetSdk = 35`, `buildToolsVersion = "36.1.0"`.
- client/build.gradle.kts: `compileSdk = 35`, `buildToolsVersion = "36.1.0"`.
- minSdk stays 24.

Edge-to-edge insets: apps targeting 35 draw under the system bars, so the chrome is pushed back in with the bar insets (only on API 35 and up; older devices keep the platform's opaque bars and automatic inset).

- MainActivity.applyEdgeToEdge sets a window-insets listener on the content view: the top inset pads the three colorPrimary app bars (main, addressbooks drawer, auth overlay), so the status bar area takes their colour as one bar; the bottom inset lifts the FAB and adds to each scrolling list's existing FAB clearance (contacts, addressbooks, config, books, advanced, source, the email row), so nothing hides under the navigation bar; the side insets pad the window for landscape bars and cutouts. The keyboard stays handled by adjustResize.
- The Google build's PaywallActivity pads its centred content by the same insets.

## Native 16 KB alignment

NDK r29 aligns the 64-bit libraries to 16 KB by default. Verified on the built libraries: the LOAD segments of arm64-v8a and x86_64 align to 0x4000 (16 KB); armeabi-v7a and x86 stay 0x1000 (4 KB), which is correct because the 16 KB requirement is 64-bit only.

Check:

```sh
readelf -l */jniLibs/arm64-v8a/libcardamum.so | awk '/LOAD/{getline; print $NF}' | sort -u
```

## Verify

```sh
cd android
nix develop --command gradle :app:assembleRelease :app:assembleGoogle :app:testDebugUnitTest
```

`assembleRelease` is the FOSS artifact (GitHub CI); `assembleGoogle` is the Play build. Both, plus the unit tests, pass. Re-test the UI for inset regressions (the app bars, the addressbooks drawer, the auth overlay, the FAB overlap, and the paywall) on an Android 15 device or emulator.

## Notes

- The strip step logs "Unable to strip libcardamum.so" and packages it as-is; this is a benign AGP or cargo-ndk note, not a migration regression, and the library is valid.
- The temporary edge-to-edge opt-out (`windowOptOutEdgeToEdgeEnforcement`) was not used; the insets are handled directly, so nothing breaks when targeting 36 later.
