package sh.siava.pixelxpert.xposed.utils.reflection

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Stable identity of a hook registration.
 *
 * The framework scopes ids per module and executable, so an id only has to be
 * stable for one callsite. Deriving it from the callsite and the target means
 * the next generation re-registers the same hook and API 102 replaces it
 * atomically instead of layering another copy on top.
 */
data class HookCaller(val className: String, val methodName: String, val line: Int) {
	override fun toString(): String = "$className#$methodName:$line"
}

object HookId {
	private const val PREFIX = "pxn"
	private const val INTERNAL_PACKAGE = "sh.siava.pixelxpert.xposed.utils.reflection"

	/**
	 * Captures the first frame outside this reflection package.
	 *
	 * The hook DSL adds its own frames on top; they describe the plumbing, not
	 * the mod pack that asked for the hook.
	 */
	fun caller(): HookCaller {
		val stack = Throwable().stackTrace
		for (element in stack) {
			if (element.className.startsWith(INTERNAL_PACKAGE)) continue
			if (element.className == HookId::class.java.name) continue
			return HookCaller(element.className, element.methodName, element.lineNumber)
		}
		return HookCaller("unknown", "unknown", 0)
	}

	fun of(caller: HookCaller, target: Executable): String = buildString {
		append(PREFIX)
		append(':')
		append(caller.className)
		append('#')
		append(caller.methodName)
		append(':')
		append(caller.line)
		append("->")
		append(target.declaringClass.name)
		append('#')
		append(signature(target))
	}

	/** Base id for a group of overloads; [HookHelper] appends the concrete signature. */
	fun forMethod(caller: HookCaller, clazz: Class<*>, label: String): String =
		"$PREFIX:${caller.className}#${caller.methodName}:${caller.line}->${clazz.name}#$label"

	/** A readable, overload-safe description of a method or constructor. */
	fun signature(target: Executable): String {
		val name =
			when (target) {
				is Constructor<*> -> "<init>"
				is Method -> if (Modifier.isStatic(target.modifiers)) "static ${target.name}" else target.name
				else -> target.name
			}
		val parameters = target.parameterTypes.joinToString(",") { it.typeName }
		return "$name($parameters)"
	}
}
