package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.Method

/**
 * Applies the "insecure screen record" toggle to the screen recorder.
 *
 * When `InsecureScreenRecord` is enabled the pack OR-s
 * [DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE] into the flags of the
 * eight-argument overload of `MediaProjection.createVirtualDisplay` - the
 * signature the current recorder calls - so the recording session is created
 * with the secure bit regardless of what the caller passed.
 */
@SystemUIModPack
class ScreenRecord(context: Context) : XposedModPack(context) {

	companion object {
		private var InsecureScreenRecord = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		InsecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.of(MediaProjection::class.java)
			.before("createVirtualDisplay")
			.run { param ->
				if (InsecureScreenRecord &&
					(param.method as Method).parameterCount == 8
				) {
					var flags = param.args[4] as Int
					flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
					param.args[4] = flags
				}
			}
	}
}
