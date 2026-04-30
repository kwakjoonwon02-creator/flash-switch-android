# Flash Switch

Native Android flashlight app built from the Material 3-style Android frame/toggle design in this folder.

## Build locally

```bash
./gradlew assembleDebug --console=plain
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build with Codemagic

1. Push this folder as the **repository root** on GitHub.
2. Open <https://codemagic.io> and choose **Add application**.
3. Select the GitHub repository.
4. Codemagic will detect `codemagic.yaml`.
5. Start the `Android Debug APK` workflow.
6. Download the APK from the build **Artifacts** section.

The Codemagic workflow uses JDK 17 and runs:

```bash
./gradlew assembleDebug --console=plain --info
```
