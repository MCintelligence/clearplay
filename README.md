# Clearplay for Android

An ad-free video and music player built on Media3 / ExoPlayer, the same free
Google player library that Vidy Player uses, without the ads, tracking or paywalls.

- **No internet permission.** The app can't go online, so it can't show ads or send data anywhere.
- **Plays:** MP4, M4A, MKV, WebM, MOV, 3GP, TS, MP3, AAC, FLAC, OGG/Opus, WAV, and more.
  Audio-only files (like .m4a) show a music artwork screen.
- **Open with:** appears in "Open with" and "Share" for any video or audio file.
- **Queue:** pick several files at once, plus shuffle and repeat (one or all).
- **Subtitles:** add a .srt, .vtt or .ass file. Built-in subtitle tracks work too, via the CC button.
- **Speed:** 0.25x to 2x, plus audio and subtitle track selection (gear icon in the controls).
- **Double-tap:** left side rewinds 10 s, right side skips ahead 10 s, middle plays or pauses.
- **Picture size:** Fit, Crop or Stretch. A rotate button locks landscape.
- **Resume:** each file picks up where you left off.
- **Background play:** keeps playing with the screen off, with lock-screen, notification and headset controls.
- **Picture-in-picture:** leaving the app while a video plays keeps it in a floating window.

## Option A: let GitHub build the APK (no Android Studio needed)

1. Create a new repository on github.com and upload everything in this folder
   (keep the hidden `.github` folder).
2. Open the **Actions** tab. The "Build APK" workflow runs on its own, or click **Run workflow**.
3. After about 5 minutes, open the finished run and download **Clearplay-apk** under *Artifacts*.
4. Unzip it, copy `app-release.apk` to your phone, open it, and allow "Install unknown apps" when asked.

## Option B: Android Studio

1. Install Android Studio (free): https://developer.android.com/studio
2. **File > Open** and pick this folder. Let Gradle sync finish (the first time downloads the SDK).
3. Plug in your phone with USB debugging on and press **Run**, or use
   **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

From a terminal: `./gradlew assembleRelease`. The APK lands in `app/build/outputs/apk/release/`.

## Notes

- The release build is signed with the debug key so it installs right away. That's fine for
  personal use. Create your own signing key before sharing it or publishing it.
- Format support depends on your phone's hardware decoders. Some MKV files carry AC-3, E-AC-3 or DTS
  audio that many phones can't decode, so you get a picture with no sound. The fix is Media3's
  FFmpeg extension, which has to be built from source:
  https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg
  Clearplay already prefers extension decoders, so you only need to add the module.
