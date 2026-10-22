package sh.siava.pixelxpert.xposed.utils.reflection

import android.annotation.SuppressLint
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findClassIfExists
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.regex.Pattern
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

/**
 * The hook DSL every mod pack is written against:
 * `ReflectedClass.of("...").before("method").run(instance) { ... }`.
 *
 * The wrapped [clazz] is allowed to be **null** - `of` and `ofIfPossible` both
 * fall back to the framework class loader and may still find nothing - and the
 * `before`/`after` builders then short-circuit to an empty handle set instead
 * of throwing. That is what lets a pack target an API a given Android build
 * simply does not have.
 *
 * Deviation, deliberate: Kotlin refuses a public subclass that exposes a
 * private supertype, so the builders cannot be `private`/inner nor
 * [MethodData] `private static` - [MethodData] and the four builders are
 * public with internal constructors instead. Nothing outside this file
 * builds them; they reach callers only as the fluent return type of [before]
 * and [after].
 */
class ReflectedClass(val clazz: Class<*>?) {

	/**
	 * Logs every declared member, for working out what to hook.
	 *
	 * On an unresolved class this logs a line and returns instead of
	 * crashing; a diagnostic path only.
	 */
	fun dumpStructure() {
		val target = clazz ?: run {
			Logger.log("ReflectedClass.dumpStructure: class not resolved")
			return
		}

		Logger.log("Class: " + target.name)
		//noinspection DataFlowIssue
		Logger.log("extends: " + target.superclass.name)
		Logger.log("Subclasses:")
		for (c in target.classes) {
			Logger.log(c.name)
		}
		Logger.log("Methods:")

		for (m in target.declaredConstructors) {
			Logger.log("${m.name} -  - ${m.parameterCount}")
			for (c in m.parameterTypes) {
				Logger.log("\t\t" + c.typeName)
			}
		}

		for (m in target.declaredMethods) {
			Logger.log("${m.name} - ${m.returnType} - ${m.parameterCount}")
			for (c in m.parameterTypes) {
				Logger.log("\t\t" + c.typeName)
			}
		}
		Logger.log("Fields:")

		for (f in target.declaredFields) {
			Logger.log("\t\t${f.name} - ${f.type.name}")
		}
		Logger.log("End dump")
	}

	fun callStaticMethod(methodName: String, vararg args: Any?): Any? =
		XposedHelpers.callStaticMethod(clazz, methodName, *args)

	fun findMethods(namePattern: Pattern): Set<Method> =
		clazz?.let { matchesIn(it, namePattern) } ?: emptySet()

	fun before(method: Method): BeforeMethodData =
		// `method.getClass()` - the class of the *Method object* - is
		// passed rather than the declaring class. Only the logging below ever
		// reads it, so the quirk is preserved rather than quietly corrected.
		BeforeMethodData(method.javaClass, null, method, false)

	fun before(methodName: String): BeforeMethodData = BeforeMethodData(clazz, methodName, null, false)

	fun before(pattern: Pattern): BeforeMethodDatas = BeforeMethodDatas(clazz, pattern)

	fun beforeConstruction(): BeforeMethodData = BeforeMethodData(clazz, null, null, true)

	fun after(methodName: String): AfterMethodData = AfterMethodData(clazz, methodName, null, false)

	fun after(method: Method): AfterMethodData = AfterMethodData(method.javaClass, null, method, false)

	fun after(pattern: Pattern): AfterMethodDatas = AfterMethodDatas(clazz, pattern)

	fun afterConstruction(): AfterMethodData = AfterMethodData(clazz, null, null, true)

	/**
	 * Captures the first live instance of the class by hooking every method it
	 * declares, then unhooks itself.
	 *
	 * Useful when a build optimisation removed the constructor, leaving nothing
	 * to hook to observe construction.
	 */
	fun findFirstInstance(foundCallback: InstanceFoundCallback) {
		findFirstInstance(defaultXposedInterface, foundCallback)
	}

	fun findFirstInstance(xposedInterface: XposedInterface?, foundCallback: InstanceFoundCallback) {
		val unhooks = LinkedHashSet<XposedInterface.HookHandle>()
		for (method in findMethods(Pattern.compile(".+"))) {
			unhooks += before(method).run(xposedInterface, ReflectionConsumer { param ->
				unhooks.forEach { it.unhook() }
				unhooks.clear()
				foundCallback.onInstanceCaptured(param.thisObject)
			})
		}
	}

