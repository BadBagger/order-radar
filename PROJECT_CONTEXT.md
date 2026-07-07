# Order Radar Project Context

Order Radar is a local-first Smithware Studios Android MVP for retail, deli,
grocery, food service, and department managers who need practical order
forecasting.

## Identity

- App name: Order Radar
- Package: `com.smithware.orderradar`
- Repo: `https://github.com/BadBagger/order-radar`
- Brand: Smithware Studios
- Tagline: Forecast orders before you run out.

## Current Status

- Latest local build: `v0.2.0`
- APK asset: `OrderRadar.apk` / `OrderRadar-release-v0.2.0.apk`
- DevHub entry: added with package visibility and store listing.
- Logo: user-provided green radar box artwork applied to app and DevHub listing.

## MVP Scope

The MVP is intentionally local-first and manager-focused:

- Manual product counts
- Movement averages
- Truck schedules
- Forecasted order recommendations
- Order builder
- Delivery day variance tracking
- Display forecast tracking
- Recipe/production usage logging
- Copyable reports
- CameraX photo attachment and ML Kit OCR assist with user confirmation

Order Radar does not submit official orders, place AI orders, use cloud sync,
or replace workplace inventory, ordering, food safety, or compliance systems.

## Build Notes

Use the known Windows Android toolchain from prior Smithware app builds:

```powershell
$env:JAVA_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.local-jdk\jdk-17.0.19+10'
$env:ANDROID_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```
