package expo.modules.glasssdk.util;

import android.util.Log;

public final class AppLog {

    private static final String DEFAULT_TAG = "GLASS_SDK";
    private static boolean ENABLED = true;

    private AppLog() {
        // no instances
    }

    public static void enable(boolean enable) {
        ENABLED = enable;
    }

    /* ---------- DEBUG ---------- */

    public static void d(String msg) {
        if (ENABLED) Log.d(DEFAULT_TAG, msg);
    }

    public static void d(String tag, String msg) {
        if (ENABLED) Log.d(tag, msg);
    }

    /* ---------- INFO ---------- */

    public static void i(String msg) {
        if (ENABLED) Log.i(DEFAULT_TAG, msg);
    }

    public static void i(String tag, String msg) {
        if (ENABLED) Log.i(tag, msg);
    }

    /* ---------- WARN ---------- */

    public static void w(String msg) {
        if (ENABLED) Log.w(DEFAULT_TAG, msg);
    }

    public static void w(String tag, String msg) {
        if (ENABLED) Log.w(tag, msg);
    }

    /* ---------- ERROR ---------- */

    public static void e(String msg) {
        if (ENABLED) Log.e(DEFAULT_TAG, msg);
    }

    public static void e(String tag, String msg) {
        if (ENABLED) Log.e(tag, msg);
    }

    public static void e(String msg, Throwable t) {
        if (ENABLED) Log.e(DEFAULT_TAG, msg, t);
    }

    public static void e(String tag, String msg, Throwable t) {
        if (ENABLED) Log.e(tag, msg, t);
    }

    /* ---------- VERBOSE ---------- */
    public static void v(String msg) {
        if (ENABLED) Log.v(DEFAULT_TAG, msg);
    }

    public static void v(String tag, String msg) {
        if (ENABLED) Log.v(tag, msg);
    }
}
