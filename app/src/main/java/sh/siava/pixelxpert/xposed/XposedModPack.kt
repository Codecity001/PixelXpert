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

	/**
	 * Called before this pack's generation is retired.
	 *
	 * Packs that own threads, sockets, receivers or other long-lived resources
	 * must release them here; hooks themselves are replaced or removed by the
	 * framework.
	 */
	open fun onUnloaded() {
	}

	/**
	 * Collects framework references a hot reload must carry over.
	 *
	 * [outState] is per-pack and only reached by this pack's next generation.
	 * Only framework objects (views, controllers, managers, interactors) may be
	 * stored; module objects and anything that still references this pack must
	 * be released in [onUnloaded] instead.
	 */
	fun saveHotReloadState(outState: MutableMap<String, Any?>) {
		onSaveHotReloadState(outState)
	}

	/** Restores the values collected by [saveHotReloadState]. */
	fun restoreHotReloadState(savedState: Map<String, Any?>) {
		onRestoreHotReloadState(savedState)
	}

	protected open fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
	}

	protected open fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
	}
}
