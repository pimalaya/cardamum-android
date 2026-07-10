# Android 15 (API 35) migration plan

Google Play requires new apps and updates to target API level 35. Cardamum currently targets 34, so a production rollout is gated on this. Nothing here blocks internal testing or subscription setup; it is required before publishing to production. This is a plan to execute later, not applied yet.

## Where we are today

- `app/build.gradle.kts`: `compileSdk = 34`, `targetSdk = 34`, `minSdk = 24` (minSdk stays).
- Android Gradle Plugin `8.5.2` (root `build.gradle.kts`).
- `flake.nix`: `platformVersions = [ "34" ]` and a single pinned build-tools version; the Android SDK and NDK come from the nix devshell.
- The change touches the shared `defaultConfig`, so it affects the FOSS build, the Google build, and CI at once.

## Steps

1. Toolchain in `flake.nix` (the main effort, likely iterative): add platform `"35"` to `platformVersions`, bump build-tools to a `35.x`, and confirm the devshell resolves them. compileSdk 35 needs AGP 8.6+ (8.7.x recommended), so bump the AGP version, which may require a newer Gradle in the devshell too. Verify the NDK is r27+ so native libraries are 16 KB-page aligned (see step 4).
2. SDK levels in `app/build.gradle.kts`: `compileSdk = 35` and `targetSdk = 35`. Two lines, but only meaningful once step 1 lands.
3. Edge-to-edge insets (the main UI work): apps targeting 35 get edge-to-edge display enforced, so system bars turn transparent and content draws behind them. Cardamum uses framework Views with a custom top app bar (`colorPrimary`) and a bottom FAB, so without insets the top bar slides under the status bar and the FAB and bottom rows under the navigation bar. Apply the system-bar insets (pad the root or the bars) across: the main screen and app bar, the home/addressbooks drawer, the auth flow overlay, and the Google-build paywall. Re-test every screen on an Android 15 device or emulator.
4. Native 16 KB page alignment: Android 15 introduces 16 KB memory pages and Play is requiring native libraries to be 16 KB-aligned for apps targeting 15. We ship `libcardamum.so` via cargo-ndk, so confirm the devshell NDK (r27+ aligns by default) produces aligned libraries; otherwise adjust the cargo-ndk / linker flags. Verify with the alignment check on the built `.so`.
5. Verification: build and install both variants (`assembleRelease` and `assembleGoogle` / `bundleGoogle`), walk the full UI for inset regressions, run the app on API 35, and confirm CI still produces the FOSS artifacts.

## Risks and notes

- The toolchain bump (nix platform 35 + AGP + possibly Gradle + NDK) is the part most likely to cascade; do it first and in isolation.
- Edge-to-edge is the most likely visual regression; budget time to re-test drawers and the FAB overlap specifically.
- The 16 KB requirement is a separate Play deadline that lands around the same window as the target-API requirement; handling it here avoids a second pass.
- minSdk stays at 24; only compile and target move to 35.
