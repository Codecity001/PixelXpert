package sh.siava.pixelxpert.utils

import android.util.JsonReader
import android.util.Log
import java.io.InputStreamReader
import java.net.URL
import java.util.HashMap
import sh.siava.pixelxpert.BuildConfig

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val STABLE_UPDATES_URL = "https://raw.githubusercontent.com/Codecity001/PixelXpert/main/latestStable.json"
    private const val CANARY_UPDATES_URL = "https://raw.githubusercontent.com/Codecity001/PixelXpert/canary/latestCanary.json"

    val isCanary: Boolean
        get() = BuildConfig.VERSION_NAME.lowercase().contains("canary")

    fun interface TaskDoneCallback {
        fun onFinished(result: HashMap<String, Any>)
    }

    class CheckerThread(private val callback: TaskDoneCallback) : Thread() {
        override fun run() {
            try {
                sleep(200)
                val url = if (isCanary) CANARY_UPDATES_URL else STABLE_UPDATES_URL
                val updateData = URL(url)
                val s = updateData.openStream()
                val r = InputStreamReader(s)
                val jsonReader = JsonReader(r)

                val versionInfo = HashMap<String, Any>()
                jsonReader.beginObject()
                while (jsonReader.hasNext()) {
                    val name = jsonReader.nextName()
                    when (name) {
                        "versionCode" -> versionInfo[name] = jsonReader.nextInt()
                        "zipUrl", "version", "changelog" -> versionInfo[name] = jsonReader.nextString()
                        else -> versionInfo[name] = jsonReader.nextString()
                    }
                }
                jsonReader.endObject()
                callback.onFinished(versionInfo)
            } catch (e: Exception) {
                val error = HashMap<String, Any>()
                error["version"] = "Connection Error"
                error["versionCode"] = -1
                callback.onFinished(error)
                Log.e(TAG, "update check error: ", e)
            }
        }
    }

    @JvmStatic
    fun check(callback: TaskDoneCallback) {
        CheckerThread(callback).start()
    }
}
