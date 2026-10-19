package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.util.ArrayList
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.util.ThemePackMapping
import sh.siava.pixelxpert.util.ThemePackMapping.DRAWABLE_MAPPING_KEY
import sh.siava.pixelxpert.util.ThemePackMapping.IDMapping
import sh.siava.pixelxpert.util.ThemePackMapping.OverlayID
import sh.siava.pixelxpert.util.ThemePackMapping.OverlayIDName
import sh.siava.pixelxpert.util.ThemePackMapping.TYPE_DRAWABLE
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod

/**
 * Substitutes drawables for the ones a selected icon pack replaces, inside
 * SystemUI and the Settings app (hence the two annotations).
 *
 * The pack resolves the preference-stored name mapping into resource-id
 * pairs once the package is loaded, then hooks the four `Resources.getDrawable`
 * overloads SystemUI and Settings actually call; a hit is served from the
 * icon pack's own resources instead of the original. When the mapping is
 * empty the hooks are removed again, and a mapping change also toggles the
 * Google GX overlay off and on so the system rereads its resources.
 */
@SystemUIModPack
@SettingsModPack
class IconPacks(context: Context) : XposedModPack(context) {

	companion object {
		var drawableMapping: IDMapping = IDMapping()
	}

	private val mPackageManager: PackageManager = mContext.packageManager
	private val hooks: MutableList<XposedInterface.HookHandle> = ArrayList()
	private var mPackageLoaded = false

	override fun onPreferenceUpdated(vararg key: String?) {
		updateMapping(key.isNotEmpty() && key[0] == DRAWABLE_MAPPING_KEY)
	}

	private fun hookAll() {
		if (hooks.isEmpty() && mPackageLoaded && !drawableMapping.isEmpty()) {
			hooks.addAll(
				ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawable", Integer.TYPE, Resources.Theme::class.java)
					.beforeThat { param ->
						val drawable = getDrawable(param.args[0] as Int, param.args[1] as Resources.Theme)
						if (drawable != null) {
							param.setResult(drawable)
						}
					},
			)

			hooks.addAll(
				ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawable", Integer.TYPE)
					.beforeThat { param ->
						val drawable = getDrawable(param.args[0] as Int, mContext.theme)
						if (drawable != null) {
							param.setResult(drawable)
						}
					},
			)

			hooks.addAll(
				ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawableForDensity", Integer.TYPE, Integer.TYPE, Resources.Theme::class.java)
					.beforeThat { param ->
						val drawable = getDrawable(param.args[0] as Int, param.args[2] as Resources.Theme)
						if (drawable != null) {
							param.setResult(drawable)
						}
					},
			)

			hooks.addAll(
				ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawableForDensity", Integer.TYPE, Integer.TYPE)
					.beforeThat { param ->
						val drawable = getDrawable(param.args[0] as Int, mContext.theme)
						if (drawable != null) {
							param.setResult(drawable)
						}
					},
			)
		}
	}

	private fun unhookAll() {
		for (hook in hooks) {
			hook.unhook()
		}
		hooks.clear()
	}

	private fun updateMapping(shallRefresh: Boolean) {
		Thread {
			drawableMapping = getIDMapping(TYPE_DRAWABLE, "drawable")
			if (drawableMapping.isEmpty()) {
				unhookAll()
			} else {
				hookAll()
			}
			if (shallRefresh)
				refreshUI()
		}.start()
	}

	private fun refreshUI() {
		XPLauncher.enqueueProxyCommand { proxy -> proxy.runRootCommand("cmd overlay disable com.google.android.systemui.gxoverlay; cmd overlay enable com.google.android.systemui.gxoverlay") }
	}

	//noinspection SameParameterValue
	private fun getIDMapping(prefType: Int, type: String): IDMapping {
		val prefMapping = ThemePackMapping.loadMapping(Xprefs, prefType)

		val idMapping = IDMapping()
		for (key in prefMapping.keys) {
			try {
				val overlayIDName = prefMapping[key]!!

				val mappingID = getMappingID(key, type)

				//noinspection DataFlowIssue
				val replacementID = getReplacementID(overlayIDName, type)

				if (replacementID == 0 || mappingID == 0) //some id cannot be found. ignore
				{
					continue
				}

				idMapping.put(mappingID, OverlayID(overlayIDName.packageName, replacementID))
			} catch (ignored: Throwable) {
			}
		}
		return idMapping
	}

	@SuppressLint("DiscouragedApi")
	private fun getReplacementID(overlayIDName: OverlayIDName, type: String): Int {
		return mPackageManager.getResourcesForApplication(overlayIDName.packageName).getIdentifier(overlayIDName.resName, type, overlayIDName.packageName)
	}

	@SuppressLint("DiscouragedApi")
	private fun getMappingID(key: String, type: String): Int {
		val keyParts = key.split(":")
		val resName = keyParts[keyParts.size - 1]

		var sourcePackage = if (keyParts.size > 1) keyParts[0] else mContext.packageName
		if (sourcePackage == "module") {
			sourcePackage = BuildConfig.APPLICATION_ID
		}

		return mContext.resources.getIdentifier(resName, type, sourcePackage)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		mPackageLoaded = true
		hookAll()
	}

	private fun getDrawable(id: Int, theme: Resources.Theme): Drawable? {
		if (drawableMapping.containsKey(id)) {
			val overlayID = drawableMapping[id]!!

			//noinspection DataFlowIssue
			val res = mPackageManager.getResourcesForApplication(overlayID.packageName)

			return Drawable.createFromXml(res, res.getXml(overlayID.resID), theme)
		}
		return null
	}
}
