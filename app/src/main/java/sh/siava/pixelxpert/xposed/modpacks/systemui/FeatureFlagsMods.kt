package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.util.regex.Pattern
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Tweaks two SystemUI feature-flag consumers that the module cannot change at
 * the flag level: the clipboard overlay's smart-action controller is told the
 * remote-action flag is on, and the mobile mappings config is told whether to
 * show "4G" for LTE depending on the selected LTE/4G icon style.
 *
 * Both are plain after-hooks on the classes that read the flags, so they keep
 * working on builds where the DeviceConfig read itself is inlined or absent.
 */
@SystemUIModPack
class FeatureFlagsMods(context: Context) : XposedModPack(context) {

	companion object {
		//	public static final String CLIPBOARD_OVERLAY_SHOW_ACTIONS = "clipboard_overlay_show_actions";
		//	public static final String NAMESPACE_SYSTEMUI = "systemui";

		private const val SIGNAL_DEFAULT = 0
		//noinspection unused
		private const val SIGNAL_FORCE_LTE = 1
		private const val SIGNAL_FORCE_4G = 2

		@JvmField
		var SBLTEIcon = SIGNAL_DEFAULT

		private var EnableClipboardSmartActions = false
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {

		SBLTEIcon = Integer.parseInt(
			Xprefs.getString(
				"LTE4GIconMod",
				SIGNAL_DEFAULT.toString(),
			),
		)

		EnableClipboardSmartActions = Xprefs.getBoolean("EnableClipboardSmartActions", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		/*		ReflectedClass DeviceConfigClass = ReflectedClass.of("android.provider.DeviceConfig");

		hookAllMethods(DeviceConfigClass, "getBoolean", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				if(param.args[0].equals(NAMESPACE_SYSTEMUI) && param.args[1].equals(CLIPBOARD_OVERLAY_SHOW_ACTIONS))
				{
					param.setResult(EnableClipboardSmartActions);
				}
			}
		});*/
		//replaced with this:
		val ClipboardOverlayControllerClass = ReflectedClass.of("com.android.systemui.clipboardoverlay.ClipboardOverlayController")

		ClipboardOverlayControllerClass
			.before(Pattern.compile("setExpandedView.*"))
			.run { param ->
				if (EnableClipboardSmartActions) {
					setObjectField(
						getObjectField(param.thisObject, "mClipboardModel"),
						"isRemote",
						true,
					)
				}
			}

		ReflectedClass.of("com.android.settingslib.mobile.MobileMappings\$Config")
			.after("readConfig")
			.run { param ->
				if (SBLTEIcon == SIGNAL_DEFAULT) return@run

				setObjectField(
					param.getResult<Any?>(),
					"show4gForLte",
					SBLTEIcon == SIGNAL_FORCE_4G,
				)
			}
	}
}
