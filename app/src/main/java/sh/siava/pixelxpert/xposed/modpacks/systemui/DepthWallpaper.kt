package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getFloatField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import io.github.libxposed.api.XposedModuleInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Arrays
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.Constants.AI_METHOD_MLKIT
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView

/**
 * Builds and drives the lock-screen depth wallpaper: two stacked layers over
 * SystemUI's keyguard - the cropped wallpaper bitmap behind, and a segmented
 * "subject" cutout (plus its dimming veil) in front - so the clocks and key
 * guard UI appear to sit between the wallpaper and whatever is in the photo.
 * Every lock-screen frame is cropped to the display bounds on a dedicated
 * worker thread, compared byte-for-byte against the on-disk bitmap cache, and
 * on a miss is handed to the module app's root proxy for AI subject
 * segmentation; the resulting PNG lands in the subject cache and is re-read
 * from disk whenever the scrim reports KEYGUARD (or AOD/PULSING when
 * `DWonAOD` is allowed). The hooks on `ImageWallpaper$CanvasEngine`,
 * `ScrimView`, `AodBurnInSection` and `NotificationPanelViewController` exist
 * to keep those layers attached to the keyguard view tree, alpha-driven from
 * the scrim's own state machine, and torn down whenever the wallpaper goes
 * away - without them the layers would either never appear or outlive the
 * surface they describe.
 *
 * @noinspection RedundantThrows, SameParameterValue
 */
@SystemUIModPack
class DepthWallpaper(context: Context) : XposedModPack(context) {

	companion object {
		private var lockScreenSubjectCacheValid = false
		private var DWallpaperEnabled = false
		private var DWOpacity = 192
		private var DWonAOD = false
		private var SegmentorAI = AI_METHOD_MLKIT
	}

	private var mScrimController: Any? = null
	private var mLockScreenSubject: FrameLayout? = null
	private var mSubjectDimmingOverlay: Drawable? = null
	private var mWallpaperBackground: FrameLayout? = null
	private var mWallpaperBitmapContainer: FrameLayout? = null
	private var mWallpaperDimmingOverlay: FrameLayout? = null
	private var mLayersCreated = false

	override fun onPreferenceUpdated(vararg key: String?) {
		DWallpaperEnabled = Xprefs.getBoolean("DWallpaperEnabled", false)
		DWOpacity = Xprefs.getSliderInt("DWOpacity", 192)
		DWonAOD = Xprefs.getBoolean("DWonAOD", false)
		SegmentorAI = Integer.parseInt(Xprefs.getString("SegmentorAI", AI_METHOD_MLKIT.toString()))
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val CanvasEngineClass = ReflectedClass.of("com.android.systemui.wallpapers.ImageWallpaper\$CanvasEngine")
		val ScrimViewClass = ReflectedClass.of("com.android.systemui.scrim.ScrimView")
		val NotificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController")
		val AodBurnInSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInSection")

		AodBurnInSectionClass
			.after("addViews")
			.run { param ->
				val entryV = param.args[0] as View

				if (!DWallpaperEnabled) return@run

				entryV.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
					@SuppressLint("DiscouragedApi")
					override fun onViewAttachedToWindow(v: View) {
						viewAttached(entryV)
					}

					override fun onViewDetachedFromWindow(v: View) {
					}
				})

