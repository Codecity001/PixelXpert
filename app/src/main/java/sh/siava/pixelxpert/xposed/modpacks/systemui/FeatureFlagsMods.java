package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;



import android.content.Context;

import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class FeatureFlagsMods extends XposedModPack {
//	public static final String CLIPBOARD_OVERLAY_SHOW_ACTIONS = "clipboard_overlay_show_actions";
//	public static final String NAMESPACE_SYSTEMUI = "systemui";

	private static final int SIGNAL_DEFAULT = 0;
	@SuppressWarnings("unused")
	private static final int SIGNAL_FORCE_LTE = 1;
	private static final int SIGNAL_FORCE_4G = 2;

	public static int SBLTEIcon = SIGNAL_DEFAULT;

	private static boolean EnableClipboardSmartActions = false;

	public FeatureFlagsMods(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		SBLTEIcon = Integer.parseInt(Xprefs.getString(
				"LTE4GIconMod",
				String.valueOf(SIGNAL_DEFAULT)));

		EnableClipboardSmartActions = Xprefs.getBoolean("EnableClipboardSmartActions", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass.of("android.provider.DeviceConfig")
				.before("getBoolean")
				.run(param -> {
					if (param.args.length >= 2 && "systemui".equals(param.args[0]) && "clipboard_overlay_show_actions".equals(param.args[1])) {
						if (EnableClipboardSmartActions) {
							param.setResult(true);
						}
					}
				});

		ReflectedClass.of("com.android.settingslib.mobile.MobileMappings$Config")
				.after("readConfig")
				.run(param -> {
					if (SBLTEIcon == SIGNAL_DEFAULT) return;

					setObjectField(param.getResult(),
							"show4gForLte",
							SBLTEIcon == SIGNAL_FORCE_4G);
				});
	}
}
