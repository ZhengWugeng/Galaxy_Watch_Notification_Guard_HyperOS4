package local.watch7.guard;

import android.os.Build;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class GuardModule extends XposedModule {
    static final String TARGET = Config.TARGET;
    static final String POWERKEEPER = "com.miui.powerkeeper";
    static final String FIRMWARE = Config.FIRMWARE;
    static final String TAG = "Watch7Guard";
    private String processName;

    static boolean supported() {
        return Config.supported();
    }

    void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    void failure(String stage, Throwable error) {
        String message = stage + " inactive: " + error.getClass().getSimpleName()
                + ": " + error.getMessage();
        Log.e(TAG, message);
        log(Log.ERROR, TAG, message);
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        info("v0.1 loaded into " + param.getProcessName() + "; framework="
                + getFrameworkName() + " " + getFrameworkVersion()
                + "; firmware=" + Build.VERSION.INCREMENTAL);
        if (!supported()) {
            info("UNSUPPORTED firmware; no hooks will be installed");
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (supported() && POWERKEEPER.equals(param.getPackageName())) {
            awaitPowerKeeperApplication(param.getClassLoader());
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (supported()) {
            BootGuard guard = BootGuard.begin(this,
                    new File("/data/system/watch7-notification-guard.state"), "system_server");
            if (guard != null) new SystemProtection(this, guard).install(param.getClassLoader());
        }
    }

    private void awaitPowerKeeperApplication(ClassLoader loader) {
        AtomicBoolean initialized = new AtomicBoolean();
        try {
            Method attach = method(Application.class, "attach", void.class, Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                Context context = (Context) chain.getArg(0);
                if (context != null && POWERKEEPER.equals(context.getPackageName())
                        && initialized.compareAndSet(false, true)) {
                    try {
                        String name = processName.replaceAll("[^A-Za-z0-9._-]", "_");
                        File directory = context.createDeviceProtectedStorageContext().getFilesDir();
                        BootGuard guard = BootGuard.begin(this,
                                new File(directory, "watch7-guard-" + name + ".state"), processName);
                        if (guard != null) {
                            installPowerKeeper(loader);
                            completePowerKeeperOnBoot(context, guard);
                        }
                    } catch (Throwable error) {
                        failure("PowerKeeper boot guard", error);
                    }
                }
                return result;
            });
        } catch (Throwable error) {
            failure("PowerKeeper application lifecycle", error);
        }
    }

    private void completePowerKeeperOnBoot(Context context, BootGuard guard) throws Throwable {
        AtomicBoolean finished = new AtomicBoolean();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                        && finished.compareAndSet(false, true)) {
                    guard.complete();
                    context.unregisterReceiver(this);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_BOOT_COMPLETED),
                Context.RECEIVER_NOT_EXPORTED);
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method get = properties.getDeclaredMethod("get", String.class);
        if ("1".equals(getInvoker(get).invoke(null, "sys.boot_completed"))) {
            // A single main-thread task runs after Application/providers finish initialization.
            new Handler(context.getMainLooper()).post(() -> {
                if (finished.compareAndSet(false, true)) {
                    guard.complete();
                    context.unregisterReceiver(receiver);
                }
            });
        }
    }

    private void installPowerKeeper(ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            Class<?> manager = Class.forName(
                    "com.miui.powerkeeper.provider.PowerKeeperConfigureManager", false, loader);
            Method managerCheck = method(manager, "pkgHasIcon", boolean.class, String.class);
            Class<?> utils = Class.forName("com.miui.powerkeeper.utils.Utils", false, loader);
            Method utilityCheck = method(utils, "pkgHasIcon", boolean.class,
                    android.content.Context.class, String.class);
            Method init = method(manager, "initUserConfigure", void.class, int.class);
            Method initAll = method(manager, "initUserConfigure", void.class);

            handles.add(hook(managerCheck).intercept(chain ->
                    TARGET.equals(chain.getArg(0)) ? true : chain.proceed()));
            handles.add(hook(utilityCheck).intercept(chain ->
                    TARGET.equals(chain.getArg(1)) ? true : chain.proceed()));
            if (!deoptimize(init) || !deoptimize(initAll) || !deoptimize(managerCheck)) {
                throw new IllegalStateException("policy caller deoptimization unavailable");
            }
            info("POWERKEEPER ACTIVE: preserve target policy; no configuration writes");
        } catch (Throwable error) {
            for (XposedInterface.HookHandle handle : handles) {
                handle.unhook();
            }
            failure("PowerKeeper", error);
        }
    }

    static Method method(Class<?> type, String name, Class<?> returns, Class<?>... args)
            throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, args);
        if (result.getReturnType() != returns) {
            throw new NoSuchMethodException(type.getName() + "." + name + " return type changed");
        }
        return result;
    }
}
