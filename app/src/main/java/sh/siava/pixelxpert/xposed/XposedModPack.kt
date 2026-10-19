package sh.siava.pixelxpert.xposed

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * Base class every mod pack extends.
 *
 * The seemingly odd `extends Logger` is what puts a bare `log(...)` in scope
 * inside every pack.
 *
 * A pack is constructed by [XPLauncher] with the hooked process's [Context],
 * gets one [onPreferenceUpdated] for free before anything is hooked (so a pack
 * can seed its state without a null check), and then one [onPackageLoaded].
 */
abstract class XposedModPack(context: Context) : Logger() {
	@JvmField
	protected val mContext: Context = context

	/** Called when a preference changed; no keys means "load everything". */
	abstract fun onPreferenceUpdated(vararg key: String?)

	/** Called once, after [onPreferenceUpdated], with the package being readied. */
	@Throws(Throwable::class)
	abstract fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam)
}
