package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getFloatField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setAdditionalInstanceField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.Constants.AI_METHOD_MLKIT
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@SystemUIModPack
class DepthWallpaper(context: Context) : XposedModPack(context) {

    private var scrimController: Any? = null
    private var lockScreenSubject: FrameLayout? = null
    private var subjectDimmingOverlay: Drawable? = null
    private var wallpaperBackground: FrameLayout? = null
    private var wallpaperBitmapContainer: FrameLayout? = null
    private var wallpaperDimmingOverlay: FrameLayout? = null
    private var layersCreated: Boolean = false

    companion object {
        private var lockScreenSubjectCacheValid: Boolean = false
        private var dWallpaperEnabled: Boolean = false
        private var dwOpacity: Int = 192
        private var dwOnAOD: Boolean = false
        private var segmentorAI: Int = AI_METHOD_MLKIT
    }

    override fun onPreferenceUpdated(vararg key: String) {
        dWallpaperEnabled = Xprefs.getBoolean("DWallpaperEnabled", false)
        dwOpacity = Xprefs.getSliderInt("DWOpacity", 192)
        dwOnAOD = Xprefs.getBoolean("DWonAOD", false)
        segmentorAI = Xprefs.getString("SegmentorAI", AI_METHOD_MLKIT.toString())?.toIntOrNull() ?: AI_METHOD_MLKIT
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val canvasEngineClass = ReflectedClass.of("com.android.systemui.wallpapers.ImageWallpaper\$CanvasEngine")
        val scrimViewClass = ReflectedClass.of("com.android.systemui.scrim.ScrimView")
        val notificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController")
        val aodBurnInSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection")

        aodBurnInSectionClass
            .after("addViews")
            .run { runParam ->
                val entryV = runParam.args[0] as? View ?: return@run

                if (!dWallpaperEnabled) return@run

                entryV.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    @SuppressLint("DiscouragedApi")
                    override fun onViewAttachedToWindow(v: View) {
                        viewAttached(entryV)
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })

                if (entryV.isAttachedToWindow) {
                    viewAttached(entryV)
                }
            }

        scrimViewClass
            .before("setViewAlpha")
            .run { runParam ->
                if (!layersCreated) return@run

                setDepthWallpaper()
                val controller = scrimController ?: return@run
                val state = getObjectField<Any>(controller, "mState").toString()

                if (dwOnAOD && state != "KEYGUARD") {
                    lockScreenSubject?.post { lockScreenSubject?.alpha = dwOpacity.toFloat() }
                } else if (getObjectField<Any>(controller, "mNotificationsScrim") == runParam.thisObject) {
                    val mScrimBehindAlphaKeyguard = getFloatField(controller, "mScrimBehindAlphaKeyguard")
                    var notificationAlpha = runParam.args.getOrNull(0) as? Float ?: return@run

                    if (notificationAlpha < mScrimBehindAlphaKeyguard) {
                        notificationAlpha = 0f
                    }

                    val subjectAlpha = if (notificationAlpha > mScrimBehindAlphaKeyguard) {
                        (1f - notificationAlpha) / (1f - mScrimBehindAlphaKeyguard)
                    } else {
                        1f
                    }

                    lockScreenSubject?.post { lockScreenSubject?.alpha = subjectAlpha }
                }
            }

        canvasEngineClass
            .after("onSurfaceDestroyed")
            .run { runParam ->
                if (dWallpaperEnabled && isLockScreenWallpaper(runParam.thisObject)) {
                    invalidateLSWSC()
                }
            }

        canvasEngineClass
            .after("onCreate")
            .run { runParam ->
                val wallpaperManager = getObjectField<Any>(runParam.thisObject, "mWallpaperManager")
                if (wallpaperManager != null) {
                    val info = callMethod<Any?>(wallpaperManager, "getWallpaperInfo", WallpaperManager.FLAG_LOCK)
                    if (info != null) {
                        invalidateLSWSC()
                    }
                }
            }

        var wallpaperProcessorThread: Thread? = null

