# Android app (step 8.2)

The Android project lives in `android/` and is a separate Gradle build (its own Gradle and plugin versions, so it does not
touch the server build). The three shared modules `core`, `crypto` and `client-core` are compiled straight into the app from
`../core/src/main/java` and so on. The Android compiler therefore checks every API they use against the Android SDK.
Edit the shared code in its own module, both builds see the same files.

## First start
1. Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` from the repository root into `android/` (same names,
   the `gradle/wrapper/gradle-wrapper.properties` of the archive stays: it selects Gradle 9.3.1).
2. In Android Studio choose **File > Open** and open the `android` folder (not the repository root). Accept the offer to
   install the missing SDK platform 36 and build tools.
3. On the phone: Settings > About phone > tap the build number 7 times, then Developer options > USB debugging. Connect with a
   cable and confirm the prompt on the phone.
4. Run the `app` configuration. The screen shows the uid and whether the state was restored from disk; the button
   "Run crypto self-test on this device" runs every crypto step on the device.
5. Instrumented test: open `DeviceCryptoTest`, run it, or `./gradlew :app:connectedDebugAndroidTest` in `android/`.

## Versions (all in `android/build.gradle` and `android/app/build.gradle`)
Android Gradle plugin 9.1.1 (needs Gradle 9.3.1 and JDK 17+), Kotlin 2.4.20 with the Compose compiler plugin of the same
version (AGP 9 has built-in Kotlin, there is no `kotlin-android` plugin), Compose BOM 2026.04.01, compileSdk and targetSdk 36,
minSdk 30 (Android 11). If Android Studio says it cannot use this AGP, update Android Studio or lower the AGP version.

## What is in the app now
- `KeystoreMasterKey`: a random 32-byte master key, stored only wrapped by an AES-GCM key that lives in the Android Keystore.
- `StateRepository`: opens the encrypted state file (`state.bin` in the private files directory) or creates the identity;
  `encryptFor` saves before the ciphertext is returned, `decrypt` saves before the caller may acknowledge (docs/state.md).
  A damaged file or a lost Keystore key throws, it never creates a new identity silently.
- `SelfTest` and `DeviceCryptoTest`: signatures, key agreement, key derivation, sealed state, a pairwise chat and a group chat,
  both surviving a simulated process kill.
- `allowBackup=false`: the state file is useless without this device's Keystore key.

## Next (8.3)
A client engine shared with the simulator (connection, sync, send, hold and retry, acknowledgements after saving) and the
first real screens. The network permission and the OkHttp WebSocket are added there.
