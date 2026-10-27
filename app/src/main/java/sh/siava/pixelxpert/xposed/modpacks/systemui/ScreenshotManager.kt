package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.media.MediaPlayer
import android.os.UserManager
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ChildProcessModPack
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Adjusts the screenshot pipeline inside SystemUI, running in the dedicated
 * `screenshot` child process as well as the main one.
 *
 * Two preferences drive it: "disable screenshot sound" silences the shutter by
 * neutering `MediaPlayer.start()` outright and by swapping the coroutine
 * dispatcher the sound controller(s) run on for an executor that never runs
 * anything - covering both the QPR1 sound-controller constructor and the QPR2
 * executor path - while "screenshot chord insecure" lets the chord capture
 * secure content by forcing the `CaptureArgs` secure flags and short-circuiting
 * the managed-profile / user-info policy checks that would otherwise refuse.
 */
@SystemUIModPack
@ChildProcessModPack(processNameContains = "screenshot")
class ScreenshotManager(context: Context) : XposedModPack(context) {

	companion object {
		private var disableScreenshotSound = false
	}

	private var ScreenshotChordInsecure = false

	override fun onPreferenceUpdated(vararg key: String?) {
		disableScreenshotSound = Xprefs.getBoolean("disableScreenshotSound", false)
		ScreenshotChordInsecure = Xprefs.getBoolean("ScreenshotChordInsecure", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val NewCaptureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCaptureInternal.CaptureArgs") // A16QPR2
		val CaptureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCapture.CaptureArgs") // A16QPR1
		val TakeScreenshotExecutorImplClass = ReflectedClass.of("com.android.systemui.screenshot.TakeScreenshotExecutorImpl")
		val ScreenshotSoundControllerImplClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.screenshot.ScreenshotSoundControllerImpl",
		)

		ReflectedClass.of(UserManager::class.java)
			.before("getUserInfo")
			.run { param -> param.args[0] = 0 }

		val ScreenshotPolicyImplClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotPolicyImpl")

		if (ScreenshotPolicyImplClass.clazz != null) {
			ScreenshotPolicyImplClass
				.before(Pattern.compile(".*isManagedProfile.*"))
				.run { param ->
					if (ScreenshotChordInsecure) {
						param.setResult(false)
					}
				}
		}

		NewCaptureArgsClass
			.afterConstruction()
			.run { param ->
				if (ScreenshotChordInsecure) {
					setObjectField(param.thisObject, "mSecureContentPolicy", 1) // No source available. but apparently 1 works.
				}
			}

		CaptureArgsClass
			.afterConstruction()
			.run { param ->
				if (ScreenshotChordInsecure) {
					setObjectField(param.thisObject, "mCaptureSecureLayers", true)
				}
			}

		// 17 - much easier approach: killing mediaplayer totally
		ReflectedClass.of(MediaPlayer::class.java)
			.before("start")
			.run { param ->
				if (disableScreenshotSound) {
					param.setResult(null)
				}
			}

		// 16 qpr2
		TakeScreenshotExecutorImplClass
			.after("getScreenshotController")
			.run { param ->
				if (disableScreenshotSound) {
					setObjectField(
						getObjectField(param.getResult<Any?>(), "screenshotSoundController"),
						"bgDispatcher",
						ReflectedClass.of(
							"kotlinx.coroutines.ExecutorCoroutineDispatcherImpl",
						).clazz!!.constructors[0].newInstance(NoExecutor()),
					)
				}
			}

		// 16 qpr1
		ScreenshotSoundControllerImplClass
			.beforeConstruction()
			.run { param ->
				if (disableScreenshotSound) {
					for (i in param.args.indices) {
						// Locale.getDefault() keeps this on the default locale's rules
						if (param.args[i]!!.javaClass.name.lowercase(Locale.getDefault()).contains("dispatcher")) {
							param.args[i] =
								ReflectedClass.of(
									"kotlinx.coroutines.ExecutorCoroutineDispatcherImpl",
								).clazz!!.constructors[0].newInstance(NoExecutor())
						}
					}
				}
			}
	}

	// Seems like an executor, but doesn't act! perfect thing
	private class NoExecutor : ExecutorService {
		override fun shutdown() {
		}

		override fun shutdownNow(): MutableList<Runnable>? = null

		override fun isShutdown(): Boolean = false

		override fun isTerminated(): Boolean = false

		override fun awaitTermination(l: Long, timeUnit: TimeUnit): Boolean = false

		override fun <T> submit(callable: Callable<T>): Future<T>? = null

		override fun <T> submit(runnable: Runnable, t: T): Future<T>? = null

		override fun submit(runnable: Runnable): Future<*>? = null

		override fun <T> invokeAll(collection: MutableCollection<out Callable<T>>): MutableList<Future<T>>? = null

		override fun <T> invokeAll(
			collection: MutableCollection<out Callable<T>>,
			l: Long,
			timeUnit: TimeUnit,
		): MutableList<Future<T>>? = null

		override fun <T> invokeAny(collection: MutableCollection<out Callable<T>>): T? = null

		override fun <T> invokeAny(collection: MutableCollection<out Callable<T>>, l: Long, timeUnit: TimeUnit): T? = null

		override fun execute(runnable: Runnable) {
		}
	}
}