	/**
	 * Shared state of a before/after request: which class, which method, and
	 * whether the target is a constructor.
	 *
	 * [clazz] doubles as the "was the class resolvable at all" gate: [runBefore]
	 * and [runAfter] return an empty set when it is null.
	 */
	open class MethodData(
		internal val clazz: Class<*>?,
		internal val methodName: String?,
		internal val method: Method?,
		internal val isConstructor: Boolean,
	) {
		protected fun runBefore(
			xposedInterface: XposedInterface?,
			consumer: ReflectionConsumer,
			log: Boolean = false,
		): Set<XposedInterface.HookHandle> {
			val target = clazz ?: return emptySet()
			val iface = xposedInterface ?: return emptySet()

			val hooked: Set<XposedInterface.HookHandle> = when {
				isConstructor -> {
					val handles = HookHelper.hookAllConstructors(target, wrap(consumer, log), true, iface)
					maybeLogHook(handles.size, target, "before constructor", null, null, log)
					handles
				}

				method != null -> {
					val handle = HookHelper.hookMethod(method, wrap(consumer, log), true, iface)
					maybeLogHook(1, target, "before method", method, null, log)
					linkedSetOf(handle)
				}

				else -> {
					val handles = HookHelper.hookAllMethods(target, methodName!!, wrap(consumer, log), true, iface)
					maybeLogHook(handles.size, target, "before method", null, methodName, log)
					handles
				}
			}
			return hooked
		}

		protected fun runAfter(
			xposedInterface: XposedInterface?,
			consumer: ReflectionConsumer,
			log: Boolean = false,
		): Set<XposedInterface.HookHandle> {
			val target = clazz ?: return emptySet()
			val iface = xposedInterface ?: return emptySet()

			val hooked: Set<XposedInterface.HookHandle> = when {
				isConstructor -> {
					val handles = HookHelper.hookAllConstructors(target, wrap(consumer, log), false, iface)
					maybeLogHook(handles.size, target, "after constructor", null, null, log)
					handles
				}

				method != null -> {
					val handle = HookHelper.hookMethod(method, wrap(consumer, log), false, iface)
					maybeLogHook(1, target, "after method", method, null, log)
					linkedSetOf(handle)
				}

				else -> {
					val handles = HookHelper.hookAllMethods(target, methodName!!, wrap(consumer, log), false, iface)
					maybeLogHook(handles.size, target, "after method", null, methodName, log)
					handles
				}
			}
			return hooked
		}

		private fun wrap(consumer: ReflectionConsumer, log: Boolean): ReflectionConsumer =
			if (!log) consumer else ReflectionConsumer { param ->
				Logger.log(param.method.name + " called")
				consumer.run(param)
			}

		/**
		 * The "which line asked for this hook" line, gated on the caller asking
		 * for it or on [FLAG_DEBUG_HOOKS].
		 *
		 * [log] is that flag, so the logger is named out in full rather than
		 * shadowed by it.
		 */
		@SuppressLint("DefaultLocale")
		private fun maybeLogHook(
			size: Int,
			target: Class<*>,
			what: String,
			method: Method?,
			methodName: String?,
			log: Boolean,
		) {
			if (!log && !FLAG_DEBUG_HOOKS) return

			val element = Thread.currentThread().stackTrace[2]
			val described = method?.name ?: methodName
			val message = if (described == null) {
				String.format(
					"%s line %d: Hook to %s of %s size = %d",
					element.className, element.lineNumber, what, target.name, size,
				)
			} else {
				String.format(
					"%s line %d: Hook to %s %s of %s size = %d",
					element.className, element.lineNumber, what, described, target.name, size,
				)
			}
			Logger.log(message)
		}
	}

