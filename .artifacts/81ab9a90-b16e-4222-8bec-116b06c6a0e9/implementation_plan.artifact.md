# Fix Build Errors Across App Files

Resolve compiler errors and unresolved references resulting from recent library/code updates across Coil 3 video extensions, Material icons, modifiers, and experimental APIs.

## User Review Required

> [!IMPORTANT]
> The fixes address broken imports for Coil 3 video extensions (`coil3.video`), corrected Outlined icon references, proper Compose `clickable` modifier usage, and experimental Material API opt-in.

## Proposed Changes

### Core & Application
#### [MODIFY] [GalleryApplication.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/GalleryApplication.kt)
- Update `VideoFrameDecoder` import from `coil3.decode.VideoFrameDecoder` to `coil3.video.VideoFrameDecoder`.

### Presentation
#### [MODIFY] [MediaScreen.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/media/MediaScreen.kt)
- Update `videoFrameMillis` import from `coil3.request.videoFrameMillis` to `coil3.video.videoFrameMillis`.

#### [MODIFY] [GalleryApp.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/home/GalleryApp.kt)
- Fix outlined icon imports and references (`PhotoLibraryOutlined`, `CollectionsOutlined`, `SettingsOutlined`) using standard `androidx.compose.material.icons.outlined.*` imports or available outlined icons (`Image`, `Folder`, `Settings`).

#### [MODIFY] [SettingsScreen.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/settings/SettingsScreen.kt)
- Import `androidx.compose.foundation.clickable` and correct `clickableListItem` implementation to use `Modifier.clickable(onClick = onClick)`.

#### [MODIFY] [MediaViewerScreen.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/viewer/MediaViewerScreen.kt)
- Add `@OptIn(ExperimentalMaterial3Api::class)` to `MediaViewerScreen` to resolve experimental Material API compilation errors.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify successful compilation of all modules and files.
