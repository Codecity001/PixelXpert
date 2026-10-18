package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf

@SystemUIModPack
class UDFPSManager(context: Context) : XposedModPack(context) {

    private var deviceEntryIconView: View? = null
    private var readonlyStateFlowClass: ReflectedClass? = null

    companion object {
        private const val TRANSPARENT = 0
        private const val OPAQUE = 255
        private var transparentBG = false
        private var transparentFG = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        transparentBG = Xprefs.getBoolean("fingerprint_circle_hide", false)
        transparentFG = Xprefs.getBoolean("fingerprint_icon_hide", false)

        if (key.isEmpty()) return

        when (key[0]) {
            "fingerprint_circle_hide", "fingerprint_icon_hide" -> setUDFPSGraphics(true)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val deviceEntryIconViewClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.DeviceEntryIconView")
        val deviceEntryIconViewModelClass = ReflectedClass.of("com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel")

        readonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

        deviceEntryIconViewModelClass
            .afterConstruction()
            .run { runParam ->
                if (transparentBG && !transparentFG) {
                    try {
                        val constructor = readonlyStateFlowClass?.clazz?.constructors?.firstOrNull()
                        val stateFlow = constructor?.newInstance(getStateFlowImplOf(false))
                        if (stateFlow != null) {
                            setObjectField(runParam.thisObject, "useBackgroundProtection", stateFlow)
                        }
                    } catch (_: Throwable) {}
                }
            }

        deviceEntryIconViewClass
            .afterConstruction()
            .run { runParam ->
                val view = runParam.thisObject as? View ?: return@run
                deviceEntryIconView = view

                setUDFPSGraphics(false)

                // Making sure it remains on top of wallpaper subject
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.z = 100f
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })

                view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.z = 100f
                }
            }
    }

    private fun setUDFPSGraphics(force: Boolean) {
        val view = deviceEntryIconView ?: return

        if (transparentFG || force) {
            (getObjectField(view, "iconView") as? ImageView)?.imageAlpha =
                if (transparentFG) TRANSPARENT else OPAQUE
        }
        if (transparentFG || transparentBG || force) {
            (getObjectField(view, "bgView") as? ImageView)?.imageAlpha =
                if (transparentFG || transparentBG) TRANSPARENT else OPAQUE
        }
    }
}
