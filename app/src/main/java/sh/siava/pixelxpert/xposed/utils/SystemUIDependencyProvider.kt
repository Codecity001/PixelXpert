package sh.siava.pixelxpert.xposed.utils

import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Asks SystemUI's own service locator for a singleton rather than reflecting
 * for it at every call site.
 *
 * `Dependency.sDependency` is resolved once and cached for the life of the
 * process; every lookup then goes through `getDependencyInner`, which is the
 * only path that both registers and returns the object. A failed lookup - the
 * class is not present, the singleton has not been installed yet, or the
 * target type is absent from this build - returns null instead of throwing,
 * so callers decide what an unavailable dependency means for them.
 */
class SystemUIDependencyProvider {

	companion object {
		private var sDependency: Any? = null

		@JvmStatic
		fun get(fullClassName: String): Any? {
			if (sDependency == null) {
				getInstance()
			}

			return try {
				callMethod(sDependency, "getDependencyInner", ReflectedClass.of(fullClassName).clazz)
			} catch (ignored: Throwable) {
				null
			}
		}

		private fun getInstance() {
			val DependencyClass = ReflectedClass.of("com.android.systemui.Dependency")
			sDependency = getStaticObjectField(DependencyClass.clazz, "sDependency")
		}
	}
}
