# Contributing guide

Thank you for investing your time in contributing to Cardamum for Android.

## Development environment

The development environment is managed by [Nix flakes](https://nixos.wiki/wiki/Flakes). Running `nix develop` spawns a shell with everything pinned: a Rust toolchain carrying the four Android ABI targets (read from `rust-toolchain.toml`), `cargo-ndk`, the Android SDK and NDK, JDK 17, Gradle and `jdt-language-server`.

If you do not want to use Nix, provide these yourself: the Android SDK (platform 34, build-tools 34.0.0) and NDK r26, JDK 17, Gradle, plus a Rust toolchain (>= `v1.87`) with the `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` and `i686-linux-android` targets and `cargo-ndk`.

## Architecture

Three layers, each knowing only the one below:

- `:app` (Java, framework Views): the screens. Talks only to `CardamumClient`; never sees sockets or JNI.
- `:client` (Android library, Java): the public API exposed to the app. Owns the JNI boundary, the URL-keyed pool of TLS sockets (platform trust store, zero APK cost) and the parsing of the bridge's replies.
- `libcardamum.so` (Rust): the Pimalaya I/O-free building blocks (pimconf discovery, io-webdav CardDAV) exposed over JNI, with socket I/O delegated back to Java on each read/write yield.

TLS and TCP live in Java on purpose: the `.so` stays a small state machine that cross-compiles trivially, and certificate validation is handled by Android. The app is plain Java and XML framework Views, no Kotlin and no Jetpack, to keep the toolchain small and the APK minimal.

## Build

```sh
nix develop
cd android
gradle assembleRelease
```

`gradle` first cross-compiles the Rust bridge for the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) via cargo-ndk into `client/src/main/jniLibs`, then assembles the APKs. The release build emits one APK per ABI plus a universal one under `app/build/outputs/apk/release/`. For a quick install on your own device, `gradle assembleDebug` produces a ready-to-sideload `app/build/outputs/apk/debug/app-debug.apk`.

To iterate on the Rust bridge alone: `cd rust && cargo build`.

## Lint, test

The Rust bridge is checked with:

```sh
cd rust
cargo fmt
cargo clippy
cargo test
```

The app's pure logic (the neutral-field-model to ContactsContract mapping in `Mapping`) is covered by JVM unit tests that pin the wire columns and type constants, so a mapping change breaks loudly:

```sh
cd android
gradle :app:testDebugUnitTest
```

## Commit style

Cardamum for Android follows the [conventional commits specification](https://www.conventionalcommits.org/en/v1.0.0/#summary).
