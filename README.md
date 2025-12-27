# Akashvani Patna Live - Android Radio Streaming App

A simple, stable Android app for streaming Akashvani Patna live HLS audio using Media3 ExoPlayer.

## Features

-  Single-screen interface with two large buttons (PLAY/STOP)
-  High contrast UI for accessibility
-  Auto-reconnect on stream failures
-  Keeps screen awake during playback
-  Optimized for low-end Android devices
-  Proper lifecycle management
-  No ads, no menus, minimal design

## Requirements

- Android Studio (latest version recommended)
- Minimum SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)
- Internet connection for streaming

## Setup Instructions

1. **Open the project** in Android Studio
2. **Sync Gradle** - Android Studio will automatically download dependencies
3. **Update the HLS Stream URL** (if needed):
   - Open `app/src/main/java/com/akashvani/patna/live/MainActivity.kt`
   - Find the `hlsStreamUrl` variable (around line 70)
   - Replace with the actual Akashvani Patna HLS stream URL
4. **Build and Run** the app on your device or emulator

## Dependencies

All dependencies are already configured in `app/build.gradle`:

- Media3 ExoPlayer 1.2.0
- Media3 ExoPlayer HLS 1.2.0
- Media3 UI 1.2.0
- AndroidX Core & AppCompat
- Material Design Components

## Permissions

The app requires the following permissions (already declared in `AndroidManifest.xml`):

- `INTERNET` - Required for streaming audio
- `WAKE_LOCK` - Keeps device awake during playback
- `FOREGROUND_SERVICE` - For media playback service
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` - Media playback service type

## Usage

1. Launch the app
2. Tap **PLAY** to start streaming
3. Tap **STOP** to stop playback
4. The app will automatically retry if the stream fails (up to 5 attempts)

## Project Structure

```
app/
├── src/main/
│   ├── java/com/akashvani/patna/live/
│   │   └── MainActivity.kt          # Main activity with ExoPlayer logic
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml    # UI layout
│   │   └── values/
│   │       ├── strings.xml          # String resources
│   │       └── colors.xml           # Color resources
│   └── AndroidManifest.xml          # App manifest with permissions
└── build.gradle                     # App-level build configuration
```

## Notes

- The app uses HLS (HTTP Live Streaming) protocol for audio streaming
- Stream URL is currently set to a placeholder - update it with the actual Akashvani Patna stream URL
- The app handles network errors gracefully with automatic retry logic
- Screen stays awake while playing to prevent interruption

## Troubleshooting

- **Stream not playing**: Check your internet connection and verify the HLS URL is correct
- **App crashes**: Ensure all dependencies are synced in Android Studio
- **Buttons not responding**: Check that the app has INTERNET permission granted

## License

This project is provided as-is for educational and personal use.

