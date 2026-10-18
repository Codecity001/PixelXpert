package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.HapticFeedbackConstants
import android.view.View
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.HighBrightnessFlashlightTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.HighBrightnessTorchController
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.min

@SystemUIModPack
class HighBrightnessFlashlightTile(context: Context) : XposedModPack(context) {

    private var mTile: Any? = null

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val customTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
            val qsFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")
            val qsTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")

            qsFactoryImplClass
                .after("createTile")
                .run { hookParam ->
                    val arg = hookParam.args.getOrNull(0) as? String
                    if (arg != null && arg.contains(HighBrightnessFlashlightTileService::class.java.simpleName)) {
                        val result = hookParam.result
                        if (result != null) {
                            mTile = result
                            updateTile()
                        }
                    }
                }

            customTileClass
                .before("handleClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val arg = hookParam.args.getOrNull(0)
                        handleTileClick(arg)
                        hookParam.result = null
                    }
                }

            qsTileImplClass
                .before("handleLongClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val arg = hookParam.args.getOrNull(0)
                        handleTileLongClick(arg)
                        hookParam.result = null
                    }
                }

            customTileClass
                .after("handleUpdateState")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val state = hookParam.args.getOrNull(0) ?: return@run

                        val controller = HighBrightnessTorchController.getInstance()
                        val isSupported = controller?.isSupported == true
                        val isOn = controller?.isOn == true

                        try {
                            setObjectField(state, "value", isOn)
                        } catch (ignored: Throwable) {
                        }

                        if (!isSupported) {
                            setObjectField(state, "state", Tile.STATE_UNAVAILABLE)
                            setObjectField(state, "secondaryLabel", "Not Supported")
                        } else {
                            setObjectField(state, "state", if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
                            if (isOn && controller != null) {
                                val level = controller.currentBrightness
                                val max = controller.maxBrightness
                                setObjectField(state, "secondaryLabel", "Level $level/$max")
                            } else {
                                setObjectField(state, "secondaryLabel", null)
                            }
                        }
                    }
                }

            customTileClass
                .before("getLongClickIntent")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        hookParam.result = intent
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun handleTileClick(arg: Any?) {
        triggerHapticFeedback(arg)
        val controller = HighBrightnessTorchController.getInstance() ?: return
        if (!controller.isSupported) return

        if (controller.isOn) {
            val current = controller.currentBrightness
            val max = controller.maxBrightness
            if (current >= max) {
                Xprefs.edit().putInt("high_brightness_flashlight_level", 16).apply()
                controller.closeCamera()
            } else {
                val next = min(current + 16, max)
                controller.setBrightness(next)
            }
        } else {
            val last = Xprefs.getInt("high_brightness_flashlight_level", 16)
            controller.setBrightness(last)
        }
        updateTile()
    }

    private fun handleTileLongClick(arg: Any?) {
        triggerHapticFeedback(arg)
        val controller = HighBrightnessTorchController.getInstance() ?: return
        if (!controller.isSupported) return

        if (controller.isOn) {
            controller.closeCamera()
        } else {
            controller.setBrightness(controller.maxBrightness)
        }
        updateTile()
    }

    private fun triggerHapticFeedback(arg: Any?) {
        var performed = false
        try {
            var view: View? = null
            if (arg is View) {
                view = arg
            } else if (arg != null) {
                try {
                    val v = callMethod(arg, "asView")
                    if (v is View) {
                        view = v
                    }
                } catch (ignored: Throwable) {
                }
            }

            if (view != null) {
                performed = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (!performed) {
                    performed = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        } catch (ignored: Throwable) {
        }

        if (!performed) {
            try {
                SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun updateTile() {
        val tileObj = mTile ?: return
        try {
            val tile = getObjectField(tileObj, "mTile") as? Tile ?: return

            val controller = HighBrightnessTorchController.getInstance()
            val isSupported = controller?.isSupported == true
            val isOn = controller?.isOn == true

            tile.icon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_flashlight)

            if (!isSupported) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Not Supported"
            } else {
                tile.state = if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (isOn && controller != null) {
                    val level = controller.currentBrightness
                    val max = controller.maxBrightness
                    tile.subtitle = "Level $level/$max"
                } else {
                    tile.subtitle = null
                }
            }

            val label = XPLauncher.moduleResources.getString(R.string.high_brightness_flashlight_tile_title)
            tile.contentDescription = label

            callMethod(tileObj, "refreshState", null as Any?)
        } catch (t: Throwable) {
            sh.siava.pixelxpert.xposed.utils.toolkit.Logger.log("HighBrightnessFlashlightTile: failed to updateTile: $t")
        }
    }
}