				if (entryV.isAttachedToWindow()) {
					viewAttached(entryV)
				}
			}

		ScrimViewClass
			.before("setViewAlpha")
			.run { param ->
				if (!mLayersCreated) return@run

				setDepthWallpaper()
				//noinspection ConstantValue
				if (DWonAOD &&
					!getObjectField(mScrimController, "mState").toString().equals("KEYGUARD")
				) {
					mLockScreenSubject!!.post { mLockScreenSubject!!.setAlpha(DWOpacity.toFloat()) }
				} else if (getObjectField(mScrimController, "mNotificationsScrim").equals(param.thisObject)) { //instead of using the mScrimName since older ones don't have that field
					val mScrimBehindAlphaKeyguard = getFloatField(mScrimController, "mScrimBehindAlphaKeyguard")

					var notificationAlpha = param.args[0] as Float

					if (notificationAlpha < mScrimBehindAlphaKeyguard) {
						notificationAlpha = 0f
					}

					val subjectAlpha =
						if (notificationAlpha > mScrimBehindAlphaKeyguard) {
							(1f - notificationAlpha) / (1f - mScrimBehindAlphaKeyguard)
						} else {
							1f
						}

					mLockScreenSubject!!.post { mLockScreenSubject!!.setAlpha(subjectAlpha) }
				}
			}

		CanvasEngineClass
			.after("onSurfaceDestroyed")
			.run { param ->
				if (DWallpaperEnabled && isLockScreenWallpaper(param.thisObject)) {
					invalidateLSWSC()
				}
			}

		CanvasEngineClass
			.after("onCreate")
			.run { param ->
				if (callMethod(
						getObjectField(param.thisObject, "mWallpaperManager"),
						"getWallpaperInfo",
						WallpaperManager.FLAG_LOCK,
					) != null
				) { //it's live wallpaper. we can't use that
					invalidateLSWSC()
				}
			}

		val wallpaperProcessorThread = arrayOfNulls<Thread>(1)

		CanvasEngineClass
			.after("drawFrameOnCanvas")
			.run { param ->
				if (wallpaperProcessorThread[0] != null) {
					wallpaperProcessorThread[0]!!.interrupt()
				}

				if (DWallpaperEnabled && isLockScreenWallpaper(param.thisObject)) {
					wallpaperProcessorThread[0] = Thread {
						val wallpaperBitmap = Bitmap.createBitmap(param.args[0] as Bitmap)

						val cacheIsValid = assertCache(wallpaperBitmap)

						val displayBounds: Rect = (callMethod(param.thisObject, "getDisplayContext") as Context)
							.getSystemService(WindowManager::class.java)!!
							.currentWindowMetrics
							.bounds

						val ratioW = 1f * displayBounds.width() / wallpaperBitmap.getWidth()
						val ratioH = 1f * displayBounds.height() / wallpaperBitmap.getHeight()

						val desiredHeight = Math.round(Math.max(ratioH, ratioW) * wallpaperBitmap.getHeight())
						val desiredWidth = Math.round(Math.max(ratioH, ratioW) * wallpaperBitmap.getWidth())

						val xPixelShift = (desiredWidth - displayBounds.width()) / 2
						val yPixelShift = (desiredHeight - displayBounds.height()) / 2

						var scaledWallpaperBitmap = Bitmap.createScaledBitmap(wallpaperBitmap, desiredWidth, desiredHeight, true)

						//crop to display bounds
						scaledWallpaperBitmap = Bitmap.createBitmap(scaledWallpaperBitmap, xPixelShift, yPixelShift, displayBounds.width(), displayBounds.height())
						val finalScaledWallpaperBitmap = Bitmap.createBitmap(scaledWallpaperBitmap)

						if (!mLayersCreated) {
							createLayers()
						}

						mWallpaperBackground!!.post { mWallpaperBitmapContainer!!.setBackground(BitmapDrawable(mContext.getResources(), finalScaledWallpaperBitmap)) }

						if (!cacheIsValid) {
							try {
								val cachePath = Constants.getLockScreenSubjectCachePath(mContext)

								val subjectBitmap = XPLauncher.getRootProviderProxy()?.extractSubject(finalScaledWallpaperBitmap, SegmentorAI)

								if (subjectBitmap != null) {
									val subjectOutputStream = FileOutputStream(cachePath)
									subjectBitmap.compress(Bitmap.CompressFormat.PNG, 100, subjectOutputStream)
									subjectOutputStream.close()

									Thread.sleep(500) //letting the filesystem settle down

									setDepthWallpaper()
								}
							} catch (ignored: Throwable) {
							}
						}

						wallpaperProcessorThread[0] = null
					}
					wallpaperProcessorThread[0]!!.start()
				}
			}

		NotificationPanelViewControllerClass
			.afterConstruction()
			.run { param -> mScrimController = getObjectField(param.thisObject, "mScrimController") }
	}

	private fun viewAttached(entryV: View) {
		ReflectionTools.runDelayedOnMainThread(entryV, 1000) {
			val rootView: ViewGroup = (entryV.parent as ViewGroup).rootView.findViewById(SystemUtils.idOf("keyguard_root_view"))

			rootView.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
				override fun onChildViewAdded(parent: View, child: View) {
					hookClocks(rootView)
				}

				override fun onChildViewRemoved(parent: View, child: View) {
				}
			})
			hookClocks(rootView.rootView)

			if (!mLayersCreated) {
				createLayers()
			}

			reAddView(rootView.rootView as ViewGroup, mWallpaperBackground, 0)
			reAddView(rootView, mLockScreenSubject)
		}
	}

	private fun hookClocks(rootView: View) {
		val keyguardRoot: ViewGroup = rootView.findViewById(SystemUtils.idOf("keyguard_root_view"))
		for (i in 0 until keyguardRoot.childCount) {
			val child = keyguardRoot.getChildAt(i)
			if (child.javaClass.name.startsWith("com.android.systemui.clocks") || child.javaClass.name.startsWith("com.android.systemui.shared.clocks.view")) {
				child.setZ(-5f)
				setSendBackListeners(child)
			}
		}
	}

	private fun setSendBackListeners(thisView: View) {
		setAdditionalInstanceField(thisView, "DepthHooked", true)
		thisView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) {
				v.setZ(-5f)
			}

			override fun onViewDetachedFromWindow(v: View) {
			}
		})

		thisView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			v.setZ(-5f)
		}
	}

	private fun assertCache(wallpaperBitmap: Bitmap): Boolean {
		var cacheIsValid = false
		try {
			val wallpaperCacheFile = File(Constants.getLockScreenBitmapCachePath(mContext))

			val compressedBitmap = ByteArrayOutputStream()
			wallpaperBitmap.compress(Bitmap.CompressFormat.JPEG, 100, compressedBitmap)
			if (wallpaperCacheFile.exists()) {
				val cacheStream = FileInputStream(wallpaperCacheFile)

				if (Arrays.equals(cacheStream.readAllBytes(), compressedBitmap.toByteArray())) {
					cacheIsValid = true
				}
				cacheStream.close()
			}

			if (!cacheIsValid) {
				val newCacheStream = FileOutputStream(wallpaperCacheFile)
				compressedBitmap.writeTo(newCacheStream)
				newCacheStream.close()
			}
			compressedBitmap.close()
		} catch (ignored: Throwable) {
		}

		if (!cacheIsValid) {
			invalidateLSWSC()
		}

		return cacheIsValid
	}

	private fun createLayers() {
		mWallpaperBackground = FrameLayout(mContext)
		mWallpaperBackground!!.setId(View.generateViewId())
		mWallpaperDimmingOverlay = FrameLayout(mContext)
		mWallpaperBitmapContainer = FrameLayout(mContext)
		val lpw = FrameLayout.LayoutParams(-1, -1)

		mWallpaperDimmingOverlay!!.setBackgroundColor(Color.BLACK)
		mWallpaperDimmingOverlay!!.setLayoutParams(lpw)
		mWallpaperBitmapContainer!!.setLayoutParams(lpw)

		mWallpaperBackground!!.addView(mWallpaperBitmapContainer)
		mWallpaperBackground!!.addView(mWallpaperDimmingOverlay)
		mWallpaperBackground!!.setLayoutParams(lpw)

		mLockScreenSubject = FrameLayout(mContext)
		val lp = FrameLayout.LayoutParams(-1, -1)
		mLockScreenSubject!!.setLayoutParams(lp)

		mLockScreenSubject!!.setId(View.generateViewId()) //a fake ID so that it can be added to constrained layout

		mLayersCreated = true
	}

	private fun isLockScreenWallpaper(canvasEngine: Any?): Boolean {
		return (getWallpaperFlag(canvasEngine) and WallpaperManager.FLAG_LOCK) == WallpaperManager.FLAG_LOCK
	}

	private fun setDepthWallpaper() {
		val state = getObjectField(mScrimController, "mState").toString()
		val showSubject = DWallpaperEnabled
			&&
			(
				state.equals("KEYGUARD")
					||
					(DWonAOD
						&&
						(state.equals("AOD") || state.equals("PULSING")))
				)

		if (showSubject) {
			if (!lockScreenSubjectCacheValid && isSubjectCacheAvailable()) {
				try {
					FileInputStream(Constants.getLockScreenSubjectCachePath(mContext)).use { inputStream ->
						val bitmapDrawable = BitmapDrawable.createFromStream(inputStream, "")!!

						mSubjectDimmingOverlay = bitmapDrawable.constantState!!.newDrawable().mutate()
						mSubjectDimmingOverlay!!.setTint(Color.BLACK)

						mLockScreenSubject!!.setBackground(LayerDrawable(arrayOf(bitmapDrawable, mSubjectDimmingOverlay!!)))
						lockScreenSubjectCacheValid = true
					}
				} catch (ignored: Throwable) {
				}
			}

			if (lockScreenSubjectCacheValid) {
				mLockScreenSubject!!.getBackground()!!.setAlpha(DWOpacity)

				if (!state.equals("KEYGUARD")) { //AOD
					mSubjectDimmingOverlay!!.setAlpha(192 /*Math.round(192 * (DWOpacity / 255f))*/)
				} else {
					//this is the dimmed wallpaper coverage
					mSubjectDimmingOverlay!!.setAlpha(Math.round(getFloatField(mScrimController, "mScrimBehindAlphaKeyguard") * 240)) //A tad bit lower than max. show it a bit lighter than other stuff
					mWallpaperDimmingOverlay!!.setAlpha(getFloatField(mScrimController, "mScrimBehindAlphaKeyguard"))
				}

				mWallpaperBackground!!.setVisibility(View.VISIBLE)
				mLockScreenSubject!!.setVisibility(View.VISIBLE)
			}
		} else if (mLayersCreated) {
			mLockScreenSubject!!.setVisibility(View.GONE)

			if (state.equals("UNLOCKED")) {
				mWallpaperBackground!!.setVisibility(View.GONE)
			}
		}
		mWallpaperBackground!!.setZ(-2f)
		mLockScreenSubject!!.setZ(-.5f)
	}

	private fun isSubjectCacheAvailable(): Boolean {
		try {
			return File(Constants.getLockScreenSubjectCachePath(mContext)).length() > 0
		} catch (e: Exception) {
			return false
		}
	}

	private fun getWallpaperFlag(canvasEngine: Any?): Int {
		return callMethod(canvasEngine, "getWallpaperFlags") as Int
	}

	private fun invalidateLSWSC() //invalidate lock screen wallpaper subject cache
	{
		lockScreenSubjectCacheValid = false
		if (mLayersCreated) {
			mLockScreenSubject!!.post {
				mLockScreenSubject!!.setVisibility(View.GONE)
				mLockScreenSubject!!.setBackground(null)
				mWallpaperBackground!!.setVisibility(View.GONE)
				mWallpaperBitmapContainer!!.setBackground(null)
			}
		}
		try {
			//noinspection ResultOfMethodCallIgnored
			File(Constants.getLockScreenSubjectCachePath(mContext)).delete()
		} catch (ignored: Throwable) {
		}
	}
}
