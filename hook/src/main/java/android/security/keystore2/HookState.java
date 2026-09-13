package android.security.keystore2;

import android.content.Context;
import android.os.Binder;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HookState {

    public static final String VERSION = "ks2-1.0.0";

    private static final long CONFIG_TTL_MS = 2000L;

    private static volatile Context sContext;
    private static volatile boolean sSystemServer;
    private static volatile HookConfig sConfig;
    private static volatile long sConfigTime;
    private static final ThreadLocal<Integer> INTERNAL = new ThreadLocal<>();
    private static final Map<Integer, String> UID_CACHE = new ConcurrentHashMap<>();

    private HookState() {
    }

    public static void initApp(Context context) {
        try {
            if (context == null) {
                return;
            }
            sContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            HookLog.d("init app " + context.getPackageName());
            BuildSpoofer.apply(config(), context.getPackageName());
        } catch (Throwable throwable) {
            HookLog.e("initApp", throwable);
        }
    }

    public static void initSystemServer() {
        try {
            sSystemServer = true;
            sContext = systemContext();
            HookLog.d("init system_server context=" + sContext);
            BuildSpoofer.apply(config(), "android");
        } catch (Throwable throwable) {
            HookLog.e("initSystemServer", throwable);
        }
    }

    private static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getMethod("currentActivityThread");
            Object thread = current.invoke(null);
            Method getSystemContext = activityThread.getMethod("getSystemContext");
            Object context = getSystemContext.invoke(thread);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object context = appGlobals.getMethod("getInitialApplication").invoke(null);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Context context() {
        return sContext;
    }

    public static boolean isSystemServer() {
        return sSystemServer;
    }

    public static HookConfig config() {
        HookConfig cached = sConfig;
        long now = SystemClock.uptimeMillis();
        if (cached != null && now - sConfigTime < CONFIG_TTL_MS) {
            return cached;
        }
        Context context = sContext;
        if (context == null) {
            return cached != null ? cached : HookConfig.empty();
        }
        beginInternal();
        try {
            cached = HookConfig.load(context.getContentResolver());
            sConfig = cached;
            sConfigTime = now;
        } finally {
            endInternal();
        }
        return cached;
    }

    public static void reloadConfig() {
        sConfigTime = 0L;
    }

    public static boolean isInternalCall() {
        return INTERNAL.get() != null;
    }

    public static void beginInternal() {
        Integer depth = INTERNAL.get();
        INTERNAL.set(depth == null ? 1 : depth + 1);
    }

    public static void endInternal() {
        Integer depth = INTERNAL.get();
        if (depth == null || depth <= 1) {
            INTERNAL.remove();
        } else {
            INTERNAL.set(depth - 1);
        }
    }

    public static String currentPackage() {
        Context context = sContext;
        if (context == null) {
            return null;
        }
        try {
            return context.getPackageName();
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static String packageForUid(int uid) {
        String cached = UID_CACHE.get(uid);
        if (cached != null) {
            return cached;
        }
        String resolved = null;
        Context context = sContext;
        if (context != null) {
            beginInternal();
            try {
                String[] packages = context.getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length == 1) {
                    resolved = packages[0];
                }
            } catch (Throwable throwable) {
                HookLog.e("packageForUid " + uid, throwable);
            } finally {
                endInternal();
            }
        }
        if (resolved != null) {
            UID_CACHE.put(uid, resolved);
        }
        return resolved;
    }

    public static boolean isPrivilegedUid(int uid) {
        if (uid < 10000) {
            return true;
        }
        Context context = sContext;
        if (context != null) {
            try {
                if (uid == context.getApplicationInfo().uid) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static int callingUid() {
        try {
            return Binder.getCallingUid();
        } catch (Throwable throwable) {
            return -1;
        }
    }
}
