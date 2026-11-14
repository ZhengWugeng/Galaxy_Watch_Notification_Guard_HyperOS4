# Quick start

## For a user

1. Install the APK from a successful GitHub Actions build.
2. Enable the module for `system` and `com.miui.powerkeeper` in LSPosed.
3. Reboot.
4. Read the module log and confirm all three activation lines.
5. Set the Watch7 policy to `noRestrict` once.
6. Test a real notification with the phone locked.

## For a maintainer

1. Read `README.md` and `docs/HANDOFF.md`.
2. Build with JDK 21 and PowerShell 7.
3. Keep the signing key outside Git.
4. Verify the exact firmware before changing hooks.
5. Run the lock-screen test before publishing a new APK.

## Useful read-only checks

```text
adb shell getprop ro.build.version.incremental
adb shell getprop ro.build.version.sdk
adb shell su -c 'logcat -d -v time -s Watch7Guard:I "*:S"'
```

Avoid collecting notification text. Timestamps and process state are enough for this bug.
