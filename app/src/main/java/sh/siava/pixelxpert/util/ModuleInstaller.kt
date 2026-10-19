package sh.siava.pixelxpert.util

import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.zip.ZipFile
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants

/**
 * Version of the module that is currently installed, or the one staged for
 * the next boot.
 *
 * [pending] says which of the two a root read found: Magisk unpacks an
 * update into `/data/adb/modules_update/<module>` and only moves it into
 * `/data/adb/modules` on reboot, so a file present there means the user has
 * an update waiting for a restart.
 */
data class ModuleVersion(
	val name: String,
	val code: Int,
	val pending: Boolean,
)

/**
 * The root-side half of the updater: version discovery, module installation
 * and the preference migration a Magisk install runs afterwards.
 *
 * Everything here blocks on a root shell, so every caller must already be
 * off the main thread - `Shell.cmd` would otherwise jank the frame that
 * triggered it, and `getShell()` can sit waiting on a root grant.
 *
 * The commands are copied rather than tidied: they run as root against
 * Magisk's and KernelSU's own tooling, where a "cleaner" rewrite is just a
 * way to get the quoting subtly wrong.
 */
object ModuleInstaller {

	val updateDir: String = "${Constants.MAGISK_UPDATE_DIR}/${Constants.MOD_NAME}"

	/**
	 * Reads the module version, preferring the staged update.
	 *
	 * `modules_update` is consulted first; when that read comes back short
	 * the *app's* own version is used instead - the two are cut from the
	 * same release, so the app version stands in for the installed module
	 * version rather than parsing `modules/module.prop` too.
	 */
	fun currentVersion(): ModuleVersion = try {
		val props = Properties()
		Shell.cmd("cat $updateDir/module.prop").exec().getOut()
			.joinToString("\n")
			.let { props.load(it.reader()) }

		val name = props.getProperty("version") ?: throw IllegalStateException("no staged module")
		ModuleVersion(name, props.getProperty("versionCode")?.toInt() ?: -1, pending = true)
	} catch (ignored: Throwable) {
		ModuleVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, pending = false)
	}

	fun hasRoot(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)

	/**
	 * Installs a downloaded module zip through Magisk and KernelSU.
	 *
	 * The download may itself be wrapped in a second zip - that is how some
	 * release channels publish - so the archive is unzipped once and
	 * re-examined: a single-entry archive is the inner module, anything else
	 * is passed through untouched. Both managers are invoked because at
	 * install time it is not yet known which one this device runs, and each
	 * ignores a command meant for the other.
	 *
	 * @return true when the install command ran.
	 */
	fun installDoubleZip(downloadedPath: String): Boolean = try {
		val tempFile = File.createTempFile("doubleZ", ".zip")
		Shell.cmd("cp $downloadedPath ${tempFile.absolutePath}").exec()

		val moduleZip = ZipFile(tempFile).use { archive ->
			if (archive.stream().count() == 1L) {
				val inner = File.createTempFile("singleZ", ".zip")
				archive.getInputStream(archive.entries().nextElement()).use { input ->
					FileOutputStream(inner).use { output -> input.copyTo(output) }
				}
				inner
			} else {
				tempFile
			}
		}

		Shell.cmd("magisk --install-module ${moduleZip.absolutePath}").exec()
		Shell.cmd("ksud module install ${moduleZip.absolutePath}").exec()

		if (moduleZip != tempFile) moduleZip.delete()
		tempFile.delete()
		true
	} catch (ignored: Throwable) {
		false
	}

	/**
	 * Writes the media volume step count into the staged module.
	 *
	 * Magisk reads `system.prop` out of the module folder when it activates
	 * it, so the value has to be in place *before* the reboot that promotes
	 * `modules_update` to `modules` - which is exactly when Magisk calls
	 * back into the app with `migratePrefs`.
	 *
	 * Ten steps or fewer is Magisk's "leave it alone" case: the prop is
	 * removed rather than written, so an AOSP default is never overridden
	 * for no gain.
	 */
	fun applyVolumeSteps(volumeSteps: Int, stagedModuleDir: String) {
		if (volumeSteps <= 10) {
			Shell.cmd("rm -Rf $stagedModuleDir/system.prop").exec()
			return
		}
		Shell.cmd(
			"echo ro.config.media_vol_steps=$volumeSteps > $stagedModuleDir/system.prop",
		).exec()
	}
}
