# AlmaZoom · 1000× Zoom for Samsung Camera

AlmaZoom extends the zoom slider in the stock Samsung Camera app up to **1000× digital zoom**. It works as a Vector module in the rear camera's Photo mode with JPEG.

**[Download AlmaZoom 1.1.2 APK](https://github.com/zz5644934-design/almazoom-x1000/releases/download/v1.1.2/AlmaZoom-v1.1.2.apk)** · [Русская версия](README.ru.md)

Requires a rooted Samsung Galaxy with [Vector](https://github.com/JingMatrix/Vector) already set up.

## Compatibility

| Device | Samsung Camera | Status |
| --- | --- | --- |
| **Galaxy S25 Ultra (SM-S938U1)** | **17.0.00.43** | Camera hooks checked against this build. A previous version showed extended zoom on-device. JPEG saving in 1.1.2 still needs an on-device check. |
| Other Galaxy models or Camera versions | Varies | Untested. Check your exact Camera version before reporting compatibility. |

## Install

1. Install the [AlmaZoom APK](https://github.com/zz5644934-design/almazoom-x1000/releases/download/v1.1.2/AlmaZoom-v1.1.2.apk).
2. Enable **AlmaZoom** in Vector and set its scope to **Samsung Camera** (`com.sec.android.app.camera`).
3. Fully close Samsung Camera and reopen it. In **Photo**, use the rear camera and JPEG, such as the standard 12 MP setting with HEIF off.
4. Open the zoom slider and scroll past the stock limit. The magnifier in Camera's quick controls toggles extended zoom.

## What happens above the stock limit

Samsung Camera shoots and processes the original at its own maximum. AlmaZoom crops the processed JPEG around the same center as the enlarged preview before Gallery registration. At 400× over a 100× base, the crop uses the central quarter of the image in each dimension. The output JPEG's longest edge is capped at 2048 pixels.

Version 1.1.2 sizes the added slider range to match the camera's stock limit. Slider math was tested at 10×, 20×, 30×, 50× and 100×. Camera support also depends on the preview and JPEG-saving hooks in the installed Camera build.

## Build from source

Use JDK 17 and Android SDK Platform 35. On Windows, run the same task with `gradlew.bat`.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Installing over a previous build requires the same signing certificate.

## Report a device test

Photograph a fixed subject at the stock maximum, then at twice that value on the AlmaZoom slider. Compare the saved JPEGs in Gallery. Include the model, Android version, Samsung Camera version and matching `AlmaZoom` lines from the Vector log.

- `CAMERA_PROFILE_UNAVAILABLE` reports an incompatible camera profile.
- `CROPPED_BEFORE_PUBLICATION` confirms the module cropped the JPEG.
- `SAMSUNG_SCAN_RETURNED uriPresent=true` confirms Samsung's scanner returned an image URI.
