package sh.siava.pixelxpert.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.util.ThemePackMapping.TYPE_DRAWABLE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Discovers installed icon packs and records which substitutions are on.
 *
 * The split of responsibilities: the pack declares an activity carrying
 * [ACTION_PACK], discovery reads two string arrays it names, and every
 * switch the UI flips is flattened back into the single JSON map under
 * [ThemePackMapping.DRAWABLE_MAPPING_KEY] - the string the Xposed side
 * consumes. Nothing here draws anything; it only decides what the hooks
 * should substitute.
 *
 * Discovery runs off the main thread on a cached pool and notifies
 * [IconPackQueryListener]s afterwards, so the screen can drop its loading
 * state once the data has arrived.
 */
class IconPackUtil private constructor(context: Context) {

	private val packageManager: PackageManager = context.packageManager
	private val appContext: Context = context.applicationContext

	var resourceMapping: ResourceMapping = ResourceMapping()
		private set
	var iconPackMapping: IconPackMapping = IconPackMapping()
		private set

	private val listeners: MutableSet<IconPackQueryListener> =
		ConcurrentHashMap.newKeySet()

	private val executorService: ExecutorService = Executors.newCachedThreadPool()

	@Volatile
	var isQuerying: Boolean = false
		private set

	@Volatile
	private var hasLoaded: Boolean = false

	interface IconPackQueryListener {
		fun onIconPacksLoaded(mapping: ResourceMapping, packMapping: IconPackMapping) {}
	}

	fun addListener(listener: IconPackQueryListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: IconPackQueryListener) {
		listeners.remove(listener)
	}

	private val prefs: SharedPreferences
		get() = PrefStore.open(appContext)

	fun isAnythingEnabled(): Boolean {
		if (isQuerying) return false
		return resourceMapping.values.any { icons -> icons.any { it.isEnabled } }
	}

	@Synchronized
	fun queryIconPacks(force: Boolean) {
		if (force || !hasLoaded) {
			executorService.submit { queryMappingInternal() }
			return
		}
		listeners.forEach { it.onIconPacksLoaded(resourceMapping, iconPackMapping) }
	}

	private fun queryMappingInternal() {
		isQuerying = true
		hasLoaded = false
		resourceMapping = ResourceMapping()
		iconPackMapping = IconPackMapping()
		val prefMapping = ThemePackMapping.loadMapping(prefs, TYPE_DRAWABLE)

		// The action is an external contract third-party packs build
		// against, so it stays exactly as it is even though the application
		// id has changed - renaming it would find no packs at all.
		val activities = packageManager.queryIntentActivities(
			Intent(ACTION_PACK),
			0,
		)

		for (activity in activities) {
			try {
				val packageName = activity.activityInfo.packageName
				// The class name carries the array name as its suffix, which
				// is how a pack points at its own pair of string arrays.
				val packName = activity.activityInfo.name
					.replaceFirst(Regex("^${Regex.escape(packageName)}\\."), "")
				val packLabel = activity.activityInfo.loadLabel(packageManager).toString()

				val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
				val author = try {
					packageInfo.applicationInfo?.metaData?.getString("packauthor")
				} catch (ignored: Exception) {
					null
				} ?: "Unknown"

				val packResources = packageManager.getResourcesForApplication(packageName)

				val packData = packResources.getStringArray(
					packResources.getIdentifier(packName, "array", packageName),
				)
				val resNames = packResources.getStringArray(
					getStringArrayID(packData[0], packResources, packageName),
				)
				val replacements = packResources.getStringArray(
					getStringArrayID(packData[1], packResources, packageName),
				)

				val iconPack = IconPack(packageName, packLabel, author)

				for (i in resNames.indices) {
					val rep = ReplacementIcon(iconPack, replacements[i])
					rep.isEnabled = prefMapping.isEnabled(resNames[i], iconPack.mPackageName, replacements[i])
					resourceMapping.add(resNames[i], rep)
					iconPackMapping.add(iconPack, resNames[i], rep)
				}
			} catch (ignored: Exception) {
				// A pack with a malformed pair of arrays is skipped.
			}
		}

		listeners.forEach { it.onIconPacksLoaded(resourceMapping, iconPackMapping) }
		hasLoaded = true
		isQuerying = false
	}

	fun enable(iconPack: IconPack) = setEnabled(iconPack, true)

	fun disable(iconPack: IconPack) = setEnabled(iconPack, false)

	private fun setEnabled(iconPack: IconPack, enabled: Boolean) {
		val thisPack = iconPackMapping[iconPack] ?: return
		thisPack.forEach { (resName, replacementIcons) ->
			replacementIcons.firstOrNull()?.let {
				resourceMapping.setEnabled(resName, it, enabled)
			}
		}
		savePrefs()
	}

	/**
	 * Flattens every enabled replacement back into the JSON map.
	 *
	 * One resName can be offered by several packs; the first enabled wins,
	 * which is what decides the value the hooks will read.
	 */
	private fun savePrefs() {
		val mapping = ThemePackMapping.Mapping()
		resourceMapping.forEach { (resName, replacementIcons) ->
			replacementIcons.firstOrNull { it.isEnabled }?.let {
				mapping.add(resName, it.iconPack.mPackageName, it.mReplacementRes)
			}
		}
		ThemePackMapping.saveMapping(prefs, TYPE_DRAWABLE, mapping)
	}

	fun disable(resName: String) {
		resourceMapping.reset(resName)
		savePrefs()
	}

