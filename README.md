# freeSticker

An Android app for making your own stickers from photos, videos and GIFs, and adding them to WhatsApp as sticker packs.

## Features

- **Browse your phone's media** – search photos, videos and GIFs by name or album.
- **Stickers from photos** – crop (pinch / drag), square, rounded or circle shape, white outline, caption.
- **Background removal** – on-device subject cut-out (ML Kit Subject Segmentation); the outline then follows the subject's shape.
- **Animated stickers** from videos, GIFs and animated WebP – trim, frame rate, speed, and all of the above, per frame.
- **GIF maker** – turn a clip into a GIF, saved to `Pictures/StickerMaker` and ready to share.
- **Sticker packs** – static and animated packs that you add to WhatsApp (or WhatsApp Business) with one tap. Packs with fewer than 3 stickers are padded with blank stickers, because WhatsApp requires at least 3.

Stickers are encoded to WhatsApp's requirements: 512×512 WebP, ≤ 100 KB static, ≤ 500 KB / ≤ 10 s animated, 96×96 tray icon.

## Building

Requirements: Android Studio (recent), Android SDK 37, JDK 21 (bundled with Android Studio).

1. Open the project folder in Android Studio and let Gradle sync.
2. Run the `app` configuration on a device or emulator (Android 10 or newer).

Or from the command line:

```
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests (JVM + Robolectric)
```

Background removal needs Google Play services on the device; the model downloads automatically the first time it is used.

## How it works (short version)

- `media/` – rendering (shape, outline, caption), a hand-written animated WebP muxer and parser, a GIF encoder, video/GIF/WebP frame sources, background removal.
- `packs/` – pack storage in app-private files, plus the blank "filler" stickers.
- `whatsapp/StickerContentProvider` – the content provider WhatsApp reads packs from (same contract as WhatsApp's official sample app).

## Disclaimer

This is an independent project. It is not affiliated with, endorsed by, or sponsored by WhatsApp or Meta. "WhatsApp" is a trademark of its owner and is used here only to describe compatibility.
