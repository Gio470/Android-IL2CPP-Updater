# IL2CPP Offset Updater

An Android app that finds where ARM64 IL2CPP offsets moved between two builds
of `libil2cpp.so`. It runs locally on your device with a bundled Capstone
disassembler.

## Install

Requires a 64-bit ARM device running Android 8.0 or newer.

1. Open the latest successful run of the [Android APK workflow](https://github.com/Gio470/Android-IL2CPP-Updater/actions/workflows/android.yml).
2. Download the **offset-updater-debug-apk** artifact and extract it.
3. Open the APK. Android may ask you to allow installs from the browser or file
   manager you used to open it. The APK is signed with a debug key.

## Use the app

1. In **Setup**, select the **Original Library** your offsets came from and the
   **Target Library** you want to search.
2. Select an offsets file or paste the offsets into **Manual Offsets**.
3. Tap **Start** and open **Results**. Tap a resolved offset to copy it, or tap
   an ambiguous result to see its candidates. Use the copy or share controls
   beside **Your offsets** to export the results.

Enter one hexadecimal offset per line with a lowercase `0x` prefix. Add
`//` or `--` after an offset to give it a name. The app reports invalid
entries with their line numbers.

```text
0xBEAF // Example method
0xF00D
// A comment on its own line
```

The file browser remembers your selections between launches. The interface
uses Material 3 Expressive, follows the system light or dark theme, and
supports wallpaper colours on Android 12 and newer.

### Choose an engine

- **Standard** adjusts the length of a byte pattern to find a unique match.
  Start here for most updates.
- **Enhanced** uses more selective byte masks and can fall back to call graphs,
  pointer tables, and vtable slots. Try it for ambiguous results or builds that
  differ substantially. Optional Il2CppDumper `script.json` files can supply
  method boundaries or names.
- **Basic** starts by matching a whole function through its return instruction.
  Try it for small, self-contained functions.

## Build and test

Use JDK 21 and Android SDK Platform 35. Set `ANDROID_HOME` or set `sdk.dir`
in `local.properties` to your Android SDK path.

```bash
git clone https://github.com/Gio470/Android-IL2CPP-Updater.git
cd Android-IL2CPP-Updater
./gradlew :android:assembleDebug
```

The APK is written to `android/build/outputs/apk/debug/`. The repository
includes the prebuilt Capstone library. To rebuild it with the Android NDK, use
the [Capstone build script](tools/build-capstone-android.sh).

Run `./gradlew :core:test` to test the matching engines without an Android SDK.
The **Android APK** workflow also runs Android unit tests and lint, and uploads
screenshots and test reports as **ui-previews-and-reports**.

## Contributing

Report bugs or request features in [Issues](https://github.com/Gio470/Android-IL2CPP-Updater/issues).

## License and acknowledgements

The project is licensed under the [MIT License](LICENSE).

- The Basic engine is a port of [NullUpdater](https://github.com/0NullBit0/NullUpdater).
- The Enhanced engine is adapted from Gio470’s TypeScript updater.
- The build uses [Capstone](https://www.capstone-engine.org/),
  [Gson](https://github.com/google/gson), [Kotlin](https://kotlinlang.org/), and
  [ktfmt-gradle](https://github.com/cortinico/ktfmt-gradle).
- The app and launcher icons come from the [Font Awesome Free](https://fontawesome.com)
  solid set and are licensed under [CC BY 4.0](https://fontawesome.com/license/free).
