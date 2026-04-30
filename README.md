# Flash Switch

Native Android flashlight app with a React/Tailwind-inspired design: dark zinc off state, white on state, Korean `손전등` app bar, yellow bulb glow, circular power button, and Android-friendly APK build setup.

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
