# GalleryApp

Production-oriented Android Gallery application using Kotlin, Jetpack Compose and Material 3.

## Build configuration

- Android Gradle Plugin: 8.12.1
- Kotlin: 2.2.10
- compileSdk: 36
- targetSdk: 36
- minSdk: 26
- Java/JVM target: 17
- Compose BOM: 2026.04.01
- Activity Compose: 1.11.0
- Core KTX: 1.17.0
- Lifecycle: 2.9.4
- Navigation Compose: 2.9.7
- AndroidX Hilt Navigation Compose: 1.2.0
- Dagger Hilt: 2.57.2
- Hilt annotation processing: KAPT
- WorkManager: 2.10.5
- DataStore: 1.1.7
- Media3: 1.10.1
- Coil 3: 3.3.0

## Important

This project intentionally stays on the API-36 dependency line. Do not upgrade the Compose BOM, Core, Lifecycle, Navigation, AndroidX Hilt Navigation Compose, Coil, or Media3 to API-37-only releases unless the entire build is migrated to a compatible AGP/compileSdk stack.

Use JDK 17. Kotlin 2.2.10 is officially compatible with Gradle through 8.14; for AGP 8.12.x, use the Gradle version supported by that AGP release rather than Gradle 9.x.


## Rotation and list loading

- The activity no longer forces `fullSensor`; screen rotation follows the system auto-rotate setting.
- The media library is loaded on `Dispatchers.IO`, cached in `HomeViewModel`, and refreshed by a MediaStore `ContentObserver`. Filtering, sorting and date grouping run on `Dispatchers.Default` and the grid only renders the prepared rows. Albums are derived from the same cache.

## Building

`./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`. A GitHub Actions workflow (`.github/workflows/build.yml`) builds the same APK on every push.

## Recycle bin

- **Delete** asks "Delete this photo?" and then moves the item to the OS Recycle bin (MediaStore trash, Android 11+). Items are purged by Android after 30 days. On Android 10 and below there is no OS bin, so the dialog says the delete is permanent.
- **Recycle bin** (Albums -> Utilities, or the overflow menu) lists trashed items with a days-left badge and supports Restore, Delete permanently and Empty.
- Android shows its own "Allow Gallery to move…" prompt for media the app didn't create. *Settings -> Delete without extra prompts* opens the system "Media management" switch that removes that second prompt.

## UI

Large titles, round header buttons, a floating **Photos | Albums** pill with a grid-size button on the left and sort/filter on the right, a tight date-grouped grid with video-length badges, a floating action pill while selecting, and rounded album covers with a Pinned section (Recent, Videos, Favourites, Camera, Screenshots).

