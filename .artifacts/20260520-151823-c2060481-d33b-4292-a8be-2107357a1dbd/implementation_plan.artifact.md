# Implementation Plan - Refactor and Stabilize GuideRunningFortheBlind App

This plan outlines the steps to refactor the project to use modern Android practices (Hilt DI, Foreground Services) and improve the robustness of AI-powered features.

## Proposed Changes

### Android Manifest and Core Components

#### [AndroidManifest.xml](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/AndroidManifest.xml)
- Add Foreground Service permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_CAMERA`.
- Register `RunningService` with `foregroundServiceType="location|camera"`.

#### [NEW] [RunningService.kt](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/service/RunningService.kt)
- Implement a `Service` annotated with `@AndroidEntryPoint`.
- Handle foreground notification to keep the app alive during running sessions.
- Placeholder for Location and CameraX integration.

#### [MainApplication.kt](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/MainApplication.kt)
- Annotated with `@HiltAndroidApp`.
- Remove manual property initialization (lazy database, repositories, etc.) as they will be handled by Hilt.

### Dependency Injection

#### [NEW] [AppModule.kt](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/di/AppModule.kt)
- Define a Hilt module to provide:
    - `AppDatabase` and DAOs.
    - `RunningApiService` using Retrofit.
    - Repositories (e.g., `RunningSessionRepository`).

### UI and ViewModel Refactoring

#### [HistoryDetailViewModel.kt](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/ui/history/detail/HistoryDetailViewModel.kt)
- Refactor to `@HiltViewModel`.
- Use `SavedStateHandle` to receive `sessionId`.
- Remove manual `Factory`.

### AI Robustness

#### [AiRoutePlanner.kt](file:///D:/Andriod/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/ai/AiRoutePlanner.kt)
- Improve `planRoute` to handle non-JSON responses from Gemini using a regex-based JSON extractor.
- Ensure the prompt explicitly asks for JSON only.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project still compiles after the refactor.
- (Optional) Run existing unit tests if any.

### Manual Verification
- Verify the existence of the new files.
- Inspect the refactored code for potential issues (missing imports, type mismatches).
