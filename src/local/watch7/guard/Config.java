package local.watch7.guard;

import android.os.Build;

final class Config {
    static final String TARGET = "com.samsung.wearable.watch7plugin";
    static final String FIRMWARE = "OS4.0.6.0.XPBCNXM";

    static boolean supported() {
        return Build.VERSION.SDK_INT == 37 && FIRMWARE.equals(Build.VERSION.INCREMENTAL);
    }
}