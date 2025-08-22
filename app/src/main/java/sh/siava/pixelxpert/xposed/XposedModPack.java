package sh.siava.pixelxpert.xposed;

import static sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass.setDefaultClassloader;

import android.content.Context;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public abstract class XposedModPack {
	protected Context mContext;

	public XposedModPack(Context context) {
		mContext = context;
	}

	public abstract void onPreferenceUpdated(String... Key);

	public final void onPackageLoadedInternal(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		setDefaultClassloader(lpParam.classLoader);
		onPackageLoaded(lpParam);
	}

	public abstract void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable;
}