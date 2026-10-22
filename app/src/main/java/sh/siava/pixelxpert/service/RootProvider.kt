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

/**
 * Root-side service behind [IRootProviderService].
 *
 * These run as root on the user's device: the LSPosed scope database lives
 * behind a sqlite binary that only root can read, and the package lookups run
 * there for the same reason.
 */
class RootProvider : RootService() {

	override fun onBind(intent: Intent): IBinder = RootServicesIPC()

	private inner class RootServicesIPC : IRootProviderService.Stub() {

		private var lsposedMID = -1
		private var lsposedEnabled = false

		override fun checkLSPosedDB(packageName: String?): Boolean {
			var pkg = packageName ?: return false
			if (Constants.SYSTEM_FRAMEWORK_PACKAGE == pkg) pkg = "system"

			return try {
				if (lsposedMID < 0 || !lsposedEnabled) moduleMID()
				if (!lsposedEnabled) return false

				lsposedQuery(
					"select count(*) from scope where mid = $lsposedMID " +
						"and user_id = 0 and app_pkg_name = '$pkg'",
				).firstOrNull() == "1"
			} catch (ignored: Throwable) {
				false
			}
		}

		override fun isPackageInstalled(packageName: String?): Boolean {
			val pm = packageManager ?: return false
			return try {
				pm.getPackageInfo(packageName ?: return false, PackageManager.GET_ACTIVITIES)
				pm.getApplicationInfo(packageName, 0).enabled
			} catch (ignored: PackageManager.NameNotFoundException) {
				false
			}
		}

		override fun activateInLSPosed(packageName: String?): Boolean {
			var pkg = packageName ?: return false
			// Newer LSPosed versions renamed the framework package.
			if (Constants.SYSTEM_FRAMEWORK_PACKAGE == pkg) pkg = "system"

			if (checkLSPosedDB(pkg)) return true

			moduleMID()

			if (!lsposedEnabled) {
				enableModule()
				if (checkLSPosedDB(pkg)) return true
			}

			lsposedQuery(
				"insert into scope (mid, app_pkg_name, user_id) " +
					"values ($lsposedMID, '$pkg', 0)",
			)

			return checkLSPosedDB(pkg)
		}

		override fun getFileSystemService(): IBinder = FileSystemManager.getService()

		/** Enables the module inside LSPosed's own modules table. */
		private fun enableModule() {
			lsposedQuery("update modules set enabled = 1 where mid = $lsposedMID")
		}

		/** Reads this module's LSPosed id and whether it is enabled. */
		private fun moduleMID() {
			lsposedMID = lsposedQuery(
				"select mid from modules where module_pkg_name = '${BuildConfig.APPLICATION_ID}'",
			).firstOrNull()?.toIntOrNull() ?: -1

			lsposedEnabled = lsposedQuery(
				"select enabled from modules where mid = $lsposedMID",
			).firstOrNull() == "1"
		}

		/** Runs one query against the LSPosed scope database as root. */
		private fun lsposedQuery(command: String): List<String> =
			Shell.cmd("$SQLITE_BIN $LSPD_DB_PATH \"$command\"").exec().getOut()
	}

	private companion object {
		const val LSPD_DB_PATH = "/data/adb/lspd/config/modules_config.db"
		const val SQLITE_BIN = "/data/adb/modules/PixelXpert/sqlite3"
	}
}
