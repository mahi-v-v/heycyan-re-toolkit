package expo.modules.glasssdk

import android.content.Context
import android.content.pm.PackageManager
import expo.modules.glasssdk.util.AppLog

object SdkConfig {

    private const val META_KEY = "apiUrl"
    private const val DEFAULT_API_URL = "https://api.example.com"

    var apiUrl: String = DEFAULT_API_URL
        private set

    fun init(context: Context) {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        apiUrl = appInfo.metaData?.getString(META_KEY) ?: DEFAULT_API_URL
        AppLog.i("SdkConfig: apiUrl=$apiUrl")
    }
}