        canvasEngineClass
            .after("drawFrameOnCanvas")
            .run { runParam ->
                wallpaperProcessorThread?.interrupt()

                if (dWallpaperEnabled && isLockScreenWallpaper(runParam.thisObject)) {
                    wallpaperProcessorThread = thread(start = true) {
                        val original = runParam.args[0] as? Bitmap ?: return@thread
                        val wallpaperBitmap = Bitmap.createBitmap(original)

                        val cacheIsValid = assertCache(wallpaperBitmap)

                        val displayContext = callMethod<Context>(runParam.thisObject, "getDisplayContext")
                        val displayBounds = displayContext.getSystemService(WindowManager::class.java)
                            .currentWindowMetrics
                            .bounds

                        val ratioW = 1f * displayBounds.width() / wallpaperBitmap.width
                        val ratioH = 1f * displayBounds.height() / wallpaperBitmap.height

                        val maxRatio = maxOf(ratioH, ratioW)
                        val desiredHeight = (maxRatio * wallpaperBitmap.height).roundToInt()
                        val desiredWidth = (maxRatio * wallpaperBitmap.width).roundToInt()

                        val xPixelShift = (desiredWidth - displayBounds.width()) / 2
                        val yPixelShift = (desiredHeight - displayBounds.height()) / 2

                        val scaledWallpaperBitmap = Bitmap.createScaledBitmap(wallpaperBitmap, desiredWidth, desiredHeight, true)
                        val croppedWallpaperBitmap = Bitmap.createBitmap(scaledWallpaperBitmap, xPixelShift, yPixelShift, displayBounds.width(), displayBounds.height())
                        val finalScaledWallpaperBitmap = Bitmap.createBitmap(croppedWallpaperBitmap)

                        if (!layersCreated) {
                            createLayers()
                        }

                        wallpaperBackground?.post {
                            wallpaperBitmapContainer?.background = BitmapDrawable(mContext.resources, finalScaledWallpaperBitmap)
                        }

                        if (!cacheIsValid) {
                            try {
                                val cachePath = Constants.getLockScreenSubjectCachePath(mContext)
                                val subjectBitmap = XPLauncher.getRootProviderProxy().extractSubject(finalScaledWallpaperBitmap, segmentorAI)

                                if (subjectBitmap != null) {
                                    FileOutputStream(cachePath).use { subjectOutputStream ->
                                        subjectBitmap.compress(Bitmap.CompressFormat.PNG, 100, subjectOutputStream)
                                    }

                                    Thread.sleep(500)
                                    setDepthWallpaper()
                                }
                            } catch (_: Throwable) {}
                        }

                        wallpaperProcessorThread = null
                    }
                }
            }

