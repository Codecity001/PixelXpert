package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.os.Environment
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import sh.siava.pixelxpert.xposed.XPrefs

object BootLoopProtector {
    const val LOAD_TIME_KEY_KEY: String = "packageLastLoad_"
    const val PACKAGE_STRIKE_KEY_KEY: String = "packageStrike_"
    private const val COUNTER_RESET_PERIOD: Int = 60 // seconds
    private const val MAX_STRIKES: Int = 3
    private const val EARLY_BOOT_STATE_VERSION: Int = 1
    private const val EARLY_SYSTEM_SERVER_STATE_FILE: String = "pixelxpert-system-server-boot-state"

    enum class BootLoopState {
        ALLOWED,
        BLOCKED,
        UNAVAILABLE
    }

    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun isBootLooped(packageName: String): Boolean {
        val loadTimeKey = "$LOAD_TIME_KEY_KEY$packageName"
        val strikeKey = "$PACKAGE_STRIKE_KEY_KEY$packageName"
        val currentTime = Calendar.getInstance().time.time
        val lastLoadTime = XPrefs.getLong(loadTimeKey, 0L)
        var strikeCount = XPrefs.getInt(strikeKey, 0)

        if (currentTime - lastLoadTime > COUNTER_RESET_PERIOD * 1000L) {
            resetCounter(packageName)
        } else if (strikeCount >= MAX_STRIKES) {
            return true
        } else {
            strikeCount++
            XPrefs.edit().putInt(strikeKey, strikeCount).commit()
        }
        return false
    }

    @JvmStatic
    fun checkAndRecordEarlySystemServerStart(): BootLoopState {
        val stateFile = AtomicFile(
            File(File(Environment.getDataDirectory(), "system"), EARLY_SYSTEM_SERVER_STATE_FILE)
        )
        val currentTime = Calendar.getInstance().time.time
        var lastLoadTime = 0L
        var strikeCount = 0

        try {
            if (stateFile.baseFile.exists()) {
                DataInputStream(BufferedInputStream(stateFile.openRead())).use { input ->
                    if (input.readInt() != EARLY_BOOT_STATE_VERSION) {
                        return BootLoopState.UNAVAILABLE
                    }
                    lastLoadTime = input.readLong()
                    strikeCount = input.readInt()
                }
            }
        } catch (_: IOException) {
            return BootLoopState.UNAVAILABLE
        } catch (_: RuntimeException) {
            return BootLoopState.UNAVAILABLE
        }

        if (currentTime - lastLoadTime > COUNTER_RESET_PERIOD * 1000L) {
            lastLoadTime = currentTime
            strikeCount = 0
        } else if (strikeCount >= MAX_STRIKES) {
            return BootLoopState.BLOCKED
        } else {
            strikeCount++
        }

        var output: FileOutputStream? = null
        return try {
            output = stateFile.startWrite()
            val dataOutput = DataOutputStream(BufferedOutputStream(output))
            dataOutput.writeInt(EARLY_BOOT_STATE_VERSION)
            dataOutput.writeLong(lastLoadTime)
            dataOutput.writeInt(strikeCount)
            dataOutput.flush()
            stateFile.finishWrite(output)
            BootLoopState.ALLOWED
        } catch (_: IOException) {
            output?.let { runCatching { stateFile.failWrite(it) } }
            BootLoopState.UNAVAILABLE
        } catch (_: RuntimeException) {
            output?.let { runCatching { stateFile.failWrite(it) } }
            BootLoopState.UNAVAILABLE
        }
    }

    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun resetCounter(packageName: String) {
        runCatching {
            val loadTimeKey = "$LOAD_TIME_KEY_KEY$packageName"
            val strikeKey = "$PACKAGE_STRIKE_KEY_KEY$packageName"
            val currentTime = Calendar.getInstance().time.time

            XPrefs.edit()
                .putLong(loadTimeKey, currentTime)
                .putInt(strikeKey, 0)
                .commit()
        }
    }
}
