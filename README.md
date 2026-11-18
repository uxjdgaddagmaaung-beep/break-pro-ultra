# BREAK PRO ULTRA

All-in-one creative suite for Android (Kotlin, Compose, Room). Built in phases.

## Status: v0.2.0
Working in code: dashboard (16 sections), Add Project (9 types), project list/delete (Room),
media import per project (Storage Access Framework), crash log to `filesDir/logs/crash.log`.

Video Editor: multi-clip timeline, trim, split, reorder, delete, preview, real MP4 export (Media3 Transformer, 480/720/1080p) to Movies/BreakPro.
Photo Editor: brightness, contrast, saturation, B&W/Sepia/Invert, rotate, JPG export to Pictures/BreakPro.
Video clip effects (applied at export): B&W, brightness, contrast, rotate, mute, caption text.
Audio Editor: multi-track join, trim, reorder, play, export AAC (.m4a) to Music/BreakPro.
Poster Maker: ratios 1:1/4:5/9:16/16:9, draggable text layers, colors, PNG export to Pictures/BreakPro.
GIF Studio (GIF + Animation projects): images -> animated GIF (frame order, delay, size, aspect), custom GIF89a encoder (216-colour palette), saved to Pictures/BreakPro.
PDF Studio: images -> multi-page PDF (A4 or image-size pages, margin), via android.graphics.pdf.PdfDocument, saved to Documents/BreakPro.
Vector/Logo Maker: rect/ellipse/triangle/text, drag, resize, colours, layer order, SVG export to Documents/BreakPro.
Asset Library: install ZIP packs (manifest.json with required license; zip-slip + size protected), preview images, favorites, delete pack. No online marketplace, no 'apply' into editors yet.
Photo: EXIF orientation fix, centre-crop (1:1, 4:5, 16:9, 9:16), flip.
Projects: rename, duplicate (copies media references).
Settings: version, cache size/clear, crash log viewer/clear.
Export Manager: history of finished exports (history only, no render queue).
Poster: undo/redo (50 steps). Video: duplicate clip, default export resolution (Settings).
Settings: backup/restore projects (names, types, media references as JSON).
Photo: temperature + tint. Search in Asset Library and project list.
Photo histogram. GIF: reverse + ping-pong. Vector/Poster: duplicate layer.
Poster: export PDF. Audio: copy track.
Settings: dark/light theme.
Poster: center text H/V (approximate). Vector: center shape on artboard.
Video caption: colour + size (export only).
minSdk 29.

Not implemented yet: everything else (editors, export, effects, AI, marketplace).
Dashboard tiles for those show a "not implemented" message with the planned phase.

## Build in the cloud (no Android Studio needed)
Push to GitHub. The workflow `.github/workflows/android.yml` builds a debug APK on every push.
Open the repo's Actions tab -> latest run -> download artifact `app-debug-apk`.
If the build fails, the red step shows the compile errors; copy them to get them fixed.

## Build locally
Open in Android Studio (JDK 17), let it sync, then Run. CLI:

    ./gradlew assembleDebug   (Gradle wrapper 8.7 is included)

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Roadmap
See phases 1-10 in the project spec. Remaining modules (core, media-engine, timeline-engine, ...)
get split out as each phase lands.
