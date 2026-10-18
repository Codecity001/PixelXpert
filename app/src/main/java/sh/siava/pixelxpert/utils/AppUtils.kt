package sh.siava.pixelxpert.utils

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
import android.os.FileUtils
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.zip.ZipFile
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.PixelXpert
import kotlin.system.exitProcess

object AppUtils {

    @JvmStatic
    fun restart(what: String) {
        when (what.lowercase()) {
            "systemui" -> Shell.cmd("killall com.android.systemui").exec()
            "system" -> Shell.cmd("svc power reboot").exec()
            "zygote", "android" -> {
                Shell.cmd("kill $(pidof zygote)").submit()
                Shell.cmd("kill $(pidof zygote64)").submit()
            }
            else -> Shell.cmd("killall $what").exec()
        }
    }

    @JvmStatic
    fun restartSelf(reason: String?) {
        val app = PixelXpert.instance
        val pm = app.baseContext.packageManager
        val intent = pm.getLaunchIntentForPackage(app.baseContext.packageName)

        if (intent != null) {
            if (reason != null) {
                intent.putExtra(Constants.LAUNCH_REASON_EXTRA, reason)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(intent)
        }

        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    @JvmStatic
    fun runKSURootActivity(context: Context, launchApp: Boolean) {
        try {
            val broadcastIntent = Intent(Constants.PX_ROOT_EXTRA)
            if (launchApp) {
                broadcastIntent.putExtra("launchApp", 1)
            }
            broadcastIntent.setPackage(Constants.KSU_PACKAGE)
            context.sendBroadcast(broadcastIntent)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(Constants.KSU_PACKAGE)
            if (launchIntent != null) {
                launchIntent.putExtra(Constants.PX_ROOT_EXTRA, 1)
                launchIntent.flags = FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                if (launchApp) {
                    launchIntent.putExtra("launchApp", 1)
                }
                context.startActivity(launchIntent)
            }
        } catch (ignored: Throwable) {}
    }

    @JvmStatic
    fun isLikelyPixelBuild(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("getprop ro.build.id")
            process.waitFor()
            val buffer = ByteArray(process.inputStream.available())
            process.inputStream.read(buffer)
            val result = String(buffer, StandardCharsets.US_ASCII).replace("\n", "")
            Pattern.matches("^[TUABCZ][A-Z]([A-Z0-9]){2}\\.[0-9]{6}\\.[0-9]{3}(\\.[A-Z0-9]{2})?$", result)
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun installDoubleZip(doubleZipped: String): Boolean {
        return try {
            val tempFile = File.createTempFile("doubleZ", ".zip")
            Shell.cmd("cp $doubleZipped ${tempFile.absolutePath}").exec()

            val unzipper = ZipFile(tempFile)
            val unzippedFile: File
            if (unzipper.stream().count() == 1L) {
                unzippedFile = File.createTempFile("singleZ", ".zip")
                val unzipOutputStream = FileOutputStream(unzippedFile)
                FileUtils.copy(unzipper.getInputStream(unzipper.entries().nextElement()), unzipOutputStream)
                unzipOutputStream.close()
            } else {
                unzippedFile = tempFile
            }
            unzipper.close()

            Shell.cmd("magisk --install-module ${unzippedFile.absolutePath}").exec()
            Shell.cmd("ksud module install ${unzippedFile.absolutePath}").exec()

            tempFile.delete()
            unzippedFile.delete()
            true
        } catch (e: Exception) {
            Log.e("PixelXpert Installer", "PixelXpert zip install error: ", e)
            false
        }
    }
}
