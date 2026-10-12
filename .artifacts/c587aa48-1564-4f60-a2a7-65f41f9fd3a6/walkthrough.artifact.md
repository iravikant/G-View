# Walkthrough - Fix ExoPlayer ERROR_CODE_TIMEOUT in Video Editor Preview

Fixed the `ERROR_CODE_TIMEOUT` (`StuckPlayerDetector`) exception occurring in `VideoEditorViewModel` during video preview and scrubbing.

## Changes

### Video Editor

#### [Videoeditorviewmodel.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/editor/Videoeditorviewmodel.kt)

- **Auto-Recovery from Timeout**: Added error handling in `Player.Listener#onPlayerError` specifically for `PlaybackException.ERROR_CODE_TIMEOUT`. When triggered, the ViewModel automatically re-prepares the playlist and resumes playback if it was playing, avoiding crashes or disruptive error popups during preview.
- **Throttled Scrubbing Seeks**: Increased the seek throttle threshold during timeline dragging (`scrubbing`) from 40ms to 120ms. This prevents flooding the video decoder with excessive seek commands during rapid finger scrubbing, eliminating decoder starvation and timeout errors.

## Verification Results

### Automated Tests
- Ran `./gradlew app:assembleDebug` successfully. Build passed with zero errors.
