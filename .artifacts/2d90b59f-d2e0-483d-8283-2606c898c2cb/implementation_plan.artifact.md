# Replace PdfGenerator with PdfCreator

Replace the legacy `PdfGenerator` utility with the robust `PdfCreator` service, supporting customizable page sizes (`A4`, `Letter`, `Original`) and qualities (`High`, `Medium`, `Low`), fallback decoding for API 26-27, progress reporting, and reliable saving via `ExportManager`.

## User Review Required

> [!NOTE]
> We will introduce an interactive PDF creation dialog allowing users to select their preferred **Page Size** and **Quality** before generating the PDF from selected images.

## Proposed Changes

### Core & Domain
#### [MODIFY] [PdfCreator.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/core/pdf/PdfCreator.kt)
- Update `PdfCreator` to return a temporary `File` (ready for `ExportManager.saveToDownloads`), support API 26+ via `ImageDecoder` (API 28+) and `BitmapFactory` fallback (API 26-27), handle progress callbacks, and ensure active coroutine cancellation.

#### [DELETE] [PdfGenerator.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/core/pdf/PdfGenerator.kt)
- Remove obsolete `PdfGenerator.kt`.

### ViewModel & Presentation
#### [MODIFY] [HomeViewModel.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/home/HomeViewModel.kt)
- Inject `PdfCreator` into `HomeViewModel` and add a `createPdf` function supporting `PdfPageSize` and `PdfQuality`.

#### [MODIFY] [MediaScreen.kt](file:///C:/Users/91827/Downloads/GView/GalleryApp/app/src/main/java/com/acoder/gallery/presentation/media/MediaScreen.kt)
- Update PDF confirmation dialog to allow selecting page size and quality options, and invoke `vm.createPdf(...)` with progress indication.

## Verification Plan

### Automated Tests
- Build project successfully using `./gradlew :app:assembleDebug`.

### Manual Verification
- Select one or more images in the gallery.
- Click the PDF action icon.
- Choose page size and quality in the configuration dialog.
- Verify PDF generation, saving to Downloads/cache, sharing, and error handling.
