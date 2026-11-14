package local.watch7.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

final class SystemProtection {
    private final GuardModule module;
    private final BootGuard bootGuard;
    private final AtomicBoolean attached = new AtomicBoolean();
    private final AtomicBoolean reportedSkip = new AtomicBoolean();
    private final AtomicBoolean reportedRetrySkip = new AtomicBoolean();
    private volatile int targetUid = -1;
    private Context context;
    private XposedInterface.Invoker<?, Method> uidForPid;

    SystemProtection(GuardModule module, BootGuard bootGuard) {
        this.module = module;
        this.bootGuard = bootGuard;
    }

    void install(ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            Class<?> greeze = Class.forName("com.miui.server.greeze.GreezeManagerService", false, loader);
            Class<?> filters = Class.forName("com.miui.server.greeze.AurogonFilterManager", false, loader);
            Class<?> immobulus = Class.forName("com.miui.server.greeze.AurogonImmobulusMode", false, loader);

            Method filter = GuardModule.method(filters, "filter", boolean.class,
                    int.class, String.class, int.class);
            Method gameAllow = GuardModule.method(immobulus, "isGameAllowListApp", boolean.class, int.class);
            Method noRestrictFreezeable = GuardModule.method(immobulus, "isNoRestrictFreezeable",
                    boolean.class, String.class, int.class);
            Method action = GuardModule.method(greeze, "freezeAction", boolean.class,
                    int.class, int.class, String.class, boolean.class, boolean.class);
            Method uid = GuardModule.method(greeze, "freezeUid", String.class,
                    int.class, long.class, int.class, String.class, int.class);
            Method uids = arrayMethod(greeze, "freezeUids", int[].class, long.class,
                    int.class, String.class, boolean.class);
            Method pids = arrayMethod(greeze, "freezePids", int[].class, long.class,
                    int.class, String.class);
            Method process = processMethod(greeze);
            Field processUid = process.getParameterTypes()[0].getDeclaredField("uid");
            if (processUid.getType() != int.class) {
                throw new NoSuchFieldException("RunningProcess.uid is not int");
            }
            processUid.setAccessible(true);
            Method precheck = precheckMethod(greeze);
            Method bootPhase = bootPhaseMethod(loader);
            Class<?> executor = Class.forName("com.miui.server.greeze.power.ActionExecute", false, loader);
            Method retry = GuardModule.method(executor, "dealRetryUid", void.class,
                    int.class, String.class, int.class);
            uidForPid = module.getInvoker(Process.class.getDeclaredMethod("getUidForPid", int.class));

            handles.add(module.hook(filter).intercept(chain ->
                    protects(chain.getArg(0)) && GuardModule.TARGET.equals(chain.getArg(1))
                            ? false : chain.proceed()));
            handles.add(module.hook(gameAllow).intercept(chain ->
                    protects(chain.getArg(0)) ? true : chain.proceed()));
            handles.add(module.hook(noRestrictFreezeable).intercept(chain ->
                    GuardModule.TARGET.equals(chain.getArg(0)) && protects(chain.getArg(1))
                            ? false : chain.proceed()));

            // Refuse at entry, before the service records a freeze or applies Binder/network restrictions.
            handles.add(module.hook(action).intercept(chain -> {
                if (!protects(chain.getArg(0))) return chain.proceed();
                skipped("freezeAction");
                return false;
            }));
            handles.add(module.hook(uid).intercept(chain -> {
                if (!protects(chain.getArg(0))) return chain.proceed();
                skipped("freezeUid");
                return "Watch7GuardExempt";
            }));
            handles.add(module.hook(uids).intercept(chain -> filterBatch(chain, false)));
            handles.add(module.hook(pids).intercept(chain -> filterBatch(chain, true)));
            handles.add(module.hook(process).intercept(chain -> {
                Object running = chain.getArg(0);
                if (running == null || !protects(processUid.getInt(running))) return chain.proceed();
                skipped("freezeProcess");
                return false;
            }));
            // Keep onFreezeFailed's GPS cleanup; suppress only scheduling a new target freeze.
            handles.add(module.hook(retry).intercept(chain -> {
                if (!protects(chain.getArg(0))) return chain.proceed();
                if (reportedRetrySkip.compareAndSet(false, true)) {
                    module.info("TARGET RETRY SUPPRESSED: no five-second freeze retry scheduled");
                }
                return null;
            }));
            handles.add(module.hook(bootPhase).intercept(chain -> {
                Object result = chain.proceed();
                List<Object> args = chain.getArgs();
                int phase = (int) args.get(args.size() - 1);
                if (phase >= 500) attachSystemContext();
                if (phase >= 1000) bootGuard.complete();
                return result;
            }));

            // Only callers of the short policy predicates need deoptimization.
            for (Method caller : Arrays.asList(precheck, action, uids)) {
                if (!module.deoptimize(caller)) {
                    throw new IllegalStateException("cannot deoptimize " + caller.getName());
                }
            }
            int retryCallers = 0;
            for (Method caller : executor.getDeclaredMethods()) {
                String name = caller.getName();
                if (name.equals("onFreezeFailed") || name.equals("dealCheckUids")
                        || name.equals("handleDelayedFreeze")) {
                    if (!module.deoptimize(caller)) {
                        throw new IllegalStateException("cannot deoptimize retry caller " + name);
                    }
                    retryCallers++;
                }
            }
            if (retryCallers != 3) throw new NoSuchMethodException("retry callers changed");
            module.info("SYSTEM HOOKS INSTALLED; waiting for package manager at boot phase >=500");
        } catch (Throwable error) {
            targetUid = -1;
            for (XposedInterface.HookHandle handle : handles) handle.unhook();
            module.failure("system protection", error);
        }
    }

    private boolean protects(Object uid) {
        int current = targetUid;
        return current >= 10000 && uid instanceof Integer && (int) uid == current;
    }

    private Object filterBatch(XposedInterface.Chain chain, boolean arePids) throws Throwable {
        int[] original = (int[]) chain.getArg(0);
        if (targetUid < 10000 || original == null || original.length == 0) return chain.proceed();
        int[] kept = new int[original.length];
        int count = 0;
        for (int value : original) {
            int uid = arePids ? (int) uidForPid.invoke(null, value) : value;
            if (!protects(uid)) kept[count++] = value;
        }
        if (count == original.length) return chain.proceed();
        Object[] args = chain.getArgs().toArray();
        args[0] = Arrays.copyOf(kept, count);
        skipped(arePids ? "freezePids" : "freezeUids");
        // Let the original method produce its normal return value, including for an empty batch.
        return chain.proceed(args);
    }

    private void skipped(String path) {
        if (reportedSkip.compareAndSet(false, true)) {
            module.info("FIRST DIRECT FREEZE SKIPPED: " + path + "; uid=" + targetUid);
        }
    }

    private void attachSystemContext() {
        if (!attached.compareAndSet(false, true)) return;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = module.getInvoker(
                    activityThread.getDeclaredMethod("currentActivityThread")).invoke(null);
            Context resolved = (Context) module.getInvoker(
                    activityThread.getDeclaredMethod("getSystemContext")).invoke(thread);
            if (resolved == null) throw new IllegalStateException("system context not ready");
            context = resolved;
            IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (intent.getData() != null
                            && GuardModule.TARGET.equals(intent.getData().getSchemeSpecificPart())) {
                        refreshUid();
                    }
                }
            }, packages, Context.RECEIVER_NOT_EXPORTED);
            refreshUid();
        } catch (Throwable error) {
            attached.set(false);
            module.failure("package-manager binding", error);
        }
    }

    private void refreshUid() {
        targetUid = -1;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(GuardModule.TARGET, 0);
            String[] packages = pm.getPackagesForUid(app.uid);
            if (app.uid < 10000 || app.uid >= 100000 || packages == null
                    || packages.length != 1 || !GuardModule.TARGET.equals(packages[0])) {
                module.info("TARGET INACTIVE: UID is outside owner user or is shared");
                return;
            }
            targetUid = app.uid;
            module.info("SYSTEM ACTIVE: " + GuardModule.TARGET + " uid=" + targetUid);
        } catch (PackageManager.NameNotFoundException missing) {
            module.info("TARGET INACTIVE: Watch7 plugin not installed for owner user");
        } catch (Throwable error) {
            module.failure("target UID resolution", error);
        }
    }

    private static Method arrayMethod(Class<?> type, String name, Class<?>... args)
            throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, args);
        if (!List.class.isAssignableFrom(result.getReturnType())) {
            throw new NoSuchMethodException(name + " no longer returns List");
        }
        return result;
    }

    private static Method processMethod(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("freezeProcess") && method.getReturnType() == boolean.class
                    && p.length == 4 && p[0].getSimpleName().equals("RunningProcess")
                    && p[1] == long.class && p[2] == int.class && p[3] == String.class) {
                return method;
            }
        }
        throw new NoSuchMethodException("freezeProcess(RunningProcess,long,int,String)");
    }

    private static Method precheckMethod(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("preCheckInterest") && method.getReturnType() == boolean.class
                    && p.length == 3 && p[0] == int.class && p[1] == boolean.class
                    && p[2].getSimpleName().equals("AurogonAppInfo")) return method;
        }
        throw new NoSuchMethodException("preCheckInterest signature changed");
    }

    private static Method bootPhaseMethod(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.android.server.SystemServiceManager", false, loader);
        for (Method method : manager.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals("startBootPhase") && method.getReturnType() == void.class
                    && p.length >= 1 && p[p.length - 1] == int.class) return method;
        }
        throw new NoSuchMethodException("SystemServiceManager.startBootPhase");
    }
}
