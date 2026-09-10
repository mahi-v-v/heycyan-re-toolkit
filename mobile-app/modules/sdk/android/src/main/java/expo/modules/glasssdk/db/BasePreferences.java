package expo.modules.glasssdk.db;

import android.content.Context;
import android.content.SharedPreferences;

public class BasePreferences {

    protected final SharedPreferences prefs;

    public BasePreferences(Context context, String preferenceName) {
        this.prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
    }

    protected void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    protected void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    protected void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    protected void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    protected void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }


    protected int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    protected long getLong(String key, long defaultValue) {
        return prefs.getLong(key, defaultValue);
    }

    protected float getFloat(String key, float defaultValue) {
        return prefs.getFloat(key, defaultValue);
    }

    protected boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    protected String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    protected boolean contains(String key) {
        return prefs.contains(key);
    }

    protected void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    protected void clear() {
        prefs.edit().clear().apply();
    }
}
