package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.utils.ThemePackMapping.DRAWABLE_MAPPING_KEY
import sh.siava.pixelxpert.utils.ThemePackMapping.IDMapping
import sh.siava.pixelxpert.utils.ThemePackMapping.Mapping
import sh.siava.pixelxpert.utils.ThemePackMapping.OverlayID
import sh.siava.pixelxpert.utils.ThemePackMapping.OverlayIDName
import sh.siava.pixelxpert.utils.ThemePackMapping.TYPE_DRAWABLE
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import kotlin.concurrent.thread

@SystemUIModPack
@SettingsModPack
class IconPacks(context: Context) : XposedModPack(context) {

    private val mPackageManager: PackageManager = mContext.packageManager
    private val hooks = mutableListOf<XposedInterface.HookHandle>()
    private var mPackageLoaded = false

    companion object {
        @JvmStatic
        var drawableMapping = IDMapping()
    }

    override fun onPreferenceUpdated(vararg key: String) {
        updateMapping(key.isNotEmpty() && key[0] == DRAWABLE_MAPPING_KEY)
    }

    private fun hookAll() {
        if (hooks.isEmpty() && mPackageLoaded && !drawableMapping.isEmpty()) {
            hooks.addAll(
                ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawable", java.lang.Integer.TYPE, Resources.Theme::class.java)
                    .beforeThat { param ->
                        val resId = param.args.getOrNull(0) as? Int ?: return@beforeThat
                        val drawable = getDrawable(resId, param.args.getOrNull(1) as? Resources.Theme)
                        if (drawable != null) {
                            param.result = drawable
                        }
                    }
            )

            hooks.addAll(
                ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawable", java.lang.Integer.TYPE)
                    .beforeThat { param ->
                        val resId = param.args.getOrNull(0) as? Int ?: return@beforeThat
                        val drawable = getDrawable(resId, mContext.theme)
                        if (drawable != null) {
                            param.result = drawable
                        }
                    }
            )

            hooks.addAll(
                ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawableForDensity", java.lang.Integer.TYPE, java.lang.Integer.TYPE, Resources.Theme::class.java)
                    .beforeThat { param ->
                        val resId = param.args.getOrNull(0) as? Int ?: return@beforeThat
                        val drawable = getDrawable(resId, param.args.getOrNull(2) as? Resources.Theme)
                        if (drawable != null) {
                            param.result = drawable
                        }
                    }
            )

            hooks.addAll(
                ReflectedMethod.ofExactData(ReflectedClass.of(Resources::class.java), "getDrawableForDensity", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                    .beforeThat { param ->
                        val resId = param.args.getOrNull(0) as? Int ?: return@beforeThat
                        val drawable = getDrawable(resId, mContext.theme)
                        if (drawable != null) {
                            param.result = drawable
                        }
                    }
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
        thread {
            drawableMapping = getIDMapping(TYPE_DRAWABLE, "drawable")
            if (drawableMapping.isEmpty()) {
                unhookAll()
            } else {
                hookAll()
            }
            if (shallRefresh) {
                refreshUI()
            }
        }
    }

    private fun refreshUI() {
        XPLauncher.enqueueProxyCommand { proxy ->
            proxy.runRootCommand("cmd overlay disable com.google.android.systemui.gxoverlay; cmd overlay enable com.google.android.systemui.gxoverlay")
        }
    }

    private fun getIDMapping(prefType: Int, type: String): IDMapping {
        val prefMapping = Mapping.loadMapping(Xprefs, prefType)
        val idMapping = IDMapping()

        for (key in prefMapping.keys) {
            try {
                val overlayIDName = prefMapping[key] ?: continue
                val mappingID = getMappingID(key, type)
                val replacementID = getReplacementID(overlayIDName, type)

                if (replacementID == 0 || mappingID == 0) {
                    continue
                }

                idMapping.put(mappingID, OverlayID(overlayIDName.packageName, replacementID))
            } catch (_: Throwable) {}
        }
        return idMapping
    }

    @SuppressLint("DiscouragedApi")
    private fun getReplacementID(overlayIDName: OverlayIDName, type: String): Int {
        return mPackageManager.getResourcesForApplication(overlayIDName.packageName)
            .getIdentifier(overlayIDName.resName, type, overlayIDName.packageName)
    }

    @SuppressLint("DiscouragedApi")
    private fun getMappingID(key: String, type: String): Int {
        val keyParts = key.split(":")
        val resName = keyParts.last()

        var sourcePackage = if (keyParts.size > 1) keyParts[0] else mContext.packageName
        if (sourcePackage == "module") {
            sourcePackage = BuildConfig.APPLICATION_ID
        }

        return mContext.resources.getIdentifier(resName, type, sourcePackage)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        mPackageLoaded = true
        hookAll()
    }

    private fun getDrawable(id: Int, theme: Resources.Theme?): Drawable? {
        if (drawableMapping.containsKey(id)) {
            val overlayID = drawableMapping[id] ?: return null
            val res = mPackageManager.getResourcesForApplication(overlayID.packageName)
            return Drawable.createFromXml(res, res.getXml(overlayID.resID), theme)
        }
        return null
    }
}
