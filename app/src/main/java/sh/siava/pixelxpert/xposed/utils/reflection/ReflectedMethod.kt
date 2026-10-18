package sh.siava.pixelxpert.xposed.utils.reflection

import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Method
import java.util.regex.Pattern

class ReflectedMethod private constructor(val method: Method?) {

    companion object {
        @JvmStatic
        fun of(method: Method?): ReflectedMethod = ReflectedMethod(method)

        @JvmStatic
        fun ofExactData(reflectedClass: ReflectedClass, name: String, vararg parameterTypes: Class<*>): ReflectedMethod {
            val clazz = reflectedClass.clazz ?: return ReflectedMethod(null)
            val m = runCatching {
                var cur: Class<*>? = clazz
                var found: Method? = null
                while (cur != null && cur != Any::class.java) {
                    try {
                        found = cur.getDeclaredMethod(name, *parameterTypes)
                        found.isAccessible = true
                        break
                    } catch (_: NoSuchMethodException) {
                        cur = cur.superclass
                    }
                }
                found
            }.getOrNull()
            return ReflectedMethod(m)
        }

        @JvmStatic
        fun ofName(reflectedClass: ReflectedClass, exactName: String): ReflectedMethod {
            val clazz = reflectedClass.clazz ?: return ReflectedMethod(null)
            return ReflectedMethod(findMethod(clazz, Pattern.quote(exactName)))
        }

        @JvmStatic
        fun findMethod(clazz: Class<*>?, namePattern: String): Method? {
            if (clazz == null) return null
            val pattern = Pattern.compile(namePattern)
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                for (m in current.declaredMethods) {
                    if (pattern.matcher(m.name).matches()) {
                        m.isAccessible = true
                        return m
                    }
                }
                current = current.superclass
            }
            return null
        }
    }

    fun beforeThat(consumer: ReflectedClass.ReflectionConsumer): Collection<HookHandle> {
        val m = method ?: return emptyList()
        return ReflectedClass.of(m.declaringClass).before(m).run(consumer)
    }

    fun afterThat(consumer: ReflectedClass.ReflectionConsumer): Set<HookHandle> {
        val m = method ?: return emptySet()
        return ReflectedClass.of(m.declaringClass).after(m).run(consumer)
    }

    fun invokeOriginal(obj: Any?, vararg args: Any?): Any? {
        val m = method ?: return null
        return m.invoke(obj, *args)
    }

    fun invoke(obj: Any?, vararg args: Any?): Any? {
        val m = method ?: return null
        return m.invoke(obj, *args)
    }
}
