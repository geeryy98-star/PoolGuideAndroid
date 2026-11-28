# Pool Guide Android

Standalone Android project for the Redmi Note 13 Pro 5G / Android 16.

## Current implementation

- Full-screen capture using Android MediaProjection.
- Floating control panel to show/hide the trajectory layer.
- Background frame analysis on a worker thread.
- Experimental table and ball detection.
- Geometric shot planning with direct paths and cushion rebounds.
- Transparent, non-touchable trajectory layer over the game.

## Build

The repository root is the Gradle project:

```bash
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions can build it from the **Build Pool Guide APK** workflow.

## Important validation status

This is an experimental implementation, not yet a validated playing aid. Table and ball detection are heuristic and may fail on some game layouts, screen ratios, lighting, animations, or overlays. A successful Gradle build only proves that the APK compiles; shot accuracy and performance still need to be checked on the target phone with real 8 Ball Pool captures. Do not treat a displayed line as a guaranteed pot.
