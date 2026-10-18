package sh.siava.pixelxpert.xposed.utils.reflection

import androidx.collection.ArraySet
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Method
import java.util.regex.Pattern
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger.log

class ReflectedClass(val clazz: Class<*>?) {

    companion object {
        @JvmField
        var defaultClassloader: ClassLoader? = null

        @JvmField
        var frameworkClassloader: ClassLoader? = null

        @JvmField
        var defaultXposedInterface: XposedInterface? = null

        private const val FLAG_DEBUG_HOOKS = false

        @JvmStatic
        fun of(clazz: Class<*>?): ReflectedClass = ReflectedClass(clazz)

        @JvmStatic
        fun of(name: String, loader: ClassLoader?): ReflectedClass {
            val resolvedClass = runCatching {
                HookHelper.findClassIfExists(name, loader) ?: Class.forName(name, false, loader)
            }.getOrElse {
                runCatching {
                    HookHelper.findClassIfExists(name, frameworkClassloader) ?: Class.forName(name, false, frameworkClassloader)
                }.getOrNull()
            }
            return ReflectedClass(resolvedClass)
        }

        @JvmStatic
        fun of(name: String): ReflectedClass = of(name, defaultClassloader)

        @JvmStatic
        fun ofIfPossible(name: String, loader: ClassLoader?): ReflectedClass {
            var result = HookHelper.findClassIfExists(name, loader)
            if (result == null && frameworkClassloader != null) {
                result = HookHelper.findClassIfExists(name, frameworkClassloader)
            }
            return ReflectedClass(result)
        }

        @JvmStatic
        fun ofIfPossible(name: String): ReflectedClass = ofIfPossible(name, defaultClassloader)

        @JvmStatic
        fun setFrameworkClassloader(loader: ClassLoader?) {
            frameworkClassloader = loader
        }

        @JvmStatic
        fun setDefaultClassloader(loader: ClassLoader?) {
            defaultClassloader = loader
        }

        @JvmStatic
        fun setDefaultXposedInterface(xposedInterface: XposedInterface?) {
            defaultXposedInterface = xposedInterface
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

    fun before(method: Method): BeforeMethodData {
        // Bug 1.1 Fix: use declaringClass instead of method.javaClass
        return BeforeMethodData(method.declaringClass, null, method, false)
    }

    fun before(methodName: String): BeforeMethodData {
        return BeforeMethodData(clazz, methodName, null, false)
    }

    fun before(pattern: Pattern): BeforeMethodDatas {
        return BeforeMethodDatas(clazz, pattern)
    }

    fun beforeConstruction(): BeforeMethodData {
        return BeforeMethodData(clazz, null, null, true)
    }

    fun after(method: Method): AfterMethodData {
        // Bug 1.1 Fix: use declaringClass instead of method.javaClass
        return AfterMethodData(method.declaringClass, null, method, false)
    }

    fun after(methodName: String): AfterMethodData {
        return AfterMethodData(clazz, methodName, null, false)
    }

    fun after(pattern: Pattern): AfterMethodDatas {
        return AfterMethodDatas(clazz, pattern)
    }

    fun afterConstruction(): AfterMethodData {
        return AfterMethodData(clazz, null, null, true)
    }

    fun callStaticMethod(methodName: String, vararg args: Any?): Any? {
        return if (clazz != null) HookHelper.callStaticMethod(clazz, methodName, *args) else null
    }

    fun findMethods(namePattern: Pattern): Set<Method> {
        return if (clazz != null) HookHelper.findDeclaredAndInheritedMethods(clazz, namePattern) else emptySet()
    }

    fun findFirstInstance(foundCallback: InstanceFoundCallback) {
        findFirstInstance(defaultXposedInterface, foundCallback)
    }

    fun findFirstInstance(xposedInterface: XposedInterface?, foundCallback: InstanceFoundCallback) {
        if (clazz == null || xposedInterface == null) return
        val unhooks = ArraySet<HookHandle>()
        findMethods(Pattern.compile(".+")).forEach { method ->
            unhooks.addAll(before(method).run(xposedInterface) { param ->
                unhooks.forEach { it.unhook() }
                unhooks.clear()
                val targetObj = param.thisObject
                if (targetObj != null) {
                    foundCallback.onInstanceCaptured(targetObj)
                }
            })
        }
    }

    fun dumpStructure() {
        val c = clazz ?: return
        log("Class: ${c.name}")
        log("extends: ${c.superclass?.name}")
        log("Subclasses:")
        for (sc in c.classes) {
            log(sc.name)
        }
        log("Methods:")
        for (m in c.declaredMethods) {
            log("${m.name} - ${m.returnType} - ${m.parameterCount}")
            for (p in m.parameterTypes) {
                log("\t\t${p.typeName}")
            }
        }
        log("Fields:")
        for (f in c.declaredFields) {
            log("\t\t${f.name}-${f.type.name}")
        }
        log("End dump")
    }

    abstract class MethodData(
        val targetClass: Class<*>?,
        val methodName: String?,
        val method: Method?,
        val isConstructor: Boolean
    ) {
        protected fun runBefore(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> {
            val c = targetClass ?: return emptySet()
            return when {
                isConstructor -> HookHelper.hookAllConstructors(c, consumer, true, xposedInterface)
                method != null -> setOf(HookHelper.hookMethod(method, consumer, true, xposedInterface))
                methodName != null -> HookHelper.hookAllMethods(c, methodName, consumer, true, xposedInterface)
                else -> emptySet()
            }
        }

        protected fun runAfter(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> {
            val c = targetClass ?: return emptySet()
            return when {
                isConstructor -> HookHelper.hookAllConstructors(c, consumer, false, xposedInterface)
                method != null -> setOf(HookHelper.hookMethod(method, consumer, false, xposedInterface))
                methodName != null -> HookHelper.hookAllMethods(c, methodName, consumer, false, xposedInterface)
                else -> emptySet()
            }
        }
    }

    inner class BeforeMethodData(
        clazz: Class<*>?,
        name: String?,
        method: Method?,
        isConstructor: Boolean
    ) : MethodData(clazz, name, method, isConstructor) {
        fun run(consumer: ReflectionConsumer): Set<HookHandle> = runBefore(defaultXposedInterface, consumer)
        fun run(consumer: ReflectionConsumer, log: Boolean): Set<HookHandle> = runBefore(defaultXposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> = runBefore(xposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer, log: Boolean): Set<HookHandle> = runBefore(xposedInterface, consumer)
    }

    inner class AfterMethodData(
        clazz: Class<*>?,
        name: String?,
        method: Method?,
        isConstructor: Boolean
    ) : MethodData(clazz, name, method, isConstructor) {
        fun run(consumer: ReflectionConsumer): Set<HookHandle> = runAfter(defaultXposedInterface, consumer)
        fun run(consumer: ReflectionConsumer, log: Boolean): Set<HookHandle> = runAfter(defaultXposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> = runAfter(xposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer, log: Boolean): Set<HookHandle> = runAfter(xposedInterface, consumer)
    }

    inner class BeforeMethodDatas(clazz: Class<*>?, pattern: Pattern) {
        private val datas = ArraySet<BeforeMethodData>()
        init {
            if (clazz != null) {
                HookHelper.findDeclaredAndInheritedMethods(clazz, pattern).forEach { method ->
                    datas.add(BeforeMethodData(clazz, method.name, null, false))
                }
            }
        }
        fun run(consumer: ReflectionConsumer): Set<HookHandle> = run(defaultXposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> {
            val unhooks = ArraySet<HookHandle>()
            datas.forEach { unhooks.addAll(it.run(xposedInterface, consumer)) }
            return unhooks
        }
    }

    inner class AfterMethodDatas(clazz: Class<*>?, pattern: Pattern) {
        private val datas = ArraySet<AfterMethodData>()
        init {
            if (clazz != null) {
                HookHelper.findDeclaredAndInheritedMethods(clazz, pattern).forEach { method ->
                    datas.add(AfterMethodData(clazz, method.name, null, false))
                }
            }
        }
        fun run(consumer: ReflectionConsumer): Set<HookHandle> = run(defaultXposedInterface, consumer)
        fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<HookHandle> {
            val unhooks = ArraySet<HookHandle>()
            datas.forEach { unhooks.addAll(it.run(xposedInterface, consumer)) }
            return unhooks
        }
    }

    fun interface InstanceFoundCallback {
        fun onInstanceCaptured(instance: Any)
    }

    fun interface ReflectionConsumer {
        @Throws(Throwable::class)
        fun run(param: HookHelper.RunParam)
    }
}
