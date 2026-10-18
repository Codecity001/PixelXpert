package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.concurrent.thread

@SystemUIModPack
class StatusbarGestures(context: Context) : XposedModPack(context) {

    private var oneFingerPullupEnabled = false
    private var notificationPanelViewController: Any? = null
    private var gestureDetector: GestureDetector? = null
    private var statusbarLongpressAppSwitch = false
    private var downEvent: MotionEvent? = null
    private var shadeInteractor: Any? = null

    companion object {
        private const val PULLDOWN_SIDE_RIGHT = 1
        private const val PULLDOWN_SIDE_LEFT = 2
        private const val STATUSBAR_MODE_SHADE = 0
        private const val STATUSBAR_MODE_KEYGUARD = 1
        private const val STATUSBAR_MODE_SHADE_LOCKED = 2

        private var pullDownSide = PULLDOWN_SIDE_RIGHT
        private var oneFingerPulldownEnabled = false
        private var statusbarPortion = 0.25f

        @SuppressLint("StaticFieldLeak")
        private var instance: StatusbarGestures? = null

        @JvmStatic
        fun collapseQSPanel() {
            instance?.collapseQS()
        }
    }

    init {
        instance = this
    }

    override fun onPreferenceUpdated(vararg key: String) {
        oneFingerPulldownEnabled = Xprefs.getBoolean("QSPullodwnEnabled", false)
        oneFingerPullupEnabled = oneFingerPulldownEnabled && Xprefs.getBoolean("oneFingerPullupEnabled", false)
        statusbarPortion = Xprefs.getSliderInt("QSPulldownPercent", 25) / 100f
        pullDownSide = Xprefs.getString("QSPulldownSide", "1")?.toIntOrNull() ?: PULLDOWN_SIDE_RIGHT
        statusbarLongpressAppSwitch = Xprefs.getBoolean("StatusbarLongpressAppSwitch", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val notificationPanelViewControllerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationPanelViewController")
        val phoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")

        val shadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl")
        val shadeInteractorImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorImpl")
        val shadeSurfaceImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ShadeSurfaceImpl")

        shadeSurfaceImplClass?.before("onStatusBarLongPress")?.run(this::onStatusBarLongPress)

        shadeInteractorSceneContainerImplClass?.afterConstruction()?.run { runParam ->
            shadeInteractor = runParam.thisObject
        }
        shadeInteractorImplClass?.afterConstruction()?.run { runParam ->
            shadeInteractor = runParam.thisObject
        }

        gestureDetector = GestureDetector(mContext, getPullDownLPListener())

        phoneStatusBarViewClass
            .after("dispatchTouchEvent")
            .run { runParam ->
                if (!oneFingerPulldownEnabled) return@run

                val event = (runParam.args.getOrNull(0) as? MotionEvent)
                    ?: (runParam.args.getOrNull(1) as? MotionEvent)
                    ?: return@run

                val clone = MotionEvent.obtain(event)
                if (clone.actionMasked == MotionEvent.ACTION_CANCEL) {
                    clone.action = MotionEvent.ACTION_UP
                }
                gestureDetector?.onTouchEvent(clone)
                clone.recycle()
            }

        val pullUpDetector = GestureDetector(mContext, getPullUpListener())
        val lastPullupTouchTime = longArrayOf(0)

        notificationPanelViewControllerClass?.before("onStatusBarLongPress")?.run(this::onStatusBarLongPress)

        notificationPanelViewControllerClass?.afterConstruction()?.run { runParam ->
            notificationPanelViewController = runParam.thisObject
            val touchHandler = getObjectField<Any>(runParam.thisObject, "mTouchHandler") ?: return@run
            ReflectedClass.of(touchHandler.javaClass)
                .before("onTouchEvent")
                .run { param2 ->
                    val motionEvent = param2.args.getOrNull(0) as? MotionEvent ?: return@run

                    if (oneFingerPullupEnabled && !isKeyguard(notificationPanelViewController)) {
                        when (motionEvent.actionMasked) {
                            MotionEvent.ACTION_OUTSIDE -> return@run

                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                if (SystemClock.uptimeMillis() - lastPullupTouchTime[0] > 1000) {
                                    motionEvent.action = MotionEvent.ACTION_DOWN
                                    lastPullupTouchTime[0] = SystemClock.uptimeMillis()
                                    downEvent?.recycle()
                                    downEvent = MotionEvent.obtain(motionEvent)
                                    return@run
                                }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                lastPullupTouchTime[0] = 0
                        }
                        pullUpDetector.onTouchEvent(motionEvent)
                    }
                }
        }
    }

    private fun onStatusBarLongPress(param: HookHelper.RunParam) {
        if (statusbarLongpressAppSwitch) {
            sendAppSwitchBroadcast()
            param.result = null
        }
    }

    private fun isValidFling(e1: MotionEvent?, e2: MotionEvent, velocityY: Float, speedFactor: Float, heightFactor: Float): Boolean {
        if (e1 == null) return false
        val displayBounds = SystemUtils.WindowManager()?.currentWindowMetrics?.bounds ?: return false
        return try {
            ((e2.y - e1.y) / heightFactor) > displayBounds.height() &&
                    isTouchInRegion(e1, displayBounds.width().toFloat()) &&
                    (velocityY / speedFactor > displayBounds.height())
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTouchInRegion(motionEvent: MotionEvent, width: Float): Boolean {
        val x = motionEvent.x
        val region = width * statusbarPortion

        return if (pullDownSide == PULLDOWN_SIDE_RIGHT) {
            width - region < x
        } else {
            x < region
        }
    }

    private fun sendAppSwitchBroadcast() {
        thread {
            mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())
        }
    }

    private fun getPullDownLPListener(): GestureDetector.OnGestureListener {
        return object : GestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isStatusbarClosed() && isValidFling(e1, e2, velocityY, 0.15f, 0.01f)) {
                    try {
                        val controller = notificationPanelViewController
                        if (controller != null && hasMethod(controller.javaClass, "expandToQs")) {
                            callMethod<Any>(controller, "expandToQs")
                        } else {
                            shadeInteractor?.let { callMethod<Any>(it, "expandQuickSettingsShade", "asdf", null) }
                        }
                    } catch (_: Throwable) {}
                    return true
                }
                return false
            }
        }
    }

    private fun isKeyguard(controller: Any?): Boolean {
        if (controller == null) return false
        return try {
            STATUSBAR_MODE_KEYGUARD == getObjectField<Int>(controller, "mBarState")
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasMethod(clazz: Class<*>, methodName: String): Boolean {
        return try {
            clazz.methods.any { it.name == methodName }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isStatusbarClosed(): Boolean {
        var isShade = false
        val controller = notificationPanelViewController
        if (controller != null) {
            isShade = try {
                STATUSBAR_MODE_SHADE == getObjectField<Int>(controller, "mBarState")
            } catch (_: Throwable) {
                true
            }
        }
        return isShade || shadeInteractor != null
    }

    private fun getPullUpListener(): GestureDetector.OnGestureListener {
        return object : GestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (isValidFling(downEvent, e2, velocityY, -0.15f, -0.06f)) {
                    collapseQS()
                    return true
                }
                return false
            }
        }
    }

    private fun collapseQS() {
        val controller = notificationPanelViewController ?: return
        try {
            callMethod<Any>(controller, "collapse", true, 1f)
        } catch (_: Throwable) {
            try {
                callMethod<Any>(controller, "collapse", 1f, true)
            } catch (_: Throwable) {}
        }
    }

    private open class GestureListener : GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean = false
        override fun onShowPress(e: MotionEvent) {}
        override fun onSingleTapUp(e: MotionEvent): Boolean = false
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
        override fun onLongPress(e: MotionEvent) {}
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false
    }
}
