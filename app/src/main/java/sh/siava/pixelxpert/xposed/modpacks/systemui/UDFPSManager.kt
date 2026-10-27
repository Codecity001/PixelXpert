package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
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
	private var mDeviceEntryIconViewModel: Any? = null
	private var mAttachListener: View.OnAttachStateChangeListener? = null
	private var mLayoutListener: View.OnLayoutChangeListener? = null
	private var ReadonlyStateFlowClass: ReflectedClass? = null

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		transparentBG = Xprefs.getBoolean("fingerprint_circle_hide", false)
		transparentFG = Xprefs.getBoolean("fingerprint_icon_hide", false)

		if (key.isEmpty()) return

		when (key[0]!!) {
			"fingerprint_circle_hide", "fingerprint_icon_hide" -> {
				setUDFPSGraphics(true)
				applyBackgroundProtection()
			}
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val DeviceEntryIconViewClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.DeviceEntryIconView")
		val DeviceEntryIconViewModelClass = ReflectedClass.of(
			"com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel",
		)

		ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

		DeviceEntryIconViewModelClass
			.afterConstruction()
			.run { param ->
				mDeviceEntryIconViewModel = param.thisObject
				applyBackgroundProtection()
			}

		DeviceEntryIconViewClass
			.afterConstruction()
			.run { param -> adoptView(param.thisObject as View) }
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mDeviceEntryIconView?.let { outState["view"] = it }
		mDeviceEntryIconViewModel?.let { outState["viewModel"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		mDeviceEntryIconViewModel = savedState["viewModel"]
		applyBackgroundProtection()
		(savedState["view"] as? View)?.let { adoptView(it) }
	}

	override fun onUnloaded() {
		detachView()
		mDeviceEntryIconViewModel = null
	}

	private fun adoptView(view: View) {
		if (mDeviceEntryIconView !== view) {
			detachView()
			mDeviceEntryIconView = view

			val attachListener =
				object : View.OnAttachStateChangeListener {
					override fun onViewAttachedToWindow(v: View) {
						v.setZ(100f)
					}

					override fun onViewDetachedFromWindow(v: View) {
					}
				}
			view.addOnAttachStateChangeListener(attachListener)
			mAttachListener = attachListener

			val layoutListener =
				View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.setZ(100f) }
			view.addOnLayoutChangeListener(layoutListener)
			mLayoutListener = layoutListener
		}

		view.setZ(100f)
		setUDFPSGraphics(false)
	}

	private fun detachView() {
		val view = mDeviceEntryIconView
		val attachListener = mAttachListener
		val layoutListener = mLayoutListener

		if (view != null) {
			val removeListeners =
				Runnable {
					attachListener?.let { view.removeOnAttachStateChangeListener(it) }
					layoutListener?.let { view.removeOnLayoutChangeListener(it) }
				}
			if (Looper.myLooper() == Looper.getMainLooper()) {
				removeListeners.run()
			} else {
				view.post(removeListeners)
			}
		}

		mAttachListener = null
		mLayoutListener = null
		mDeviceEntryIconView = null
	}

	private fun applyBackgroundProtection() {
		if (!(transparentBG && !transparentFG)) return

		val viewModel = mDeviceEntryIconViewModel ?: return
		val flowClass = ReadonlyStateFlowClass?.clazz ?: return

		try {
			setObjectField(
				viewModel,
				"useBackgroundProtection",
				flowClass.constructors[0].newInstance(getStateFlowImplOf(false)),
			)
		} catch (ignored: Throwable) {
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
