package sh.siava.pixelxpert.data

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
class RemotePrefProvider : RemotePreferenceProvider(
	BuildConfig.APPLICATION_ID,
	arrayOf(RemotePreferenceFile(PrefStore.FILE_NAME, true)),
)