	class BeforeMethodData internal constructor(
		clazz: Class<*>?,
		methodName: String?,
		method: Method?,
		isConstructor: Boolean,
	) : MethodData(clazz, methodName, method, isConstructor) {

		fun run(consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			runBefore(defaultXposedInterface, consumer, false)

		fun run(consumer: ReflectionConsumer, log: Boolean): Set<XposedInterface.HookHandle> =
			runBefore(defaultXposedInterface, consumer, log)

		fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			runBefore(xposedInterface, consumer, false)

		fun run(
			xposedInterface: XposedInterface?,
			consumer: ReflectionConsumer,
			log: Boolean,
		): Set<XposedInterface.HookHandle> = runBefore(xposedInterface, consumer, log)
	}

	class AfterMethodData internal constructor(
		clazz: Class<*>?,
		methodName: String?,
		method: Method?,
		isConstructor: Boolean,
	) : MethodData(clazz, methodName, method, isConstructor) {

		fun run(consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			runAfter(defaultXposedInterface, consumer, false)

		fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			runAfter(xposedInterface, consumer, false)

		fun run(consumer: ReflectionConsumer, log: Boolean): Set<XposedInterface.HookHandle> =
			runAfter(defaultXposedInterface, consumer, log)

		fun run(
			xposedInterface: XposedInterface?,
			consumer: ReflectionConsumer,
			log: Boolean,
		): Set<XposedInterface.HookHandle> = runAfter(xposedInterface, consumer, log)
	}

	class BeforeMethodDatas internal constructor(clazz: Class<*>?, namePattern: Pattern) {
		private val datas: Set<BeforeMethodData> =
			(clazz?.let { matchesIn(it, namePattern) } ?: emptySet())
				.mapTo(LinkedHashSet()) { BeforeMethodData(clazz, it.name, null, false) }

		fun run(consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			run(defaultXposedInterface, consumer)

		fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> {
			val unhooks = LinkedHashSet<XposedInterface.HookHandle>()
			datas.forEach { unhooks += it.run(xposedInterface, consumer) }
			return unhooks
		}
	}

	class AfterMethodDatas internal constructor(clazz: Class<*>?, namePattern: Pattern) {
		private val datas: Set<AfterMethodData> =
			(clazz?.let { matchesIn(it, namePattern) } ?: emptySet())
				.mapTo(LinkedHashSet()) { AfterMethodData(clazz, it.name, null, false) }

		fun run(consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> =
			run(defaultXposedInterface, consumer)

		fun run(xposedInterface: XposedInterface?, consumer: ReflectionConsumer): Set<XposedInterface.HookHandle> {
			val unhooks = LinkedHashSet<XposedInterface.HookHandle>()
			datas.forEach { unhooks += it.run(xposedInterface, consumer) }
			return unhooks
		}
	}

	fun interface InstanceFoundCallback {
		fun onInstanceCaptured(instance: Any?)
	}

	fun interface ReflectionConsumer {
		fun run(param: HookHelper.RunParam)
	}

	companion object {
		private var defaultClassloader: ClassLoader? = null
		private var frameworkClassloader: ClassLoader? = null
		private var defaultXposedInterface: XposedInterface? = null

		private const val FLAG_DEBUG_HOOKS = false

		@JvmStatic
		fun of(clazz: Class<*>): ReflectedClass = ReflectedClass(clazz)

		@JvmStatic
		fun of(name: String, loader: ClassLoader?): ReflectedClass {
			var c = findClassIfExists(name, loader)
			if (c == null) {
				c = findClassIfExists(name, frameworkClassloader)
			}
			return ReflectedClass(c)
		}

		@JvmStatic
		fun of(name: String): ReflectedClass = of(name, defaultClassloader)

		/**
		 * Like [of], but wraps what comes back - including null - instead of
		 * letting a missing class abort the caller.
		 */
		@JvmStatic
		fun ofIfPossible(name: String, loader: ClassLoader?): ReflectedClass {
			var result = findClassIfExists(name, loader)
			if (result == null && frameworkClassloader != null) {
				result = findClassIfExists(name, frameworkClassloader)
			}
			return ReflectedClass(result)
		}

		@JvmStatic
		fun ofIfPossible(name: String): ReflectedClass = ofIfPossible(name, defaultClassloader)

		@JvmStatic
		fun setFrameworkClassloader(classLoader: ClassLoader?) {
			frameworkClassloader = classLoader
		}

		@JvmStatic
		fun getFrameworkClassloader(): ClassLoader? = frameworkClassloader

		@JvmStatic
		fun setDefaultClassloader(classLoader: ClassLoader?) {
			defaultClassloader = classLoader
		}

		@JvmStatic
		fun setDefaultXposedInterface(xposedInterface: XposedInterface?) {
			defaultXposedInterface = xposedInterface
		}
	}
}

private fun matchesIn(clazz: Class<*>, namePattern: Pattern): Set<Method> {
	val result = LinkedHashSet<Method>()
	for (method in clazz.declaredMethods) {
		if (namePattern.matcher(method.name).matches()) {
			result += method
		}
	}
	return result
}
