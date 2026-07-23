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

- Latest published APK release: `v0.4.3-delivery-check`
- Current local app version: `0.8.1-ollama-compare` / versionCode `22`
- APK asset from latest published release: `OrderRadar.apk` / `OrderRadar-release-v0.4.3-delivery-check.apk`
- DevHub entry: added with package visibility and store listing.
- Logo: user-provided radar box artwork toned down from neon lime to muted emerald for app and DevHub listing.
- Deli inventory-to-order workflow has a mobile-first scan session surface in the Deli tab: start/reset session, choose source type, tag inventory location, queue reviewed OCR/manual text, build a parsed batch, display queued/running/needs-verification/complete progress, and show parsed source, inventory, promo, order-line, verify, and note counts.
- Deli review includes editable inventory rows, merge review for matching parsed labels, editable supplier order-screen rows with ordering controls, order reconciliation cards, expiry radar buckets, low-confidence verify routing, copyable expiry/order CSV, and shareable session summary text.
- The Deli tab bottom navigation and dense review cards have been tightened for phone widths: the bottom bar only labels the selected tab, progress pills wrap instead of truncating, and action/bucket labels no longer squeeze the main card text.
- Remaining deli workflow gaps: direct Deli tab camera/gallery capture, async OCR queue persistence, enabled PDF export, measured camera/OCR/model accuracy, and durable saved session history browsing.

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
- Deli order reconciliation prototype for inventory, promo, supplier order screen, expiry radar, production hints, low-confidence verify routing, and per-line ORDER/TRIM/SKIP/VERIFY reasons
- Deli scan session UI for reviewed OCR/manual batch parsing with source and location tagging before order review
- Deli CSV/share exports generated from reviewed in-app rows

Order Radar does not submit official orders, place AI orders, use cloud sync,
or replace workplace inventory, ordering, food safety, or compliance systems.

## Deli Parser Support

Current deli support is deterministic parser support, not validated camera accuracy.
The implemented parser handles reviewed text lines for:

- inventory labels with SKU, case count, pack/case weight, use-by/expiry date, brand/vendor hints, location, category inference, duplicate merging, and confidence/verify routing
- loose deli backstock lines without a SKU, routed to `UNKNOWN-*` SKUs and verification
- promo/ad lines with SKU, common deal type markers, price/discount hints, and default ad dates
- supplier order-screen rows with SKU, description, pack size, suggested cases, page order, forecast/safety editable fields, and reconciliation
- sticky/note lines that affect review context but do not place official orders

Future validation still needs real Deli tab capture, gallery/photo ingestion, OCR/model
accuracy measurements on workplace-like photos, and comparison against real manager
review outcomes. Do not market this as reliable automated OCR ordering.

## Build Notes

Use the known Windows Android toolchain from prior Smithware app builds:

```powershell
$env:JAVA_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.local-jdk\jdk-17.0.19+10'
$env:ANDROID_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
.\gradlew.bat test
.\gradlew.bat :app:assembleRelease
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose app\build\outputs\apk\release\app-release.apk
```
