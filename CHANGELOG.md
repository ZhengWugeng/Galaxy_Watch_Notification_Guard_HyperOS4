# Changelog

## Repository automation

- Publish the current `0.1` APK from the explicit `v0.1` tag.
- Keep `main` builds as Actions artifacts and require an explicit tag for future Releases.
- Support an optional stable GitHub Actions signing secret; otherwise use a per-build development key.


## Hardware verification

- Verified that the module installs and runs on a Xiaomi 17 Pro Max with HyperOS `OS4.0.6.0.XPBCNXM` and Android 17.
## 0.1

- Added a boot-only circuit breaker for the `system_server` and PowerKeeper hook groups.
- Skip business hooks after an incomplete startup instead of retrying automatically.
- Added the checked `ActionExecute.dealRetryUid` protection for the target UID.
- Added English handoff and architecture documentation.

## 0.1.0

- Added the first firmware-scoped PowerKeeper and Greeze protection hooks.
- Added the one-time policy editor and restore action.
- Added a Windows build script and a pinned GitHub Actions workflow.