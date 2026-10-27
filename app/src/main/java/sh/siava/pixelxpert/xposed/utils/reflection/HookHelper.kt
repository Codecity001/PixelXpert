package sh.siava.pixelxpert.xposed.utils.reflection

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import sh.siava.pixelxpert.xposed.XposedGeneration.HookOptions
import sh.siava.pixelxpert.xposed.XposedRuntime
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Adapter between the interceptor-chain API and the callback shape the mod
 * packs use.
 *
 * The `runBefore` flag is what makes the two hook flavours: before, the
 * consumer runs first and may short-circuit `proceed`; after, `proceed` runs
 * first and the consumer sees (and may replace) the result.
 */
object HookHelper {

	@JvmStatic
	fun hookAllConstructors(
		hookClass: Class<*>,
		callback: ReflectedClass.ReflectionConsumer,
		runBefore: Boolean,
		xposedInterface: XposedInterface,
		options: HookOptions,
	): Set<HookHandle> {
		val result = LinkedHashSet<HookHandle>()
		for (constructor in findConstructors(hookClass)) {
			result +=
				hookMethod(
					constructor,
					callback,
					runBefore,
					xposedInterface,
					options.copy(id = idFor(options.id, constructor)),
				)
		}
		return result
	}

	@JvmStatic
	fun hookAllMethods(
		hookClass: Class<*>,
		methodName: String,
		callback: ReflectedClass.ReflectionConsumer,
		runBefore: Boolean,
		xposedInterface: XposedInterface,
		options: HookOptions,
	): Set<HookHandle> {
		val result = LinkedHashSet<HookHandle>()
		for (method in findMethods(hookClass, methodName)) {
			result +=
				hookMethod(
					method,
					callback,
					runBefore,
					xposedInterface,
					options.copy(id = idFor(options.id, method)),
				)
		}
		return result
	}

	@JvmStatic
	@Suppress("UNCHECKED_CAST")
	fun <T> callMethod(obj: Any?, methodName: String, vararg args: Any?): T =
		Reflection.callMethod(obj, methodName, *args) as T

	@JvmStatic
	@Suppress("UNCHECKED_CAST")
	fun <T> getObjectField(obj: Any?, fieldName: String): T = Reflection.getObjectField(obj, fieldName) as T

	@JvmStatic
	fun hookMethod(
		hookMethod: Executable,
		callback: ReflectedClass.ReflectionConsumer,
		runBefore: Boolean,
		xposedInterface: XposedInterface,
		options: HookOptions,
	): HookHandle = XposedRuntime.hook(xposedInterface, hookMethod, options) { chain ->
		// A retired generation must only pass through. The framework keeps
		// old hooks installed until the successor replaces or unhooks
		// them, so this is what stops old pack code running in the gap.
		if (!XposedRuntime.isActive()) return@hook chain.proceed()

		val param =
			RunParam(chain.thisObject, chain.executable, chain.args.toTypedArray())

		if (runBefore) {
			callback.run(param)
			// A consumer that set a result is declining to proceed.
			if (param.isResultSet) param.result else chain.proceed(param.args)
		} else {
			param.result = chain.proceed(param.args)
			callback.run(param)
			param.result
		}
	}

	private fun idFor(id: String?, target: Executable): String? =
		if (id == null) null else "$id->${HookId.signature(target)}"

	private fun findMethods(clazz: Class<*>, name: String): List<Method> = clazz.declaredMethods.filter { it.name == name }

	private fun findConstructors(clazz: Class<*>): List<Constructor<*>> = clazz.declaredConstructors.toList()

	/**
	 * What a [ReflectedClass.ReflectionConsumer] gets handed.
	 *
	 * [args] is the array handed straight back to `proceed`, so a consumer can
	 * rewrite an argument by mutating it in place.
	 */
	@Suppress("unused")
	class RunParam constructor(
		@JvmField var thisObject: Any?,
		@JvmField var method: Executable,
		@JvmField var args: Array<Any?>,
	) {
		internal var result: Any? = null
		internal var isResultSet = false

		/** Sets the value to return, which for a "before" hook also skips `proceed`. */
		fun setResult(result: Any?) {
			isResultSet = true
			this.result = result
		}

		@Suppress("UNCHECKED_CAST")
		fun <T> getResult(): T = result as T

		@Suppress("UNCHECKED_CAST")
		fun <T> getThisObject(): T = thisObject as T

		@Suppress("UNCHECKED_CAST")
		fun <T> getArg(index: Int): T = args[index] as T
	}
}
