# Building the plugin

Built from the ATAK CIV SDK plugin template with the takdev Gradle plugin.

1. Unzip the ATAK CIV 5.7.0 (or 5.8.0) SDK and put this folder in the SDK's `samples/`
   folder, so takdev finds `../../main.jar` and `../../android_keystore`.
2. Copy `template.local.properties` to `local.properties` and set `sdk.dir` to your Android SDK.
3. Build with JDK 17:

   ```
   ./gradlew assembleCivDebug
   ```

   The APK is in `app/build/outputs/apk/civ/debug/`.

Debug builds are signed with the SDK's development keystore and only load in a developer ATAK.
Regular ATAK CIV (Play Store or TAK.gov) needs the plugin signed through TAK.gov's third-party
pipeline; the signed 1.3 APKs are attached to the v1.0 release.

Plugin version and target ATAK version are set in `app/build.gradle` (`PLUGIN_VERSION`, `ATAK_VERSION`).
