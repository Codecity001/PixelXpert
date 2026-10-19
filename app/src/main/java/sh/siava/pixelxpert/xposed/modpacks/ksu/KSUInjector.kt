package sh.siava.pixelxpert.xposed.modpacks.ksu

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import de.robv.android.xposed.XposedHelpers.callMethod
import io.github.libxposed.api.XposedModuleInterface
import java.util.ArrayList
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.KSUModPack
import sh.siava.pixelxpert.xposed.annotations.KSUNextModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Hands the module root inside KernelSU and KernelSU-Next. It listens for the
 * module's `PX_ROOT_EXTRA` broadcast and also hooks the manager's `MainActivity`
 * so an `onCreate` intent carrying that extra is caught either way; when root
 * isn't on the manager's allow list yet it builds a su profile for the module's
 * own UID through the manager's `Natives` API, writes it, then restarts the
 * module (optionally relaunching it) before killing the manager process.
 *
 * Both flavours are covered by deriving every class name from the package the
 * pack was loaded into, which is why two annotations stack on this class.
 *
 * @noinspection RedundantThrows
 */
@KSUModPack
@KSUNextModPack
class KSUInjector(context: Context) : XposedModPack(context) {

	private lateinit var NativesClass: ReflectedClass
	private lateinit var ProfileClass: ReflectedClass

	/** Nothing this pack reads; declared because [XposedModPack] requires it. */
	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val packageName = PRParam.packageName // Can be KSU or KSU Next
		val MainActivityClass = ReflectedClass.of("$packageName.ui.MainActivity")
		NativesClass = ReflectedClass.of("$packageName.Natives")
		ProfileClass = ReflectedClass.of("$packageName.Natives\$Profile")

		val broadcastReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				grantRootToPX(intent)
			}
		}

		//In case ksu is running already, it won't understand the onCreate intent we send. broadcast it is then
		mContext.registerReceiver(broadcastReceiver, IntentFilter(Constants.PX_ROOT_EXTRA), Context.RECEIVER_EXPORTED)

		MainActivityClass
			.after("onCreate")
			.run { param ->
				val launchIntent = (param.thisObject as Activity).intent
				if (launchIntent.hasExtra(Constants.PX_ROOT_EXTRA)) {
					grantRootToPX(launchIntent)
				}
			}
	}

	private fun grantRootToPX(launchIntent: Intent) {
		Thread {
			try {
				@Suppress("UNCHECKED_CAST")
				val nativeObject = ObjenesisHelper.newInstance<Any?>(NativesClass.clazz as Class<Any?>)
				val rootUIDs = callMethod(nativeObject, "getAllowList") as IntArray

				val packageManager = mContext.packageManager
				val ownUID = packageManager.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.GET_ACTIVITIES)

				val haveRoot = rootUIDs.any { it == ownUID }

				if (!haveRoot) {
					val ownRootProfile = ProfileClass.clazz!!.getConstructor(
						String::class.java,
						Int::class.javaPrimitiveType,
						Boolean::class.javaPrimitiveType,
						Boolean::class.javaPrimitiveType,
						String::class.java,
						Int::class.javaPrimitiveType,
						Int::class.javaPrimitiveType,
						List::class.java,
						List::class.java,
						String::class.java,
						Int::class.javaPrimitiveType,
						Boolean::class.javaPrimitiveType,
						Boolean::class.javaPrimitiveType,
						String::class.java,
					).newInstance(
						BuildConfig.APPLICATION_ID, ownUID, true, true, null, 0, 0,
						ArrayList<Any?>(), ArrayList<Any?>(), "u:r:su:s0", 0, true, true, "",
					)

					callMethod(nativeObject, "setAppProfile", ownRootProfile)

					restartPX(launchIntent.hasExtra("launchApp"))
				}
				Thread.sleep(2000)
				SystemUtils.killSelf()
			} catch (ignored: Throwable) {
			}
		}.start()
	}

	private fun restartPX(launch: Boolean) {
		Shell.cmd("killall ${BuildConfig.APPLICATION_ID}").exec()

		if (launch) {
			Thread.sleep(1000)
			//noinspection DataFlowIssue
			mContext.startActivity(
				mContext
					.packageManager
					.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)!!
					.putExtra("FromKSU", 1),
			)
		}
	}
}
