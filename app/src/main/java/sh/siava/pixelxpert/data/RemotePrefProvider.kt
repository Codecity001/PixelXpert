package sh.siava.pixelxpert.data

import android.database.Cursor
import android.net.Uri
import android.os.Binder
import com.crossbowffs.remotepreferences.RemotePreferenceFile
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import sh.siava.pixelxpert.BuildConfig

/**
 * Content provider that serves this app's preferences file to hooked processes.
 *
 * The library class is abstract precisely so that the two things that must
 * not drift - the authority and the file name - are supplied here rather
 * than read from metadata, which is what this subclass does.
 *
 * Cross-process contract, both halves written out:
 *
 *  * authority = [REMOTE_PREF_AUTHORITY] = `BuildConfig.APPLICATION_ID`
 *  * file = [PrefStore.FILE_NAME] = `BuildConfig.APPLICATION_ID + "_preferences"`
 *
 * The Xposed side opens
 * `ExtendedRemotePreferences(context, APPLICATION_ID, APPLICATION_ID + "_preferences", true)`,
 * so this provider must be declared with the same authority or
 * `XPLauncher`'s `waitForXprefsLoad` never returns. Declared in the manifest as
 * `.data.RemotePrefProvider`, `directBootAware` and exported.
 */
class RemotePrefProvider :
	RemotePreferenceProvider(
		BuildConfig.APPLICATION_ID,
		arrayOf(RemotePreferenceFile(PrefStore.FILE_NAME, true)),
	) {
	/**
	 * Records the caller as a hooked process.
	 *
	 * `XPLauncher` reads a probe key before it loads any mod pack, a
	 * query is the earliest info that the module is running in the caller.
	 * [HooksMonitor] later compares the pid with the running process.
	 */
	override fun query(
		uri: Uri,
		projection: Array<String>?,
		selection: String?,
		selectionArgs: Array<String>?,
		sortOrder: String?,
	): Cursor? {
		val cursor = super.query(uri, projection, selection, selectionArgs, sortOrder)
		context?.let { HooksMonitor.onHookReported(it, Binder.getCallingUid(), Binder.getCallingPid()) }
		return cursor
	}
}
