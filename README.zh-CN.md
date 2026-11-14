# 三星 Watch7 通知保护

这是一个针对 HyperOS 4 的 LSPosed 模块，处理一种具体问题：手机锁屏时，Galaxy Watch7 通知延迟，或者必须点亮手机屏幕后手表才显示通知。

模块会保护三星 Watch7 通知桥接插件，避免被小米后台冻结；同时保留 PowerKeeper 的后台策略，并拦截冻结失败后每 5 秒重新尝试冻结的路径。

## 适配范围

当前版本针对已经检查过的一套环境：

- 手机：Xiaomi 17 Pro Max（`2509FPN0BC`）
- 固件：`OS4.0.6.0.XPBCNXM`
- Android：17，SDK 37
- Root：KernelSU
- Hook 框架：LSPosed Modern API，手机已安装框架的 API 为 102
- 目标包：`com.samsung.wearable.watch7plugin`
- 手表：Galaxy Watch7

模块有严格的固件门禁。换到其他系统版本时，它会跳过 Hook，不会直接假定方法仍然相同。

## 当前状态
> 已实测可运行：Xiaomi 17 Pro Max，HyperOS `OS4.0.6.0.XPBCNXM`，Android 17。

模块已经在 Xiaomi 17 Pro Max 上完成安装并实际运行，固件为 HyperOS `OS4.0.6.0.XPBCNXM`，Android 17。

## 安装

1. 从 GitHub Actions 的 `watch7-guard-<run number>` 工件下载 APK，或本地构建。
2. 在手机上安装 APK。
3. 在 LSPosed 中启用“Watch7 Notification Guard”，勾选两个作用域：
   - `system`，Modern API 中对应 `system_server`；
   - `com.miui.powerkeeper`。
4. 重启手机。
5. 打开模块应用，点击“查看保护日志”，确认日志中出现：
   - `POWERKEEPER ACTIVE`
   - `SYSTEM HOOKS INSTALLED`
   - `SYSTEM ACTIVE: com.samsung.wearable.watch7plugin uid=...`
6. 点击“一次性设置为 noRestrict”。KernelSU 请求 Root 时允许。应用会先保存原策略，写入目标包，再读回确认。
7. 手机保持锁屏，用真实消息测试。记录手机通知入队时间、手表显示时间和目标进程的冻结状态。

应用不读取通知正文、联系人或聊天内容，没有网络权限，也没有前台服务、闹钟、Worker、轮询或唤醒锁。

## 模块做什么

### PowerKeeper

HyperOS 会重新生成后台策略表。Watch7 插件没有桌面启动 Activity，因此这台固件的某条路径会把它当作无图标应用，并把策略恢复为 `miuiAuto`。模块只对目标包改变这个判断，保留它已有的后台策略。

一次性设置和 Hook 分开。一次性设置使用包名和用户 ID，不使用会变化的数据库行 ID；它会保存原值、写入 `noRestrict`，再读回确认。

### Greeze 和 Aurogon

系统 Hook 会在系统服务启动后从 PackageManager 获取目标 UID，并缓存它。只有目标包安装、卸载或替换时才刷新 UID。

针对这个 UID，模块覆盖已经检查过的冻结路径：

- Aurogon 普通筛选；
- 这台固件中的游戏允许列表和睡眠模式判断；
- UID、PID、批量 UID、批量 PID 和 `RunningProcess` 入口；
- `ActionExecute.dealRetryUid`，也就是冻结失败后 5 秒重试的路径。

模块在高层入口拒绝冻结，避免系统继续记录成功冻结或附加 Binder、网络、传感器和唤醒锁限制。它不伪造内核冻结结果，也不直接写 cgroup 文件。

## 启动失败回退

0.1 会在安装业务 Hook 前写入 `pending` 标记，相关启动阶段完成后改成 `ready`。如果下一次加载发现上次启动没有完成，就跳过该进程的业务 Hook，并保持禁用状态。它只保护本模块对应的进程，不会关闭 LSPosed 或 KernelSU。

回退只使用一次性启动事件和原子文件写入，没有计时器或后台 Worker。如果框架在模块入口运行前就崩溃，这个回退无法介入。真正的启动循环仍应使用 KernelSU 安全模式恢复。

## 恢复

正常停用：

1. 在 LSPosed 停用模块并重启。
2. 打开模块应用，点击“恢复原后台设置”。
3. 确认策略恢复后再卸载应用。

如果手机无法完成启动，使用 KernelSU 安全模式禁用模块；如果设备仍能通过 ADB 连接，也可以禁用 LSPosed 对应的 KernelSU 模块。原因没有查清前，不要反复清除启动标记。

## 发布规则

当前正式版本是 `0.1`，由 `v0.1` 标签发布。APK 和 `SHA256SUMS.txt` 都附在这个 GitHub Release 中。

推送到 `main` 或提交 Pull Request 时，Actions 只构建开发工件，不创建新的 Release。以后只有明确推送 `v*` 标签时才发布新版本，并按需要修改版本号。

配置 `WATCH7_SIGNING_KEY_B64` 和 `WATCH7_SIGNING_PASSWORD` 后，Release 构建会使用固定签名，后续 APK 可以覆盖更新现有安装。固定签名必须在仓库之外备份。
## 本地构建

需要：

- PowerShell 7（`pwsh`）
- JDK 21
- 能访问 Google Maven 和 Maven Central 的 Windows 环境

构建脚本会下载并校验：

- Android platform 36 r02
- Android Build Tools 36.0.0
- 只在编译时使用的 libxposed API 101.0.1

请把构建目录放在仓库之外的临时位置：

```powershell
pwsh -NoProfile -File .\build.ps1 -WorkRoot 'D:\path\to\disposable\watch7-guard-build'
```

APK 和校验和会写入 `WorkRoot\artifacts`。不指定签名密钥时，脚本会创建 alias 为 `watch7-local`、密码为 `android` 的本地开发密钥。若要覆盖安装更新，必须保留同一密钥。GitHub Actions 可以通过 `WATCH7_SIGNING_KEY_B64` 和 `WATCH7_SIGNING_PASSWORD` 两个仓库 Secret 使用固定 PKCS12 密钥。

## 目录

- `src/`：LSPosed 入口、PowerKeeper Hook、系统 Hook、启动回退和配置界面的 Java 源码。
- `metadata/`：Modern LSPosed 入口和作用域声明。
- `res/`、`AndroidManifest.xml`：应用资源和包信息。
- `build.ps1`：下载依赖、校验、编译、DEX 转换、打包、对齐和签名。
- `.github/workflows/build.yml`：Windows 构建流程，上传 APK 和校验和工件。
- `docs/ARCHITECTURE.md`：方法级实现和固件依据。
- `docs/HANDOFF.md`：验证、适配和维护流程。
- `CHANGELOG.md`：版本记录。

英文说明见 [README.md](README.md)。