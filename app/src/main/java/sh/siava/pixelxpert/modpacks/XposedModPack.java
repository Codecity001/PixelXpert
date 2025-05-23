package sh.siava.pixelxpert.modpacks;

import android.content.Context;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.modpacks.startup.HybridClassLoader;
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass;

public abstract class XposedModPack {
	protected Context mContext;

	public XposedModPack(Context context) {
		mContext = context;
	}

	public abstract void updatePrefs(String... Key);
	public final void handleLoadPackageInternal(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable
	{
		ReflectedClass.setDefaultClassloader(injectClassLoader(lpParam.classLoader));
		handleLoadPackage(lpParam);
	}

	public abstract void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable;

	public abstract boolean listensTo(String packageName);

	private ClassLoader injectClassLoader(ClassLoader classLoader) {
		if (classLoader == null) {
			throw new NullPointerException("classLoader == null");
		}
		try {
			Field fParent = ClassLoader.class.getDeclaredField("parent");
			fParent.setAccessible(true);
			ClassLoader mine = XposedModPack.class.getClassLoader();
			ClassLoader curr = (ClassLoader) fParent.get(mine);
			if (curr == null) {
				curr = XposedBridge.class.getClassLoader();
			}
			if (!curr.getClass().getName().equals(HybridClassLoader.class.getName())) {
				HybridClassLoader hybrid = new HybridClassLoader(curr, classLoader);
				fParent.set(mine, hybrid);
				return hybrid;
			} else {
				return curr;
			}
		} catch (Exception e) {
			XposedBridge.log(e);
			return classLoader;
		}
	}

}