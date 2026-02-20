# ShortsBlocker

An Android app that automatically detects and blocks YouTube Shorts using Android's Accessibility Service.

## How It Works

Once enabled, the app monitors the YouTube app in the background. When a Shorts screen is detected, it automatically navigates back — keeping you focused on regular content.

Detection uses two signals:
- **Window class name** — instantly catches Shorts when the screen transitions
- **Accessibility node tree** — scans for Shorts player-specific view IDs (`reel_player`, `reel_watch`, etc.) as a fallback

## Setup

1. Install the APK
2. Open the app and tap **"접근성 설정 열기"** (Open Accessibility Settings)
3. Enable **ShortsBlocker** under Accessibility Services
4. Toggle blocking on/off from the main screen

## Requirements

- Android 8.0 (API 26) or higher
- YouTube app installed (`com.google.android.youtube`)

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Notes

- The app does not collect any data or access the internet
- Blocking can be paused at any time via the toggle switch
- A toast notification appears each time a Shorts video is blocked