        notificationPanelViewControllerClass
            .afterConstruction()
            .run { runParam ->
                scrimController = getObjectField(runParam.thisObject, "mScrimController")
            }
    }

    private fun viewAttached(entryV: View) {
        ReflectionTools.runDelayedOnMainThread(entryV, 1000) {
            val parent = entryV.parent as? ViewGroup ?: return@runDelayedOnMainThread
            val rootView = parent.rootView.findViewById<ViewGroup>(idOf("keyguard_root_view")) ?: return@runDelayedOnMainThread

            rootView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parentView: View, child: View) {
                    hookClocks(rootView)
                }

                override fun onChildViewRemoved(parentView: View, child: View) {}
            })
            hookClocks(rootView.rootView)

            if (!layersCreated) {
                createLayers()
            }

            (rootView.rootView as? ViewGroup)?.let { rootGroup ->
                wallpaperBackground?.let { reAddView(rootGroup, it, 0) }
            }
            lockScreenSubject?.let { reAddView(rootView, it) }
        }
    }

    private fun hookClocks(rootView: View) {
        val keyguardRoot = rootView.findViewById<ViewGroup>(idOf("keyguard_root_view")) ?: return
        for (i in 0 until keyguardRoot.childCount) {
            val child = keyguardRoot.getChildAt(i)
            val className = child.javaClass.name
            if (className.startsWith("com.android.systemui.clocks") || className.startsWith("com.android.systemui.shared.clocks.view")) {
                child.z = -5f
                setSendBackListeners(child)
            }
        }
    }

    private fun setSendBackListeners(thisView: View) {
        setAdditionalInstanceField(thisView, "DepthHooked", true)
        thisView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.z = -5f
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })

        thisView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.z = -5f
        }
    }

    private fun assertCache(wallpaperBitmap: Bitmap): Boolean {
        var cacheIsValid = false
        try {
            val wallpaperCacheFile = File(Constants.getLockScreenBitmapCachePath(mContext))
            val compressedBitmap = ByteArrayOutputStream()
            wallpaperBitmap.compress(Bitmap.CompressFormat.JPEG, 100, compressedBitmap)

            if (wallpaperCacheFile.exists()) {
                FileInputStream(wallpaperCacheFile).use { cacheStream ->
                    if (cacheStream.readBytes().contentEquals(compressedBitmap.toByteArray())) {
                        cacheIsValid = true
                    }
                }
            }

            if (!cacheIsValid) {
                FileOutputStream(wallpaperCacheFile).use { newCacheStream ->
                    compressedBitmap.writeTo(newCacheStream)
                }
            }
            compressedBitmap.close()
        } catch (_: Throwable) {}

        if (!cacheIsValid) {
            invalidateLSWSC()
        }

        return cacheIsValid
    }

    private fun createLayers() {
        wallpaperBackground = FrameLayout(mContext).apply {
            id = View.generateViewId()
        }
        wallpaperDimmingOverlay = FrameLayout(mContext)
        wallpaperBitmapContainer = FrameLayout(mContext)
        val lpw = FrameLayout.LayoutParams(-1, -1)

        wallpaperDimmingOverlay?.setBackgroundColor(Color.BLACK)
        wallpaperDimmingOverlay?.layoutParams = lpw
        wallpaperBitmapContainer?.layoutParams = lpw

        wallpaperBackground?.addView(wallpaperBitmapContainer)
        wallpaperBackground?.addView(wallpaperDimmingOverlay)
        wallpaperBackground?.layoutParams = lpw

        lockScreenSubject = FrameLayout(mContext).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            id = View.generateViewId()
        }

        layersCreated = true
    }

    private fun isLockScreenWallpaper(canvasEngine: Any): Boolean {
        return (getWallpaperFlag(canvasEngine) and WallpaperManager.FLAG_LOCK) == WallpaperManager.FLAG_LOCK
    }

    private fun setDepthWallpaper() {
        val controller = scrimController ?: return
        val state = getObjectField<Any>(controller, "mState").toString()
        val showSubject = dWallpaperEnabled && (state == "KEYGUARD" || (dwOnAOD && (state == "AOD" || state == "PULSING")))

        if (showSubject) {
            if (!lockScreenSubjectCacheValid && isSubjectCacheAvailable()) {
                try {
                    FileInputStream(Constants.getLockScreenSubjectCachePath(mContext)).use { inputStream ->
                        val bitmapDrawable = Drawable.createFromStream(inputStream, "") ?: return@use
                        val overlay = bitmapDrawable.constantState?.newDrawable()?.mutate() ?: return@use
                        overlay.setTint(Color.BLACK)
                        subjectDimmingOverlay = overlay

                        lockScreenSubject?.background = LayerDrawable(arrayOf(bitmapDrawable, overlay))
                        lockScreenSubjectCacheValid = true
                    }
                } catch (_: Throwable) {}
            }

            if (lockScreenSubjectCacheValid) {
                lockScreenSubject?.background?.alpha = dwOpacity

                val alphaKeyguard = getFloatField(controller, "mScrimBehindAlphaKeyguard")
                if (state != "KEYGUARD") {
                    subjectDimmingOverlay?.alpha = 192
                } else {
                    subjectDimmingOverlay?.alpha = (alphaKeyguard * 240).roundToInt()
                    wallpaperDimmingOverlay?.alpha = alphaKeyguard
                }

                wallpaperBackground?.visibility = VISIBLE
                lockScreenSubject?.visibility = VISIBLE
            }
        } else if (layersCreated) {
            lockScreenSubject?.visibility = GONE
            if (state == "UNLOCKED") {
                wallpaperBackground?.visibility = GONE
            }
        }
        wallpaperBackground?.z = -2f
        lockScreenSubject?.z = -0.5f
    }

    private fun isSubjectCacheAvailable(): Boolean {
        return try {
            File(Constants.getLockScreenSubjectCachePath(mContext)).length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun getWallpaperFlag(canvasEngine: Any): Int {
        return callMethod(canvasEngine, "getWallpaperFlags")
    }

    private fun invalidateLSWSC() {
        lockScreenSubjectCacheValid = false
        if (layersCreated) {
            lockScreenSubject?.post {
                lockScreenSubject?.visibility = GONE
                lockScreenSubject?.background = null
                wallpaperBackground?.visibility = GONE
                wallpaperBitmapContainer?.background = null
            }
        }
        try {
            File(Constants.getLockScreenSubjectCachePath(mContext)).delete()
        } catch (_: Throwable) {}
    }
}