	fun setEnabled(resName: String, replacementIcon: ReplacementIcon) {
		resourceMapping.setEnabled(resName, replacementIcon, true)
		savePrefs()
	}

	fun getEnabledState(iconPack: IconPack): Int {
		val thisPack = iconPackMapping[iconPack].orEmpty()
		var fullyEnabled = true
		var partialEnabled = false

		thisPack.forEach { (resName, _) ->
			val replacementIcon = getEnabled(resName)
			if (replacementIcon != null && replacementIcon.iconPack == iconPack) {
				partialEnabled = true
			} else {
				fullyEnabled = false
			}
		}

		return when {
			fullyEnabled -> ENABLED_FULL
			partialEnabled -> ENABLED_PARTIAL
			else -> DISABLED
		}
	}

	fun getEnabled(resName: String): ReplacementIcon? =
		resourceMapping.getReplacementIcons(resName)?.firstOrNull { it.isEnabled }

	private fun getStringArrayID(resName: String, packResources: Resources, packageName: String): Int =
		packResources.getIdentifier(resName, "array", packageName)

	data class IconPack(val mPackageName: String, val mName: String, val mAuthor: String) {
		// Cached the first time the row draws, so repeated recompositions
		// do not go back to the pack's resources.
		var packDrawables: List<Drawable?> = emptyList()
			private set

		// Equality covers the label and the package only, leaving the author
		// out - and that pair is the key the pack mapping is grouped under,
		// so the two have to agree or lookups would miss their own entry.
		override fun equals(other: Any?): Boolean = other is IconPack &&
			other.mName == mName && other.mPackageName == mPackageName

		override fun hashCode(): Int = 31 * mPackageName.hashCode() + mName.hashCode()

		fun getPackDrawables(context: Context): List<Drawable?> {
			if (packDrawables.isNotEmpty()) return packDrawables

			instance ?: return emptyList()
			val packMapping = instance!!.iconPackMapping[this] ?: return emptyList()
			if (packMapping.isEmpty()) return emptyList()

			val previewResNames = context.resources.getStringArray(R.array.iconpack_preview_icons)
			val drawables = ArrayList<Drawable?>(previewResNames.size)

			for (preview in previewResNames) {
				// Prefer a resource whose name contains the preview slot so the
				// row reads as a real sample of the pack; fall back to any
				// resource in it, and stop rather than loop if the pack offers
				// none at all.
				val resName = packMapping.keys.firstOrNull { it.contains(preview) }
					?: packMapping.keys.randomOrNull()
				if (resName == null) break
				drawables.add(packMapping[resName]?.firstOrNull()?.getDrawable())
			}
			packDrawables = drawables
			return drawables
		}
	}

	inner class ReplacementIcon(val iconPack: IconPack, val mReplacementRes: String) {
		var isEnabled: Boolean = false

		fun getDrawable(): Drawable? = try {
			val packResources = packageManager.getResourcesForApplication(iconPack.mPackageName)
			val resID = packResources.getIdentifier(
				mReplacementRes,
				"drawable",
				iconPack.mPackageName,
			)
			if (resID == 0) null else ResourcesCompat.getDrawable(packResources, resID, null)
		} catch (ignored: Throwable) {
			// A pack whose drawable is missing simply shows no preview.
			null
		}

		override fun equals(other: Any?): Boolean = other is ReplacementIcon &&
			other.iconPack == iconPack && other.mReplacementRes == mReplacementRes

		override fun hashCode(): Int = 31 * iconPack.hashCode() + mReplacementRes.hashCode()
	}

	inner class ResourceMapping : HashMap<String, ArrayList<ReplacementIcon>>() {

		fun add(originalRes: String, replacementIcon: ReplacementIcon) {
			getOrPut(originalRes) { ArrayList() }.add(replacementIcon)
		}

		fun getOriginalResList(): List<String> = keys.toList()

		fun getReplacementIcons(originalRes: String): List<ReplacementIcon>? = get(originalRes)

		fun reset(resName: String) {
			get(resName)?.forEach { it.isEnabled = false }
		}

		fun setEnabled(resName: String, replacementIcon: ReplacementIcon, enabled: Boolean) {
			if (!containsKey(resName)) return
			reset(resName)
			get(resName)?.forEach { rep ->
				if (rep == replacementIcon) rep.isEnabled = enabled
			}
		}
	}

	inner class IconPackMapping : HashMap<IconPack, HashMap<String, ArrayList<ReplacementIcon>>>() {

		fun add(iconPack: IconPack, resName: String, replacementIcon: ReplacementIcon) {
			val thisPack = getOrPut(iconPack) { HashMap() }
			thisPack.getOrPut(resName) { ArrayList() }.add(replacementIcon)
		}

		fun getIconPacks(): List<IconPack> = keys.toList()
	}

	companion object {
		/**
		 * Declared by every pack that wants to be found.
		 *
		 * Kept verbatim: icon packs already installed on the device declare
		 * this action, so it is an external contract rather than something
		 * derived from this app's id.
		 */
		const val ACTION_PACK: String = "sh.siava.pixelxpert.iconpack"

		const val ENABLED_FULL: Int = 0
		const val ENABLED_PARTIAL: Int = 1
		const val DISABLED: Int = 2

		@Volatile
		private var instance: IconPackUtil? = null

		@Synchronized
		fun getInstance(context: Context): IconPackUtil = instance ?: IconPackUtil(context).also { instance = it }
	}
}
