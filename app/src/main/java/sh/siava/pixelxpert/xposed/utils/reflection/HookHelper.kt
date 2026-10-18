package sh.siava.pixelxpert.xposed.utils.reflection

import androidx.collection.ArraySet
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.regex.Pattern

object HookHelper {

    @JvmStatic
    fun hookAllConstructors(
        hookClass: Class<*>,
        callback: ReflectedClass.ReflectionConsumer,
        runBefore: Boolean,
        xposedInterface: XposedInterface?
    ): Set<HookHandle> {
        val result = ArraySet<HookHandle>()
        if (xposedInterface == null) return result
        for (constructor in hookClass.declaredConstructors) {
            result.add(hookMethod(constructor, callback, runBefore, xposedInterface))
        }
        return result
    }

    @JvmStatic
    fun hookAllMethods(
        hookClass: Class<*>,
        methodName: String,
        callback: ReflectedClass.ReflectionConsumer,
        runBefore: Boolean,
        xposedInterface: XposedInterface?
    ): Set<HookHandle> {
        val result = ArraySet<HookHandle>()
        if (xposedInterface == null) return result
        for (method in findDeclaredAndInheritedMethods(hookClass, methodName)) {
            result.add(hookMethod(method, callback, runBefore, xposedInterface))
        }
        return result
    }

    @JvmStatic
    fun hookMethod(
        hookMethod: Executable,
        callback: ReflectedClass.ReflectionConsumer,
        runBefore: Boolean,
        xposedInterface: XposedInterface?
    ): HookHandle {
        if (xposedInterface == null) {
            return HookHandle { }
        }
        return xposedInterface.hook(hookMethod).intercept { chain ->
            val argsArray = chain.args.toTypedArray()
            val param = RunParam(chain.thisObject, chain.executable, argsArray)

            if (runBefore) {
                runCatching {
                    callback.run(param)
                }.onFailure { t ->
                    android.util.Log.e("PixelXpertHook", "Exception in before hook: ${hookMethod.name}", t)
                }

                if (param.isResultSet) {
                    return@intercept param.result
                }

                chain.proceed(param.args)
            } else {
                param.result = chain.proceed(param.args)
                runCatching {
                    callback.run(param)
                }.onFailure { t ->
                    android.util.Log.e("PixelXpertHook", "Exception in after hook: ${hookMethod.name}", t)
                }
                param.result
            }
        }
    }

    @JvmStatic
    fun findDeclaredAndInheritedMethods(clazz: Class<*>, name: String): Set<Method> {
        val result = ArraySet<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == name) {
                    m.isAccessible = true
                    result.add(m)
                }
            }
            current = current.superclass
        }
        return result
    }

    @JvmStatic
    fun findDeclaredAndInheritedMethods(clazz: Class<*>, pattern: Pattern): Set<Method> {
        val result = ArraySet<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (pattern.matcher(m.name).matches()) {
                    m.isAccessible = true
                    result.add(m)
                }
            }
            current = current.superclass
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> callMethod(obj: Any, methodName: String, vararg args: Any?): T? {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == args.size) {
                    m.isAccessible = true
                    return m.invoke(obj, *args) as? T
                }
            }
            current = current.superclass
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> getObjectField(obj: Any, fieldName: String): T? {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            val curr = current
            runCatching {
                val f = curr.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj) as? T
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var current: Class<*>? = obj.javaClass
        while (current != null && current != Any::class.java) {
            val curr = current
            runCatching {
                val f = curr.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            }
            current = current.superclass
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> getStaticObjectField(clazz: Class<*>, fieldName: String): T? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val curr = current
            runCatching {
                val f = curr.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(null) as? T
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val curr = current
            runCatching {
                val f = curr.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(null, value)
                return
            }
            current = current.superclass
        }
    }

    @JvmStatic
    fun findFieldIfExists(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val curr = current
            runCatching {
                val f = curr.getDeclaredField(fieldName)
                f.isAccessible = true
                return f
            }
            current = current.superclass
        }
        return null
    }

    private val additionalFields = java.util.WeakHashMap<Any, java.util.HashMap<String, Any?>>()

    @JvmStatic
    fun setAdditionalInstanceField(obj: Any, key: String, value: Any?) {
        synchronized(additionalFields) {
            val map = additionalFields.getOrPut(obj) { java.util.HashMap() }
            map[key] = value
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> getAdditionalInstanceField(obj: Any, key: String): T? {
        synchronized(additionalFields) {
            return additionalFields[obj]?.get(key) as? T
        }
    }

    @JvmStatic
    fun getBooleanField(obj: Any, fieldName: String): Boolean = getObjectField<Boolean>(obj, fieldName) ?: false

    @JvmStatic
    fun getIntField(obj: Any, fieldName: String): Int = getObjectField<Int>(obj, fieldName) ?: 0

    @JvmStatic
    fun getFloatField(obj: Any, fieldName: String): Float = getObjectField<Float>(obj, fieldName) ?: 0f

    @JvmStatic
    fun getLongField(obj: Any, fieldName: String): Long = getObjectField<Long>(obj, fieldName) ?: 0L

    @JvmStatic
    fun setBooleanField(obj: Any, fieldName: String, value: Boolean) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun setIntField(obj: Any, fieldName: String, value: Int) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun setFloatField(obj: Any, fieldName: String, value: Float) = setObjectField(obj, fieldName, value)

    @JvmStatic
    fun setLongField(obj: Any, fieldName: String, value: Long) = setObjectField(obj, fieldName, value)

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T> callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): T? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == args.size) {
                    m.isAccessible = true
                    return m.invoke(null, *args) as? T
                }
            }
            current = current.superclass
        }
        return null
    }

    @JvmStatic
    fun findClassIfExists(className: String, classLoader: ClassLoader? = null): Class<*>? {
        return runCatching {
            if (classLoader != null) Class.forName(className, false, classLoader)
            else Class.forName(className)
        }.getOrNull()
    }

    @JvmStatic
    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>?): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == paramTypes.size) {
                    m.isAccessible = true
                    return m
                }
            }
            current = current.superclass
        }
        return null
    }

    class RunParam(
        @JvmField val thisObject: Any?,
        @JvmField val method: Executable?,
        @JvmField var args: Array<Any?>
    ) {
        @JvmField
        var result: Any? = null

        @JvmField
        var isResultSet: Boolean = false

        fun setResult(res: Any?) {
            isResultSet = true
            result = res
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> getResult(): T? = result as? T

        @Suppress("UNCHECKED_CAST")
        fun <T> getThisObject(): T? = thisObject as? T

        @Suppress("UNCHECKED_CAST")
        fun <T> getArg(index: Int): T? = args.getOrNull(index) as? T

        fun setArg(index: Int, value: Any?) {
            if (index in args.indices) {
                args[index] = value
            }
        }
    }
}
