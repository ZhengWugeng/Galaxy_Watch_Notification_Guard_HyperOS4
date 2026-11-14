# Watch7 Notification Guard

A firmware-scoped LSPosed module for a Xiaomi phone running HyperOS 4. It targets a specific failure pattern: Galaxy Watch7 notifications arrive late, or appear only after the phone screen wakes while the phone is locked.

The module keeps Samsung's Watch7 notification bridge out of Xiaomi's background freezer. It also preserves the bridge's PowerKeeper policy and suppresses the five-second retry path that follows a rejected freeze.

## Support boundary

This release targets one tested environment:

- Phone: Xiaomi 17 Pro Max (`2509FPN0BC`)
- Firmware: `OS4.0.6.0.XPBCNXM`
- Android: 17, SDK 37
- Root: KernelSU
- Hook framework: LSPosed modern API, tested against installed framework API 102
- Target package: `com.samsung.wearable.watch7plugin`
- Watch: Galaxy Watch7

The module has a firmware gate. On another build it skips its hooks instead of guessing that the same methods still exist.

## Current status
> Verified on hardware: Xiaomi 17 Pro Max, HyperOS `OS4.0.6.0.XPBCNXM`, Android 17.

The module has been installed and run successfully on a Xiaomi 17 Pro Max with HyperOS `OS4.0.6.0.XPBCNXM` and Android 17.


## Install

1. Download the APK from the GitHub Actions artifact named `watch7-guard-<run number>`, or build it locally.
2. Install the APK on the phone.
3. In LSPosed, enable `Watch7 Notification Guard` for both scopes:
   - `system`, which is `system_server` in the modern API.
   - `com.miui.powerkeeper`.
4. Reboot the phone.
5. Open the module app and tap `View protection logs`. Confirm that the logs contain:
   - `POWERKEEPER ACTIVE`
   - `SYSTEM HOOKS INSTALLED`
   - `SYSTEM ACTIVE: com.samsung.wearable.watch7plugin uid=...`
6. Tap `Set policy to noRestrict once` and grant Root in KernelSU when asked. The app saves the current policy, updates the target package, and reads it back to verify the write.
7. Test a real message while the phone stays locked. Record the phone enqueue time, watch display time, and the target process freeze state.

The app does not read notification text, contacts, or chats. It has no network permission and does not run a foreground service, alarm, worker, polling loop, or wake lock.

## What the module does

### PowerKeeper

HyperOS rebuilds its background policy table. The Watch7 plugin has no launcher activity, so one firmware path classifies it as an iconless package and restores `miuiAuto`. The module changes that classification only for the target package while PowerKeeper initializes its policy.

The one-time policy action is separate from the hook. It uses the package name and user ID instead of a database row ID, saves the original value, writes `noRestrict`, and verifies the result.

### Greeze and Aurogon

The system hook resolves the target UID from PackageManager after system services start. It caches that UID and refreshes it only on package add, remove, or replace events.

For that UID, the module covers the checked freeze decision paths:

- normal Aurogon filtering;
- the game-allow-list and sleep-mode checks used by this firmware;
- direct UID, PID, batch UID, batch PID, and `RunningProcess` freeze entry points;
- the `ActionExecute.dealRetryUid` path that schedules another freeze after five seconds.

The module refuses at high-level entries before the service records a successful freeze or applies the associated binder, network, sensor, and wake-lock controls. It does not fake a kernel freeze result and it does not write cgroup files.

## Boot failure fallback

Version 0.1 writes a small `pending` marker before installing the business hooks. It changes that marker to `ready` after the relevant boot stage completes. If a later launch finds an unfinished marker, it skips the business hooks for that process and leaves the marker disabled. This is a process-level circuit breaker; it does not turn off LSPosed or KernelSU.

The fallback uses one-time boot events and atomic file writes. It has no timer or background worker. It cannot help if the framework crashes before the module entry point runs. KernelSU's rescue mode remains the external recovery method for a real boot loop.

## Recovery

To stop the module normally:

1. Disable it in LSPosed and reboot.
2. Open the module app and choose `Restore original policy`.
3. Confirm the policy is restored before uninstalling the app.

If the phone cannot complete boot, use KernelSU rescue mode to disable modules, or disable the LSPosed KernelSU module from ADB while the device is reachable. Do not repeatedly clear the boot marker while the cause is unknown.

## Release policy

The current release is `0.1`, published from the `v0.1` tag. The APK and `SHA256SUMS.txt` are attached to that GitHub Release.

Pushes to `main` and pull requests build an Actions artifact for development. They do not create additional Releases. A future Release requires an explicit `v*` tag and an intentional version change.

When `WATCH7_SIGNING_KEY_B64` and `WATCH7_SIGNING_PASSWORD` are configured, release builds use a stable signing key and newer APKs can update an existing installation. Keep the signing key backed up outside the repository.
## Build locally

Requirements:

- PowerShell 7 (`pwsh`)
- JDK 21
- A Windows machine with network access to Google Maven and Maven Central

The build script downloads and SHA-256 checks these inputs:

- Android platform 36 r02
- Android Build Tools 36.0.0
- libxposed API 101.0.1, compile-only

Choose a disposable build directory outside the repository. The script puts its downloads, compiler output, and temporary files there.

```powershell
pwsh -NoProfile -File .\build.ps1 -WorkRoot 'D:\path\to\disposable\watch7-guard-build'
```

The APK and checksum are written under `WorkRoot\artifacts`. Without a signing key, the script creates a local development key with alias `watch7-local` and password `android`. Keep that key if the APK must be updated in place. GitHub Actions can use a stable PKCS12 key through the `WATCH7_SIGNING_KEY_B64` and `WATCH7_SIGNING_PASSWORD` repository secrets.

## Repository map

- `src/`: Java source for the LSPosed entry point, PowerKeeper hooks, system hooks, boot guard, and the small configuration app.
- `metadata/`: modern LSPosed entry and scope declarations.
- `res/` and `AndroidManifest.xml`: app resources and package metadata.
- `build.ps1`: dependency download, checksum verification, compilation, DEX conversion, packaging, alignment, and signing.
- `.github/workflows/build.yml`: reproducible Windows build that uploads the APK and checksum as an artifact.
- `docs/ARCHITECTURE.md`: method-level behavior and firmware evidence.
- `docs/HANDOFF.md`: how to validate, adapt, and maintain the module.
- `CHANGELOG.md`: release history.

## References

- [HyperOS FCM Fix](https://github.com/dingwen07/hyperos-fcm-fix)
- [Xiaomi whitelist rewrite investigation](https://github.com/dingwen07/hyperos-fcm-fix/blob/main/docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md)
- [HyperOS 4 freeze investigation](https://github.com/dingwen07/hyperos-fcm-fix/blob/main/docs/xiaomi-hyperos4-nighttime-fcm-investigation.md)
- [LSPosed modern module API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [libxposed API](https://github.com/libxposed/api)

For the Chinese version, see [README.zh-CN.md](README.zh-CN.md).