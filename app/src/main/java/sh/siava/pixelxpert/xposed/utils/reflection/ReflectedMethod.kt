package sh.siava.pixelxpert.xposed.utils.reflection

import de.robv.android.xposed.XposedBridge.invokeOriginalMethod
import de.robv.android.xposed.XposedHelpers.findMethodExact
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * A single resolved [Method], with the two call styles a hook body needs:
 * run the original, or run the hooked method itself.
 *
 * `ofName` matches a *pattern* against the name and takes the first hit from
 * the public method list, which is the tolerant lookup when a method is
 * overloaded and only one signature matters.
 */
class ReflectedMethod private constructor(private val method: Method) {

	fun beforeThat(consumer: ReflectedClass.ReflectionConsumer): Set<XposedInterface.HookHandle> =
		ReflectedClass.of(method.javaClass)
			.before(method)
			.run(consumer)

	fun afterThat(consumer: ReflectedClass.ReflectionConsumer): Set<XposedInterface.HookHandle> =
		ReflectedClass.of(method.javaClass)
			.after(method)
			.run(consumer)

	/**
	 * Runs the original implementation, bypassing this hook.
	 *
	 * Distinct from [invoke] because a "before" callback that wants to
	 * delegate has to reach the real method, not re-enter the hook chain.
	 */
	@Throws(Throwable::class)
	fun invokeOriginal(`object`: Any?, vararg args: Any?): Any? =
		invokeOriginalMethod(method, `object`, args)

	/** Invokes the method reflectively, going through any hooks on it. */
	@Throws(Throwable::class)
	fun invoke(`object`: Any?, vararg args: Any?): Any? = method.invoke(`object`, *args)

	companion object {
		@JvmStatic
		fun of(method: Method): ReflectedMethod = ReflectedMethod(method)

		/** Resolves by exact name and parameter types; throws when there is no match. */
		@JvmStatic
		fun ofExactData(
			reflectedClass: ReflectedClass,
			name: String,
			vararg parameterTypes: Class<*>,
		): ReflectedMethod =
			ReflectedMethod(
				findMethodExact(reflectedClass.clazz!!, name, *parameterTypes),
			)

		/** Resolves the first public method whose name matches [exactName] as a pattern. */
		@JvmStatic
		fun ofName(reflectedClass: ReflectedClass, exactName: String): ReflectedMethod =
			ReflectedMethod(findMethod(reflectedClass.clazz!!, exactName))

		@JvmStatic
		fun findMethod(clazz: Class<*>, namePattern: String): Method {
			for (method in clazz.methods) {
				if (Pattern.matches(namePattern, method.name)) {
					return method
				}
			}
			throw NoSuchMethodException("$clazz#$namePattern")
		}
	}
}
