package sh.siava.pixelxpert.modpacks.systemui;

import static sh.siava.pixelxpert.modpacks.XPrefs.Xprefs;

import android.content.Context;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.modpacks.Constants;
import sh.siava.pixelxpert.modpacks.XPLauncher;
import sh.siava.pixelxpert.modpacks.XposedModPack;
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
public class ThreeButtonNavMods extends XposedModPack {
	private static final String listenPackage = Constants.SYSTEM_UI_PACKAGE;
	private boolean ThreeButtonLayoutMod = false;
	private static String ThreeButtonCenter, ThreeButtonRight, ThreeButtonLeft;

	public ThreeButtonNavMods(Context context) {
		super(context);
	}

	@Override
	public void updatePrefs(String... Key) {
		if (Xprefs == null) return;
		ThreeButtonLayoutMod = Xprefs.getBoolean("ThreeButtonLayoutMod", false);

		ThreeButtonLeft = Xprefs.getString("ThreeButtonLeft", "back");
		ThreeButtonCenter = Xprefs.getString("ThreeButtonCenter", "home");
		ThreeButtonRight = Xprefs.getString("ThreeButtonRight", "recent");
	}


	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) {
		if (!lpParam.packageName.equals(listenPackage)) return;

		ReflectedClass NavigationBarInflaterViewClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.views.NavigationBarInflaterView");
		if(NavigationBarInflaterViewClass.getClazz() == null)
		{
			NavigationBarInflaterViewClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.NavigationBarInflaterView");
		}

		NavigationBarInflaterViewClass
				.before("inflateLayout")
				.run(param -> {
					if (!ThreeButtonLayoutMod || !((String) param.args[0]).contains("recent")) return;

					String layout = ((String) param.args[0])
							.replace("home", "XCenterX")
							.replace("back", "XLeftX")
							.replace("recent", "XRightX");

					param.args[0] = layout
							.replace("XCenterX", ThreeButtonCenter)
							.replace("XLeftX", ThreeButtonLeft)
							.replace("XRightX", ThreeButtonRight);
				});
	}

	@Override
	public boolean listensTo(String packageName) {
		return listenPackage.equals(packageName) && !XPLauncher.isChildProcess;
	}

}
