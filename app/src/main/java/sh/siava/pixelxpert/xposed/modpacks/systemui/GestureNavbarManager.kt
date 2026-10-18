package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.graphics.Point
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getFloatField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.roundToInt

@SystemUIModPack
class GestureNavbarManager(context: Context) : XposedModPack(context) {

    private var initialBackX = 0f
    private var edgeBackGestureHandler: Any? = null

    companion object {
        private var backGestureHeightFractionLeft = 1f
        private var backGestureHeightFractionRight = 1f
        private var leftEnabled = true
        private var rightEnabled = true
    }

    override fun onPreferenceUpdated(vararg key: String) {
        leftEnabled = Xprefs.getBoolean("BackFromLeft", true)
        rightEnabled = Xprefs.getBoolean("BackFromRight", true)
        backGestureHeightFractionLeft = Xprefs.getSliderInt("BackLeftHeight", 100) / 100f
        backGestureHeightFractionRight = Xprefs.getSliderInt("BackRightHeight", 100) / 100f
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val edgeBackGestureHandlerClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler")
        val navigationBarEdgePanelClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.NavigationBarEdgePanel")
        val backPanelControllerClass = ReflectedClass.of("com.android.systemui.navigationbar.gestural.BackPanelController")

        // A16 QPR2 - The class doesn't have a visible constructor anymore
        edgeBackGestureHandlerClass?.before("updateIsEnabled")?.run { runParam ->
            edgeBackGestureHandler = runParam.thisObject
        }

        // The navbar builds each display's gesture handler through this method, on the same edge handler
        edgeBackGestureHandlerClass?.before("createDisplayBackGestureHandler")?.run { runParam ->
            edgeBackGestureHandler = runParam.thisObject
        }

        backPanelControllerClass
            .before("onMotionEvent")
            .run { runParam ->
                val ev = runParam.args[0] as? MotionEvent ?: return@run

                if (ev.actionMasked == ACTION_DOWN) {
                    val handler = edgeBackGestureHandler
                    if (handler != null) {
                        val displaySize = getObjectField(handler, "mDisplaySize") as? Point
                        val bottomGestureHeight = getFloatField(handler, "mBottomGestureHeight")

                        if (displaySize != null && notWithinInsets(ev.x, ev.y, displaySize, bottomGestureHeight)) {
                            setObjectField(handler, "mAllowGesture", false)
                            runParam.result = null
                        }
                    }
                }
            }

        navigationBarEdgePanelClass?.before("onMotionEvent")?.run { runParam ->
            val event = runParam.args[0] as? MotionEvent ?: return@run
            if (event.action == ACTION_DOWN) {
                initialBackX = event.x
            }
            val displaySize = getObjectField(runParam.thisObject, "mDisplaySize") as? Point
            if (displaySize != null && notWithinInsets(initialBackX, event.y, displaySize, 0f)) {
                runParam.result = null
            }
        }
    }

    private fun notWithinInsets(x: Float, y: Float, displaySize: Point, bottomGestureHeight: Float): Boolean {
        val isLeftSide = x < (displaySize.x / 3f)
        if ((isLeftSide && !leftEnabled) || (!isLeftSide && !rightEnabled)) {
            return true
        }

        val edgeHeight = if (isLeftSide) {
            (displaySize.y * backGestureHeightFractionLeft).roundToInt()
        } else {
            (displaySize.y * backGestureHeightFractionRight).roundToInt()
        }

        return edgeHeight != 0 && y < (displaySize.y - bottomGestureHeight - edgeHeight)
    }
}
