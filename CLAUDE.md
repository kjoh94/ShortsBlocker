# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ShortsBlocker (쇼츠 차단기) is an Android utility app that automatically detects and blocks YouTube Shorts using Android's Accessibility Service. It monitors the YouTube app's accessibility tree for Shorts-related content and performs automatic back navigation when detected.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug build on connected device
./gradlew installDebug

# Clean build
./gradlew clean
```

There are no tests configured in this project.

## Architecture

Single-module app (`app/`) with two core components in `com.shortsblockr`:

- **MainActivity.java** — Single activity with a toggle switch to enable/disable blocking. Manages state via SharedPreferences (`ShortsBlockerPrefs` / `enabled` key) and directs users to system accessibility settings.
- **ShortsBlockerService.java** — Accessibility service that monitors `com.google.android.youtube` for window state/content changes. Detects Shorts by recursively traversing the accessibility node tree (up to 6 levels deep) looking for content descriptions, view IDs, or class names containing "shorts" or "reel_player". Triggers `GLOBAL_ACTION_BACK` with a 1500ms cooldown between blocks.

The accessibility service is configured in `res/xml/accessibility_service_config.xml` targeting the YouTube package with window state and content change event types.

## Build Configuration

- **Language**: Java 8
- **SDK**: minSdk 26, compileSdk/targetSdk 34
- **Gradle**: 9.0.0, AGP 8.13.2
- **Dependencies**: Only `appcompat:1.6.1` and `material:1.11.0`
- **Localization**: All UI strings are in Korean (res/values/strings.xml)
