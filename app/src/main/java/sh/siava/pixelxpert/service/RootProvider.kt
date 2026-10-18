package sh.siava.pixelxpert.service

import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.IRootProviderService

class RootProvider : RootService() {

    companion object {
        const val LSPD_DB_PATH: String = "/data/adb/lspd/config/modules_config.db"
        const val SQLITE_BIN: String = "/data/adb/modules/PixelXpert/sqlite3"
    }

    val TAG: String = javaClass.simpleName

    override fun onBind(intent: Intent): IBinder {
        return RootServicesIPC()
    }

    private inner class RootServicesIPC : IRootProviderService.Stub() {
        private var mLSPosedMID: Int = -1
        private var mLSPosedEnabled: Boolean = false

        override fun checkLSPosedDB(packageName: String): Boolean {
            var target = packageName
            if (Constants.SYSTEM_FRAMEWORK_PACKAGE == target) {
                target = "system"
            }

            return try {
                if (mLSPosedMID < 0 || !mLSPosedEnabled) {
                    getModuleMID()
                }

                if (!mLSPosedEnabled) return false

                val query = "select count(*) from scope where mid = $mLSPosedMID and user_id = 0 and app_pkg_name = '$target'"
                val result = runLSposedSQLiteQuery(query)
                result.firstOrNull() == "1"
            } catch (_: Throwable) {
                false
            }
        }

        override fun isPackageInstalled(packageName: String): Boolean {
            val pm = packageManager
            return try {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                pm.getApplicationInfo(packageName, 0).enabled
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

        override fun activateInLSPosed(packageName: String): Boolean {
            var target = packageName
            if (Constants.SYSTEM_FRAMEWORK_PACKAGE == target) {
                target = "system"
            }

            if (checkLSPosedDB(target)) return true

            getModuleMID()

            if (!mLSPosedEnabled) {
                enableModuleLSPosed()
                if (checkLSPosedDB(target)) return true
            }

            runLSposedSQLiteQuery("insert into scope (mid, app_pkg_name, user_id) values ($mLSPosedMID, '$target', 0)")
            return checkLSPosedDB(target)
        }

        private fun enableModuleLSPosed() {
            runLSposedSQLiteQuery("update modules set enabled = 1 where mid = $mLSPosedMID")
        }

        private fun getModuleMID() {
            val midResult = runLSposedSQLiteQuery("select mid from modules where module_pkg_name = '${BuildConfig.APPLICATION_ID}'")
            midResult.firstOrNull()?.let {
                mLSPosedMID = it.toIntOrNull() ?: -1
            }

            val enabledResult = runLSposedSQLiteQuery("select enabled from modules where mid = $mLSPosedMID")
            mLSPosedEnabled = enabledResult.firstOrNull() == "1"
        }

        private fun runLSposedSQLiteQuery(command: String): List<String> {
            return Shell.cmd("$SQLITE_BIN $LSPD_DB_PATH \"$command\"").exec().out
        }

        override fun getFileSystemService(): IBinder {
            return FileSystemManager.getService()
        }
    }
}
