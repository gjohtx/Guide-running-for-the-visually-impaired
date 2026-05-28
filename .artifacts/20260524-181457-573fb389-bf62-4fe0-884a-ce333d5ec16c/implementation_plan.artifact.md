# Refactor: Complete Removal of TFLite and Local AI Models

This plan details the removal of TensorFlow Lite (TFLite) assets, the refactoring of code that relies on these models for obstacle detection, and the cleanup of project dependencies.

## User Review Required

> [!WARNING]
> This refactor will **completely disable** the local TFLite-based obstacle detection and haptic (vibration) feedback associated with it. Obstacle detection logic in `ObstacleDetector.kt` will be replaced with stub/noop implementations to avoid compilation errors while allowing for future remote AI integration (e.g., Gemini Vision already being used in the project).

## Proposed Changes

### 1. Asset Removal
The following files will be deleted from `app/src/main/assets/`:
- `1.tflite`
- `labelmap.txt`
- `obstacles.tflite` (if exists, though not found in current directory listing)

---

### 2. Code Refactoring

#### [ObstacleDetector.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/camera/ObstacleDetector.kt)
- Remove all `org.tensorflow.lite` imports.
- Remove `Interpreter` and TFLite initialization logic.
- Stub out `detect()` to return an empty list.
- Keep the `DetectionResult` and `Category` data classes for compatibility with existing UI/ViewModels.

#### [CameraViewModel.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/camera/CameraViewModel.kt)
- Update `processImage()` to handle the empty results from the refactored `ObstacleDetector`.
- Remove the haptic feedback logic triggered by TFLite detections.
- Ensure the `sceneFrameFlow` still emits bitmaps for the Gemini Vision analysis (`AiChatViewModel`).

#### [RunningFragment.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/ui/running/RunningFragment.kt)
- Clean up comments mentioning TFLite.

---

### 3. Dependency Cleanup

#### [build.gradle.kts](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/build.gradle.kts)
- Remove the following dependencies:
    - `com.google.ai.edge.litert:litert:1.4.0`
    - `com.google.ai.edge.litert:litert-support:1.4.0`

---

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure the project compiles without TFLite references or assets.

### Manual Verification
1. **App Launch**: Verify the app starts without crashing.
2. **Camera View**: Ensure the camera preview still works and frames are being sent to Gemini (if enabled).
3. **Logcat Check**: Verify that "ObstacleDetector" no longer logs initialization errors or inference logs.
4. **Vibration Test**: Confirm that haptic feedback (vibrations) no longer occurs during camera usage (since local detection is removed).
