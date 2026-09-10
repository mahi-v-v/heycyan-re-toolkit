package expo.modules.glasssdk.db;

import android.content.Context;

import java.util.Map;

public class AppPreferences extends BasePreferences {

    public AppPreferences(Context context) {
        super(context, "app_pref");
    }

    public String getBleDeviceAddress() {
        return getString("ble_address", null);
    }

    public void setBleDeviceAddress(String address) {
        putString("ble_address", address);
    }

    public String getBleDeviceName() {
        return getString("ble_name", null);
    }

    public void setBleDeviceName(String name) {
        putString("ble_name", name);
    }

    public String getBtDeviceAddress() {
        return getString("bt_address", null);
    }

    public void setBtDeviceAddress(String address) {
        putString("bt_address", address);
    }

    public String getAppVersion() {
        return getString("app_version", null);
    }

    public void setAppVersion(String version) {
        putString("app_version", version);
    }

    public String getOtaVersion() {
        return getString("ota_version", null);
    }

    public void setOtaVersion(String version) {
        putString("ota_version", version);
    }

    public String getDpjVersion() {
        return getString("dpj_version", null);
    }

    public void setDpjVersion(String version) {
        putString("dpj_version", version);
    }

    public void setPhotoSize(int width, int height) {
        putInt("photo_width", width);
        putInt("photo_height", height);
    }

    public int getPhotoWidth() {
        return getInt("photo_width", 2560);
    }

    public int getPhotoHeight() {
        return getInt("photo_height", 1920);
    }

    public void setVideoSize(int width, int height) {
        putInt("video_width", width);
        putInt("video_height", height);
    }

    public int getVideoWidth() {
        return getInt("video_width", 1920);
    }

    public int getVideoHeight() {
        return getInt("video_height", 1080);
    }

    public int getVideoQuality() {
        return getInt("video_quality", 1);
    }

    public void setVideoQuality(int quality) {
        putInt("video_quality", quality);
    }

    public int getVideoDurationSeconds() {
        return getInt("video_duration_sec", 60);
    }

    public void setVideoDurationSeconds(int seconds) {
        putInt("video_duration_sec", seconds);
    }

    public int getAudioDurationSeconds() {
        return getInt("audio_duration_sec", 60);
    }

    public void setAudioDurationSeconds(int seconds) {
        putInt("audio_duration_sec", seconds);
    }

    public String getCookie() {
        return getString("auth_cookie", null);
    }

    public void setCookie(String cookie) {
        putString("auth_cookie", cookie);
    }

    public Map<String, Integer> getMediaConfig() {

        return Map.of(
                "photoHeight", this.getPhotoHeight(),
                "photoWidth", this.getPhotoWidth(),
                "videoHeight", this.getVideoHeight(),
                "videoWidth", this.getVideoWidth(),
                "videoQuality", this.getVideoQuality(),
                "videoDuration", this.getVideoDurationSeconds(),
                "audioDuration", this.getAudioDurationSeconds()
        );
    }
}
