# Deli Workflow Handoff

## Shipped In This Integration Pass

- Deli tab scan-session workflow accepts reviewed manual/OCR text sources for inventory, promo, supplier order-screen, and note inputs.
- Sources are tagged with source type and inventory location, queued, built into a `DeliScanSession`, summarized, and saved through the repository path.
- Parsed inventory rows can be reviewed and corrected in-app, including SKU, name, category, location, case weight, use-by date, vendor, verified status, and +/- 0.5 case adjustments.
- Matching parsed labels are grouped with an explicit merge-review action before combining case counts.
- Supplier order-screen rows can be reviewed in page order, corrected, and moved up/down before reconciliation.
- Reconciliation produces ORDER/TRIM/SKIP/VERIFY cards, expiry radar buckets, low-confidence verify routing, copyable expiry/order CSV, and shareable session summary text.
- Phone-width UX pass reduced Deli tab crowding by showing only the selected bottom-nav label, wrapping progress pills, and moving action/bucket labels below dense card text.

## Deterministic Parser Support

This pass validates deterministic parsing from reviewed text, not production camera accuracy. Current parser support includes:

- SKU extraction with common OCR character normalization for numeric SKUs.
- Inventory labels with explicit case counts, case/pack weight, use-by/expiry dates, vendor hints, category inference, duplicate merging, and confidence scoring.
- Loose backstock items without SKU, assigned `UNKNOWN-*` identifiers and routed to verification.
- Promo/ad rows with SKU, BOGO/B2G/multi-buy/price-point hints, prices, discount percentages, and default ad dates.
- Supplier order review rows with SKU, description, pack size, suggested cases, retained order index, and editable forecast/safety values.
- Sticky notes and extra-count note fragments for review context.

## Not Yet Validated

- Direct Deli tab camera capture and gallery import are not wired.
- Real camera framing, lighting, glare, label-angle, supplier-screen, and multi-page OCR accuracy have not been measured.
- Vision-model extraction is not validated as a dependable Deli workflow input.
- PDF export is intentionally disabled behind a stub exporter.
- Session history is saved but there is not yet a durable in-app history browser or async OCR queue recovery surface.
- The app does not submit or modify official workplace orders.

## Verification Run

Run from the repo root on Windows:

```powershell
$env:JAVA_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.local-jdk\jdk-17.0.19+10'
$env:ANDROID_HOME='C:\Users\KyleB\Documents\Codex\2026-07-04\build-a-native-android-app-using\.android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
.\gradlew.bat test
.\gradlew.bat :app:assembleRelease
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose app\build\outputs\apk\release\app-release.apk
```

Latest requested final pass completed locally on 2026-07-23 after the UX and docs updates.
