package sh.siava.pixelxpert.xposed.modpacks.ksu

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import io.github.libxposed.api.XposedModuleInterface
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.KSUModPack
import sh.siava.pixelxpert.xposed.annotations.KSUNextModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.concurrent.thread

@KSUModPack
@KSUNextModPack
class KSUInjector(context: Context) : XposedModPack(context) {

    private var nativesClass: ReflectedClass? = null
    private var profileClass: ReflectedClass? = null

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName // Can be KSU or KSU Next
        val mainActivityClass = ReflectedClass.of("$packageName.ui.MainActivity")
        nativesClass = ReflectedClass.of("$packageName.Natives")
        profileClass = ReflectedClass.of("$packageName.Natives\$Profile")

        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                grantRootToPX(intent)
            }
        }

        // In case ksu is running already, it won't understand the onCreate intent we send. broadcast it is then
        mContext.registerReceiver(broadcastReceiver, IntentFilter(Constants.PX_ROOT_EXTRA), Context.RECEIVER_EXPORTED)

        mainActivityClass
            .after("onCreate")
            .run { hookParam ->
                val activity = hookParam.thisObject as? Activity ?: return@run
                val launchIntent = activity.intent
                if (launchIntent != null && launchIntent.hasExtra(Constants.PX_ROOT_EXTRA)) {
                    grantRootToPX(launchIntent)
                }
            }
    }

    private fun grantRootToPX(launchIntent: Intent) {
        thread {
            try {
                val natClass = nativesClass?.clazz ?: return@thread
                val profClass = profileClass?.clazz ?: return@thread

                val nativeObject = ObjenesisHelper.newInstance(natClass)
                val rootUIDs = callMethod(nativeObject, "getAllowList") as? IntArray ?: intArrayOf()

                val packageManager = mContext.packageManager
                val ownUID = packageManager.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.GET_ACTIVITIES)

                val haveRoot = rootUIDs.any { it == ownUID }

                if (!haveRoot) {
                    val constructor = profClass.getConstructor(
                        String::class.java,
                        java.lang.Integer.TYPE,
                        java.lang.Boolean.TYPE,
                        java.lang.Boolean.TYPE,
                        String::class.java,
                        java.lang.Integer.TYPE,
                        java.lang.Integer.TYPE,
                        List::class.java,
                        List::class.java,
                        String::class.java,
                        java.lang.Integer.TYPE,
                        java.lang.Boolean.TYPE,
                        java.lang.Boolean.TYPE,
                        String::class.java
                    )
                    val ownRootProfile = constructor.newInstance(
                        BuildConfig.APPLICATION_ID,
                        ownUID,
                        true,
                        true,
                        null,
                        0,
                        0,
                        ArrayList<Any>(),
                        ArrayList<Any>(),
                        "u:r:su:s0",
                        0,
                        true,
                        true,
                        ""
                    )

                    callMethod(nativeObject, "setAppProfile", ownRootProfile)
                    restartPX(launchIntent.hasExtra("launchApp"))
                }
                Thread.sleep(2000)
                SystemUtils.killSelf()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun restartPX(launch: Boolean) {
        Shell.cmd("killall " + BuildConfig.APPLICATION_ID).exec()

        if (launch) {
            Thread.sleep(1000)
            mContext.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)?.let {
                it.putExtra("FromKSU", 1)
                mContext.startActivity(it)
            }
        }
    }
}
