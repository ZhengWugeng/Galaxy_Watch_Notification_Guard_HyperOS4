# Architecture and firmware evidence

## File map

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | App identity, version, SDK, and package visibility |
| `metadata/META-INF/xposed/*` | Modern LSPosed entry and scopes |
| `src/.../BootGuard.java` | Atomic pending/ready/disabled markers for boot failure fallback |
| `src/.../Config.java` | Exact firmware gate and target package |
| `src/.../GuardModule.java` | LSPosed lifecycle and PowerKeeper hook setup |
| `src/.../SystemProtection.java` | UID cache, freeze entry hooks, retry suppression, and boot binding |
| `src/.../MainActivity.java` | Manual setup, status, logs, and restore controls |
| `src/.../PolicySettings.java` | Root-backed one-time policy update and restore |
| `build.ps1` | Dependency download, checksum verification, compilation, packaging, and signing |

The ordinary app process uses `Config` and does not depend on the LSPosed entry class. That keeps the settings screen usable even when the module is not loaded into an app process.

## PowerKeeper policy path

The checked firmware contains these methods:

```text
PowerKeeperConfigureManager.pkgHasIcon(String): boolean
Utils.pkgHasIcon(Context, String): boolean
PowerKeeperConfigureManager.initUserConfigure(): void
PowerKeeperConfigureManager.initUserConfigure(int): void
```

The module returns `true` for the target package in the two icon checks. This keeps the package out of the iconless-package reset branch without changing PackageManager results globally or creating a launcher icon.

The manual policy action reads the unique main-user row by package name and user ID. It saves the current `bgControl`, writes `noRestrict`, and reads the row back. It does not use a mutable database row ID and it does not write during normal Hook execution.

## Freeze paths

The module resolves and caches the target UID after system services start. Package add, remove, and replace events refresh the cache. A shared UID or a non-owner-user UID is rejected rather than protecting unrelated packages.

The current hook set covers the checked firmware paths:

| Method | Target behavior |
|---|---|
| `AurogonFilterManager.filter(int, String, int): boolean` | Return `false` for the target UID/package so the caller takes its skip branch. |
| `AurogonImmobulusMode.isGameAllowListApp(int): boolean` | Return `true` for the target UID. This uses the existing decision branch and does not edit the game list. |
| `AurogonImmobulusMode.isNoRestrictFreezeable(String, int): boolean` | Return `false` for the target package and UID. |
| `GreezeManagerService.freezeAction(int, int, String, boolean, boolean): boolean` | Return before the target reaches the freeze implementation. |
| `GreezeManagerService.freezeUid(int, long, int, String, int): String` | Return `Watch7GuardExempt` before the target reaches the freeze implementation. |
| `freezeUids(int[], long, int, String, boolean): List` | Remove the target UID and call the original method for the remaining inputs. |
| `freezePids(int[], long, int, String): List` | Resolve each PID's current UID, remove target PIDs, and call the original method. |
| `freezeProcess(RunningProcess, long, int, String): boolean` | Return before freeze state is recorded for the target. |
| `ActionExecute.dealRetryUid(int, String, int): void` | Do not schedule another target freeze. |

The module does not hook `FreezeUtils` or write cgroup files. The high-level entries are used because the checked `freezeProcess` path can record frozen metadata even when the low-level freeze call returns false. Blocking only the kernel write would leave the framework state inconsistent.

`freezeUids` calls the cgroup path internally, so the batch input is filtered before that path runs. Runtime validation still has to confirm that no other caller bypasses this set.

## Retry behavior

On this firmware, `ActionExecute.onFreezeFailed(int, String, String, boolean)` performs its failure cleanup and then calls `dealRetryUid(uid, reason, 5000)`. The module keeps the failure cleanup and blocks only the target retry enqueue. It deoptimizes the checked callers `onFreezeFailed`, `dealCheckUids`, and `handleDelayedFreeze` so short calls are less likely to bypass the hook through inlining.

## Boot fallback and battery behavior

Each hook group writes a boot marker before installing its business hooks. The system group completes at boot phase 1000. The PowerKeeper group completes after `BOOT_COMPLETED`, or after one main-thread task when the process starts after boot.

The module has no periodic work, foreground service, alarm, worker, polling loop, or wake lock. The Samsung bridge itself may use more battery when it is allowed to remain active; that trade-off must be measured on the phone.

## Logging

The `Watch7Guard` tag records module loading, hook installation, UID refreshes, the first direct freeze skip, the first retry suppression, and errors. It does not record notification bodies, senders, contacts, or chat text.