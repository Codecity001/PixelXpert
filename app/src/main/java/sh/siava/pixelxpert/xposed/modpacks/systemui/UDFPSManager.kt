package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf

/**
 * Hides the under-display fingerprint icon and/or the background circle on
 * the keyguard.
 *
 * The alpha of both `ImageView`s inside `DeviceEntryIconView` is driven from
 * the two preferences (with a forced refresh when either key changes), and
 * when only the circle is hidden the `useBackgroundProtection` state flow of
 * the view model is swapped for one pinned to false. The view is also kept
 * stacked above the wallpaper subject by forcing `z` on attach and on every
 * layout pass.
 */
@SystemUIModPack
class UDFPSManager(context: Context) : XposedModPack(context) {

	companion object {
		private const val TRANSPARENT = 0
		private const val OPAQUE = 255
		private var transparentBG = false
		private var transparentFG = false
	}

	private var mDeviceEntryIconView: View? = null
	private var ReadonlyStateFlowClass: ReflectedClass? = null

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		transparentBG = Xprefs.getBoolean("fingerprint_circle_hide", false)
		transparentFG = Xprefs.getBoolean("fingerprint_icon_hide", false)

		if (key.isEmpty()) return

		when (key[0]!!) {
			"fingerprint_circle_hide", "fingerprint_icon_hide" -> setUDFPSGraphics(true)
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val DeviceEntryIconViewClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.DeviceEntryIconView")
		val DeviceEntryIconViewModelClass = ReflectedClass.of("com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel")

		ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

		DeviceEntryIconViewModelClass
			.afterConstruction()
			.run {
				if ((transparentBG && !transparentFG)) {
					try {
						setObjectField(it.thisObject, "useBackgroundProtection", ReadonlyStateFlowClass!!.clazz!!.constructors[0].newInstance(getStateFlowImplOf(false)))
					} catch (ignored: Throwable) {
					}
				}
			}

		DeviceEntryIconViewClass
			.afterConstruction()
			.run { param ->
				mDeviceEntryIconView = param.thisObject as View

				setUDFPSGraphics(false)

				//making sure it remains on top on wallpaper subject
				mDeviceEntryIconView!!.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
					override fun onViewAttachedToWindow(v: View) {
						v.setZ(100f)
					}

					override fun onViewDetachedFromWindow(v: View) {
					}
				})

				mDeviceEntryIconView!!.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
					v.setZ(100f)
				}
			}
	}

	/** @noinspection ConstantValue*/
	private fun setUDFPSGraphics(force: Boolean) {
		if (mDeviceEntryIconView == null) return

		if (transparentFG || force) {
			(getObjectField(mDeviceEntryIconView, "iconView") as ImageView)
				.setImageAlpha(
					if (transparentFG) {
						TRANSPARENT
					} else {
						OPAQUE
					},
				)
		}
		if (transparentFG || transparentBG || force) {
			(getObjectField(mDeviceEntryIconView, "bgView") as ImageView)
				.setImageAlpha(
					if (transparentFG || transparentBG) {
						TRANSPARENT
					} else {
						OPAQUE
					},
				)
		}
	}
}
