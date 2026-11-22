# PlainPDF

A small, local-first Android PDF viewer written in Kotlin. The app deliberately has no accounts, ads, analytics, cloud services or network permission.

## Current scope (0.1.0)

- Open a PDF through Android's system document picker.
- Open a PDF from another app using Android's `VIEW` or `SEND` intent.
- Render pages with Android `PdfRenderer`.
- Page navigation and pinch zoom.
- Extract readable text from the current page with local Tesseract OCR and copy it to the clipboard.
- Run local Tesseract OCR over all pages for the privacy scan.
- Suggest email, Indian mobile number, Aadhaar and PAN redactions.
- Let the user add an exact phrase/name to redact.
- Export a new flattened PDF with selected regions painted into the page image. The original PDF is never modified.

## Build

### GitHub Actions

A workflow is included at `.github/workflows/android.yml`. Push the project to a GitHub repository (with the contents of this `PlainPDF` folder at the repository root), then open the repository's **Actions** tab. The workflow runs on pushes, pull requests and manual dispatches. It installs JDK 17, Android SDK Platform 36 and Gradle 8.13, builds the debug APK, and uploads it as the `PlainPDF-debug-apk` artifact.

Download the artifact from the completed workflow run. The APK is at `app/build/outputs/apk/debug/app-debug.apk` inside the artifact.

### Android Studio (local build)

1. Install a recent Android Studio and Android SDK Platform 36.
2. Open this folder in Android Studio.
3. Use JDK 17 for Gradle.
4. This source snapshot includes the Gradle 8.13 wrapper configuration, but not the wrapper launcher JAR/scripts. If your Android Studio setup requires them, run `gradle wrapper --gradle-version 8.13` from this folder using an installed Gradle 8.13, then reopen the project.
5. Sync Gradle and run the `app` configuration on an Android 8.0+ device.

The first build requires internet to download development dependencies and SDK components. The installed app itself has no `INTERNET` permission and does not need a network connection at runtime.

## Bundled OCR language models

English and Hindi Tesseract models are already bundled in `app/src/main/assets/tessdata/`.

- `eng.traineddata` — English
- `hin.traineddata` — Hindi / Devanagari

Both are Tesseract `tessdata_fast` models under Apache-2.0. The app copies them into private app storage on first OCR use. It does not download language data. Add other language models to the same assets folder and include their language codes in `LocalOcrEngine` if needed.

## Privacy and redaction notes

- No broad storage permission is requested. The user picks a document and Android grants access to that URI.
- There is intentionally no `INTERNET` permission in the manifest.
- OCR runs on the device using Tesseract 5.
- Automatic detection is pattern-based. It can miss or misread details; always inspect the pages before sharing.
- Names are not guessed. Enter a name or any exact text in the custom field to locate and redact it.
- Export rasterizes every page and draws the selected redactions directly into the raster. The original PDF's text objects and metadata are not copied into the new PDF. This is intentionally destructive and makes the output image-based: selectable text, links, forms and vector detail are not retained.
- The redacted export is a new file. The source PDF is not changed.

## Important limitations of this first build

- Password-protected PDFs may not open through the Android renderer.
- OCR quality depends on scan quality, language data and page orientation.
- The first version runs English and Hindi OCR. Other languages are not bundled yet.
- Large PDFs can take time to scan and produce larger image-based exports.
- A user must review the result before sharing; automated OCR is not a guarantee that every sensitive detail was found.

## Main components

- `core/PdfDocumentSession.kt` — PDF rendering and flattened redacted export.
- `core/LocalOcrEngine.kt` — on-device Tesseract adapter.
- `core/PrivacyScanner.kt` — local pattern detection and normalized OCR boxes.
- `ui/PlainPdfApp.kt` — viewer and privacy workflow.

## Licence notes

PlainPDF's source is provided as a starter project. Tesseract4Android, Tesseract and the bundled English/Hindi trained-data models are Apache-2.0. The licence text is included in `THIRD_PARTY_LICENSES/Apache-2.0.txt`. Preserve applicable third-party notices when distributing the app.
