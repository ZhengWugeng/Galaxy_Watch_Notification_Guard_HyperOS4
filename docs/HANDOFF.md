# Maintainer handoff

## What this repository contains

This is an LSPosed module verified on one Xiaomi HyperOS 4 firmware build. The module protects the Samsung Watch7 notification bridge from the background freezer that was observed delaying notifications while the phone was locked.

The repository contains the handwritten source, LSPosed metadata, Android resources, a dependency-checking PowerShell build script, CI, and these maintenance notes. It does not contain phone logs, system JAR/APK copies, ADB serials, or signing keys.

## Known device baseline

| Item | Value |
|---|---|
| Phone | Xiaomi 17 Pro Max, `2509FPN0BC` |
| Firmware | `OS4.0.6.0.XPBCNXM`, China build |
| Android | 17, SDK 37 |
| Root | KernelSU |
| LSPosed | v2.2.1 (7912), installed framework API 102 |
| Module compile API | libxposed API 101.0.1 |
| Target | `com.samsung.wearable.watch7plugin` |
| `miui-services.jar` SHA-256 | `6d1b13d8a10b294e6d096c64a81167ce480fb353eeb73322bd5d963b82587f8c` |
| `PowerKeeper.apk` SHA-256 | `164f2b6271eef11afaf5c97984011f01329173099accf69bada3b815a26cbd44` |

The `system_server` class path includes `/system_ext/framework/miui-services.jar`. The checked `RunningProcess.uid` field is an `int`. The checked boot-phase method is `startBootPhase(TimingsTraceAndSlog, int)`.

## Validation sequence

1. Read `docs/ARCHITECTURE.md`.
2. Build from a clean Windows machine with PowerShell 7 and JDK 21.
3. Verify the exact firmware gate before changing any hook.
4. Install the APK, enable both LSPosed scopes, and reboot.
5. Confirm `POWERKEEPER ACTIVE`, `SYSTEM HOOKS INSTALLED`, and `SYSTEM ACTIVE` in the module log.
6. Inspect the Watch7 policy and current cgroup state.
7. Send real messages with the phone locked. Compare notification enqueue time with watch display time.
8. Check for repeated freeze attempts, then repeat after reboot and with USB disconnected.
9. Measure overnight battery use before describing the battery impact.

The module has now been installed and run successfully on the baseline Xiaomi 17 Pro Max. A successful build or a matching method name is still not the same as a locked-screen delivery or battery measurement. The useful evidence for those claims is a test with process state and timestamps.

## Adapting to a new firmware

Do not change `Config.FIRMWARE` by itself.

1. Use the device's built-in `/apex/com.android.art/bin/dexdump` to inspect the new `miui-services.jar` and `PowerKeeper.apk`.
2. Recheck every method signature in `docs/ARCHITECTURE.md`.
3. Recheck the return branches for the normal filter, direct freeze, batch freeze, and sleep-mode paths.
4. Recheck `freezeProcess`, `freezeUidCgroup`, and the state changes that apply binder, network, sensor, and wake-lock controls.
5. Recheck `ActionExecute.onFreezeFailed`, `dealRetryUid`, and its callers for delayed retry behavior.
6. Update the firmware gate, file hashes, module version, and documentation.
7. Build and run the full locked-screen validation sequence.

Keep the target package narrow. Do not turn this into a global freezer disable or a protection list for every messaging app.

## Boot fallback

`BootGuard` writes `pending` before each business hook group and `ready` after the expected boot stage completes. A later load that finds an unfinished marker skips that process's business hooks. This is intentionally conservative and has no timer or polling loop.

The fallback cannot help if the framework crashes before the module entry point runs. KernelSU rescue mode is still the recovery path for a real boot loop. Do not clear a marker repeatedly without recording the preceding logs.

## Signing and release work

The build script creates a development PKCS12 key when no external key is supplied. Keep that key private. A later APK can update an existing installation only when it uses the same signing key.

For GitHub Actions, store the PKCS12 file as Base64 in `WATCH7_SIGNING_KEY_B64` and the password in `WATCH7_SIGNING_PASSWORD`. The alias must remain `watch7-local` unless the build script is changed with the same care as the signing setup.

The workflow uploads an APK and checksum as a 90-day Actions artifact. The current `0.1` Release is created from the explicit `v0.1` tag; future Releases also require an explicit version tag.
## Release automation

The current release is `0.1`, published from the `v0.1` tag. Pushes to `main` and pull requests build an Actions artifact without publishing a Release. Future Releases require an explicit `v*` tag.

Release jobs can use `WATCH7_SIGNING_KEY_B64` and `WATCH7_SIGNING_PASSWORD` when a maintainer has configured them. Without those secrets, the workflow creates a fresh runner-local development key for each build. Such APKs are suitable for fresh installs, but may require uninstalling the previous build before installation. A stable signing key must stay outside Git and should have an offline backup.