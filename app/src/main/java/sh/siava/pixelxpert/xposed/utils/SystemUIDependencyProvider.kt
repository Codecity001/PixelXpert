package sh.siava.pixelxpert.xposed.utils

import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

object SystemUIDependencyProvider {
    private var sDependency: Any? = null

    @JvmStatic
    fun get(fullClassName: String): Any? {
        if (sDependency == null) {
            getInstance()
        }
        val targetClass = ReflectedClass.of(fullClassName).getClazz() ?: return null
        return try {
            val dep = sDependency ?: return null
            callMethod<Any>(dep, "getDependencyInner", targetClass)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun getInstance() {
        val dependencyClass = ReflectedClass.of("com.android.systemui.Dependency")
        val clazz = dependencyClass.getClazz() ?: return
        sDependency = getStaticObjectField<Any>(clazz, "sDependency")
    }
}
