# Order Radar Agent Notes

Order Radar is a Smithware Studios Android app connected through SoftSmith DevHub.

Before publishing or changing release metadata:

1. Build locally with the known Android toolchain.
2. Keep workplace data local-first. No accounts, cloud sync, ads, or tracking in the MVP.
3. Do not commit local signing keys, APK outputs, SDK paths, or secrets.
4. Publish GitHub Releases with APK assets; DevHub does not detect source-only pushes.
5. Update the SoftSmith DevHub repo after package names, release tags, icons, or app metadata change.

Package: `com.smithware.orderradar`
Repo: `BadBagger/order-radar`
Current APK release: `v0.2.0`
