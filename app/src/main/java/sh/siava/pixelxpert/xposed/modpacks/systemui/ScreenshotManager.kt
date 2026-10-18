package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.media.MediaPlayer
import android.os.UserManager
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ChildProcessModPack
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@SystemUIModPack
@ChildProcessModPack(processNameContains = "screenshot")
class ScreenshotManager(context: Context) : XposedModPack(context) {

    private var screenshotChordInsecure = false

    companion object {
        private var disableScreenshotSound = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        disableScreenshotSound = Xprefs.getBoolean("disableScreenshotSound", false)
        screenshotChordInsecure = Xprefs.getBoolean("ScreenshotChordInsecure", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val newCaptureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCaptureInternal.CaptureArgs")
        val captureArgsClass = ReflectedClass.ofIfPossible("android.window.ScreenCapture.CaptureArgs")
        val takeScreenshotExecutorImplClass = ReflectedClass.of("com.android.systemui.screenshot.TakeScreenshotExecutorImpl")
        val screenshotSoundControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotSoundControllerImpl")

        ReflectedClass.of(UserManager::class.java)
            .before("getUserInfo")
            .run { runParam -> runParam.args[0] = 0 }

        val screenshotPolicyImplClass = ReflectedClass.ofIfPossible("com.android.systemui.screenshot.ScreenshotPolicyImpl")
        screenshotPolicyImplClass?.clazz?.let {
            screenshotPolicyImplClass
                .before(Pattern.compile(".*isManagedProfile.*"))
                .run { runParam ->
                    if (screenshotChordInsecure) {
                        runParam.result = false
                    }
                }
        }

        newCaptureArgsClass?.afterConstruction()?.run { runParam ->
            if (screenshotChordInsecure) {
                setObjectField(runParam.thisObject, "mSecureContentPolicy", 1)
            }
        }

        captureArgsClass?.afterConstruction()?.run { runParam ->
            if (screenshotChordInsecure) {
                setObjectField(runParam.thisObject, "mCaptureSecureLayers", true)
            }
        }

        // Android 17
        ReflectedClass.of(MediaPlayer::class.java)
            .before("start")
            .run { runParam ->
                if (disableScreenshotSound) {
                    runParam.result = null
                }
            }

        // Android 16 QPR2
        takeScreenshotExecutorImplClass
            .after("getScreenshotController")
            .run { runParam ->
                if (disableScreenshotSound) {
                    val result = runParam.result ?: return@run
                    val controller = getObjectField<Any>(result, "screenshotSoundController") ?: return@run
                    val dispatcherConstructor = ReflectedClass.of("kotlinx.coroutines.ExecutorCoroutineDispatcherImpl").clazz?.constructors?.firstOrNull()
                    val dispatcher = dispatcherConstructor?.newInstance(NoExecutor())
                    if (dispatcher != null) {
                        setObjectField(controller, "bgDispatcher", dispatcher)
                    }
                }
            }

        // Android 16 QPR1
        screenshotSoundControllerImplClass?.beforeConstruction()?.run { runParam ->
            if (disableScreenshotSound) {
                val dispatcherConstructor = ReflectedClass.of("kotlinx.coroutines.ExecutorCoroutineDispatcherImpl").clazz?.constructors?.firstOrNull()
                for (i in runParam.args.indices) {
                    val arg = runParam.args[i]
                    if (arg != null && arg.javaClass.name.lowercase().contains("dispatcher")) {
                        runParam.args[i] = dispatcherConstructor?.newInstance(NoExecutor())
                    }
                }
            }
        }
    }

    private class NoExecutor : ExecutorService {
        override fun shutdown() {}
        override fun shutdownNow(): List<Runnable>? = null
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
        override fun <T : Any?> submit(task: Callable<T>): Future<T>? = null
        override fun <T : Any?> submit(task: Runnable, result: T): Future<T>? = null
        override fun submit(task: Runnable): Future<*>? = null
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>>? = null
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): MutableList<Future<T>>? = null
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T? = null
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T? = null
        override fun execute(command: Runnable) {}
    }
}
