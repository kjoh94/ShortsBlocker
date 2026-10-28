# ShortsBlocker

An Android app that automatically detects and blocks YouTube Shorts using Android's Accessibility Service.

## How It Works

Once enabled, the app monitors the YouTube app in the background. When a Shorts screen is detected, it navigates back — unless that Short is from an allowlisted channel.

Detection uses two signals:
- **Window class name** — instantly catches Shorts when the screen transitions
- **Accessibility node tree** — scans for Shorts player-specific view IDs (`reel_player`, `reel_watch`, `shorts_player`, `shorts_container`) as a fallback

Before pressing Back, the service checks the channel. Shorts from an allowlisted channel stay open: no Back press and no toast. Every other Shorts screen is still blocked.

## Allowlisted channels

Default channel, used until the list is edited:

| Signal | Value |
| --- | --- |
| Channel name | `adrenalGr8` |
| Handle | `@adrenalgr8964` (also matches `adrenalgr8964`) |
| Channel ID | `UC6WHTOfNwh-69D_4EXRFgkg` |

The list is one entry per line in SharedPreferences (`ShortsBlockerPrefs` / `channel_allowlist`). If that key is missing, the defaults above are used (`AllowlistMatcher.DEFAULT_ENTRIES` and `res/values/arrays.xml`). Edit it on the main screen. Add a line later to allow another channel. Saving an empty list blocks every Short again; **기본값** restores the defaults.

Matching is case-insensitive. The service reads text, content descriptions, and channel-related view IDs:

- Allowlisted name, `@handle`, or channel ID visible → do not block. A visible allowlist hit wins over another channel that is also on screen.
- A different `@handle`, a subscribe / "open channel" label for someone else, or another name on a channel view ID → block immediately.
- Channel not identified yet → wait briefly and retry, so a slow-loading own channel is not closed. If it is still unknown (about 0.8s, a little longer right after an allowlisted Short), block anyway.

YouTube often does not put the channel ID or @handle on the Shorts overlay, so the display name does most of the work. Keep the display name in the list. View IDs change between YouTube versions; known IDs are probed and any ID that looks like channel chrome is scanned too. Off-screen Shorts in the pager are ignored when their bounds are off the display or Android marks them not visible. A short title or comment that contains the allowlisted name, or any text that contains the @handle or channel ID, can suppress a block. If you leave a class-name-only Shorts screen before the label loads, and YouTube does not open a new window, one extra Back can still fire within that short wait.

## Setup

1. Install the APK
2. Enable **쇼츠 차단기** under Settings → Accessibility → Downloaded apps
3. Blocking runs while the accessibility service is on. Pause it with the PIN button (60 seconds or 5 minutes)
4. Shorts from the allowlisted channel stay open. Edit that list on the main screen if needed

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
- Blocking can be paused with the PIN button; it turns back on when the timer ends
- A toast appears each time a Shorts video is blocked. Allowlisted channels do not toast and are not closed
