package sh.siava.pixelxpert.xposed.utils.reflection

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Thin wrapper over [XposedInterface.hook] that speaks the callback shape the
 * rest of the codebase was written against.
 *
 * The one interesting detail is that `RunParam` keeps `result`
 * and `isResultSet` non-public and mutates them from this file, which is why
 * only the accessor trio is public -
 * `ReflectedClass` hands a `RunParam` to user code and never touches the
 * pair itself.
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
	): Set<XposedInterface.HookHandle> {
		val result = LinkedHashSet<XposedInterface.HookHandle>()
		for (constructor in findConstructors(hookClass)) {
			result += hookMethod(constructor, callback, runBefore, xposedInterface)
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
	): Set<XposedInterface.HookHandle> {
		val result = LinkedHashSet<XposedInterface.HookHandle>()
		for (method in findMethods(hookClass, methodName)) {
			result += hookMethod(method, callback, runBefore, xposedInterface)
		}
		return result
	}

	/** Reflective call with the result unchecked, for the (many) Object-typed targets. */
	@JvmStatic
	@Suppress("UNCHECKED_CAST")
	fun <T> callMethod(obj: Any?, methodName: String, vararg args: Any?): T =
		de.robv.android.xposed.XposedHelpers.callMethod(obj, methodName, *args) as T

	@JvmStatic
	@Suppress("UNCHECKED_CAST")
	fun <T> getObjectField(obj: Any?, fieldName: String): T =
		de.robv.android.xposed.XposedHelpers.getObjectField(obj, fieldName) as T

	@JvmStatic
	fun hookMethod(
		hookMethod: Executable,
		callback: ReflectedClass.ReflectionConsumer,
		runBefore: Boolean,
		xposedInterface: XposedInterface,
	): XposedInterface.HookHandle = xposedInterface.hook(hookMethod).intercept { chain ->
		val param = RunParam(chain.thisObject, chain.executable, chain.args.toTypedArray())

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

	private fun findMethods(clazz: Class<*>, name: String): List<Method> =
		clazz.declaredMethods.filter { it.name == name }

	private fun findConstructors(clazz: Class<*>): List<Constructor<*>> =
		clazz.declaredConstructors.toList()

	/**
	 * What a [ReflectedClass.ReflectionConsumer] gets handed.
	 *
	 * [args] is the array handed straight back to `proceed`, so a consumer can
	 * rewrite an argument by mutating it in place.
	 *
	 * The two backing fields are `internal` rather than `private`: they are
	 * mutated from `HookHelper`, which Java allows
	 * within a top-level class but Kotlin does not between a class and its
	 * nested one. Consumers only ever touch [setResult] and [getResult].
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
