# Fix ExoPlayer ERROR_CODE_TIMEOUT in Video Editor Preview

The application encounters a `PlaybackException.ERROR_CODE_TIMEOUT` (`StuckPlayerDetector` timeout) in the video editor preview (`VideoEditorViewModel`), causing playback or scrubbing to fail with an unexpected runtime error.

## User Review Required

> [!NOTE]
> This fix adds automatic recovery when `ERROR_CODE_TIMEOUT` occurs and optimizes timeline scrubbing to prevent decoder starvation.

## Proposed Changes

### Video Editor Component

#### [MODIFY] [Videoeditorviewmodel.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/editor/Videoeditorviewmodel.kt)
- Catch `PlaybackException.ERROR_CODE_TIMEOUT` in `Player.Listener#onPlayerError` and automatically recover by re-preparing the playlist and resuming playback if it was playing.
- Throttle seek requests during timeline scrubbing (`seekOut`) from 40ms to 120ms to prevent overwhelming the decoder with rapid seek calls when dragging the scrub bar.

## Verification Plan

### Automated Tests
- Run `./gradlew app:assembleDebug` to verify compilation.

### Manual Verification
- Open a video in the Video Editor.
- Scrub rapidly across the timeline and play/pause.
- Verify that no `ERROR_CODE_TIMEOUT` crash occurs, or if a timeout occurs, it recovers automatically.
