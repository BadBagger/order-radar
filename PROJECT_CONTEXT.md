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

- Latest local build: `v0.4.3-delivery-check`
- APK asset: `OrderRadar.apk` / `OrderRadar-release-v0.4.3-delivery-check.apk`
- DevHub entry: added with package visibility and store listing.
- Logo: user-provided radar box artwork toned down from neon lime to muted emerald for app and DevHub listing.
- Deli inventory-to-order workflow first slice is scaffolded locally: domain models,
  reconciliation rules, deterministic text-parser helpers, unit tests, and a Deli tab
  sample view are in place. Real batch photo upload, async OCR/vision extraction,
  offline upload queueing, PDF/CSV export, and measured camera accuracy still need
  production wiring before this should be treated as a complete deli workflow.

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
- Photo-scanned order drafts that can be edited, copied, and marked placed
- Forecast recommendation cards that add or update editable order draft lines
- Placed order drafts create delivery checklists with adjustable received quantities
- Deli order reconciliation prototype for inventory, promo, supplier order screen,
  expiry radar, production hints, low-confidence verify routing, and per-line
  ORDER/TRIM/SKIP/VERIFY reasons

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
