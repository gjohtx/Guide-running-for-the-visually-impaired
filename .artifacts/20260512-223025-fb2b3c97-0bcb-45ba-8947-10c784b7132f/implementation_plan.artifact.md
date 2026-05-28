# Implementation Plan - New Entities and Architecture

This plan covers the implementation of four new data models (`UserProfile`, `RunningSession`, `SafetyEvent`, and `DeviceCheckState`) as requested, following the Room database pattern with DAOs, Repositories, and Tests.

## Proposed Changes

### Data Models & Entities
Create the entity classes in `com.example.guiderunningfortheblind.data.local.entity`.

#### [NEW] [UserProfile.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/entity/UserProfile.kt)
- Implementation of the `UserProfile` entity with fields for age, cadence, vibration, etc.

#### [NEW] [RunningSession.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/entity/RunningSession.kt)
- Implementation of the `RunningSession` entity for tracking run data.

#### [NEW] [SafetyEvent.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/entity/SafetyEvent.kt)
- Implementation of the `SafetyEvent` entity for obstacle detection logs.

#### [NEW] [DeviceCheckState.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/model/DeviceCheckState.kt)
- Plain data class for device status (not a Room entity as per typical usage, but can be persisted if needed).

---

### Room DAOs
Create DAOs in `com.example.guiderunningfortheblind.data.local.dao`.

#### [NEW] [UserProfileDao.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/dao/UserProfileDao.kt)
#### [NEW] [RunningSessionDao.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/dao/RunningSessionDao.kt)
#### [NEW] [SafetyEventDao.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/dao/SafetyEventDao.kt)

---

### Database Update
#### [AppDatabase.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/local/AppDatabase.kt)
- Register new entities and add abstract methods for the new DAOs.

---

### Repositories
Create/Update repositories in `com.example.guiderunningfortheblind.data.repository`.

#### [NEW] [UserProfileRepository.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/repository/UserProfileRepository.kt)
#### [NEW] [RunningSessionRepository.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/main/java/com/example/guiderunningfortheblind/data/repository/RunningSessionRepository.kt)

---

### Testing
Create unit and instrumentation tests.

#### [NEW] [UserProfileDaoTest.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/androidTest/java/com/example/guiderunningfortheblind/UserProfileDaoTest.kt)
#### [NEW] [RunningSessionDaoTest.kt](file:///D:/AndroidProjects/GuideRunningFortheBlind/app/src/androidTest/java/com/example/guiderunningfortheblind/RunningSessionDaoTest.kt)

## Verification Plan

### Automated Tests
- Run Android Instrumented Tests: `./gradlew connectedDebugAndroidTest`
- Specifically check:
    - `UserProfileDaoTest`
    - `RunningSessionDaoTest`
    - `RunningDaoTest` (existing)

### Manual Verification
- Code analysis to ensure all requested fields are present and types match the requirements.
