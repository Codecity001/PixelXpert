package sh.siava.pixelxpert.xposed.utils.reflection

import sh.siava.pixelxpert.xposed.XposedRuntime
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection helpers for code that runs inside a hooked process.
 *
 * This replaces `de.robv.android.xposed.XposedHelpers` one-for-one: the call
 * sites keep reading as `getObjectField(...)`/`callMethod(...)`, but the
 * implementation is ours, so a module targeting API 102 never touches the
 * legacy Xposed API that the framework blocks.
 */
object Reflection {

	fun findClassIfExists(className: String?, classLoader: ClassLoader?): Class<*>? {
		if (className.isNullOrEmpty()) return null
		return try {
			Class.forName(className, false, classLoader)
		} catch (_: ClassNotFoundException) {
			null
		} catch (_: LinkageError) {
			null
		}
	}

	fun getObjectField(instance: Any?, fieldName: String): Any? =
		getField(instanceClass(instance, fieldName), fieldName, false).get(instance)

	fun getStaticObjectField(clazz: Class<*>?, fieldName: String): Any? =
		getField(requireClass(clazz, fieldName), fieldName, true).get(null)

	fun getBooleanField(instance: Any?, fieldName: String): Boolean =
		getField(instanceClass(instance, fieldName), fieldName, false).getBoolean(instance)

	fun getIntField(instance: Any?, fieldName: String): Int =
		getField(instanceClass(instance, fieldName), fieldName, false).getInt(instance)

	fun getLongField(instance: Any?, fieldName: String): Long =
		getField(instanceClass(instance, fieldName), fieldName, false).getLong(instance)

	fun getFloatField(instance: Any?, fieldName: String): Float =
		getField(instanceClass(instance, fieldName), fieldName, false).getFloat(instance)

	fun getDoubleField(instance: Any?, fieldName: String): Double =
		getField(instanceClass(instance, fieldName), fieldName, false).getDouble(instance)

	fun getShortField(instance: Any?, fieldName: String): Short =
		getField(instanceClass(instance, fieldName), fieldName, false).getShort(instance)

	fun getByteField(instance: Any?, fieldName: String): Byte =
		getField(instanceClass(instance, fieldName), fieldName, false).getByte(instance)

	fun getCharField(instance: Any?, fieldName: String): Char =
		getField(instanceClass(instance, fieldName), fieldName, false).getChar(instance)

	fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
		getField(instanceClass(instance, fieldName), fieldName, false).set(instance, value)
	}

	fun setStaticObjectField(clazz: Class<*>?, fieldName: String, value: Any?) {
		getField(requireClass(clazz, fieldName), fieldName, true).set(null, value)
	}

	fun setBooleanField(instance: Any?, fieldName: String, value: Boolean) {
		getField(instanceClass(instance, fieldName), fieldName, false).setBoolean(instance, value)
	}

	fun setIntField(instance: Any?, fieldName: String, value: Int) {
		getField(instanceClass(instance, fieldName), fieldName, false).setInt(instance, value)
	}

	fun setLongField(instance: Any?, fieldName: String, value: Long) {
		getField(instanceClass(instance, fieldName), fieldName, false).setLong(instance, value)
	}

	fun setFloatField(instance: Any?, fieldName: String, value: Float) {
		getField(instanceClass(instance, fieldName), fieldName, false).setFloat(instance, value)
	}

	fun setDoubleField(instance: Any?, fieldName: String, value: Double) {
		getField(instanceClass(instance, fieldName), fieldName, false).setDouble(instance, value)
	}

	fun findFieldIfExists(clazz: Class<*>?, fieldName: String): Field? {
		if (clazz == null) return null
		var current: Class<*>? = clazz
		while (current != null) {
			try {
				return current.getDeclaredField(fieldName).apply { isAccessible = true }
			} catch (_: NoSuchFieldException) {
				current = current.superclass
			}
		}
		return null
	}

	fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? =
		invokeMethod(findMethodBestMatch(instance?.javaClass, methodName, *args), instance, args)

	fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? =
		invokeMethod(findMethodBestMatch(clazz, methodName, *args), null, args)

	fun findMethodExact(clazz: Class<*>?, methodName: String, vararg parameterTypes: Class<*>): Method {
		val target = clazz ?: throw NoSuchMethodError("null.$methodName")
		var current: Class<*>? = target
		while (current != null) {
			try {
				return current.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
			} catch (_: NoSuchMethodException) {
				current = current.superclass
			}
		}
		// Interfaces carry default methods that are not in the class hierarchy.
		findInterfaceMethod(target, methodName, parameterTypes)?.let { return it }
		throw NoSuchMethodError("${target.name}#$methodName")
	}

	/**
	 * Exact lookup by parameter type, matching the legacy overload that took
	 * `Class<?>... parameterTypes`. Kotlin picks this overload when every
	 * trailing argument is a `Class`, so a call site that wants an exact
	 * signature keeps working without being interpreted as passing Class
	 * objects as runtime arguments.
	 */
	fun findMethodBestMatch(clazz: Class<*>?, methodName: String, vararg parameterTypes: Class<*>): Method =
		findMethodExact(clazz, methodName, *parameterTypes)

	/**
	 * Finds the most specific method whose parameters accept [args].
	 *
	 * Exact matches win first. Otherwise the first assignable method in the
	 * class hierarchy is used, which is the same tolerance the legacy helper
	 * offered for overloaded framework internals.
	 */
	fun findMethodBestMatch(clazz: Class<*>?, methodName: String, vararg args: Any?): Method {
		val target = clazz ?: throw NoSuchMethodError("null.$methodName")
		val candidates = declaredMethodsInHierarchy(target).filter { it.name == methodName }
		if (candidates.isEmpty()) throw NoSuchMethodError("${target.name}#$methodName")

		candidates.firstOrNull { method -> parametersMatchExactly(method.parameterTypes, args) }?.let {
			return it.apply { isAccessible = true }
		}
		candidates.firstOrNull { method -> parametersMatch(method.parameterTypes, args) }?.let {
			return it.apply { isAccessible = true }
		}

		throw NoSuchMethodError(
			"${target.name}#$methodName(${args.joinToString(",") { it?.javaClass?.name ?: "null" }})",
		)
	}

	/**
	 * Runs the original implementation through the libxposed invoker.
	 *
	 * Kept under the old helper name so the hook code reads the same while the
	 * implementation is API 102 native.
	 */
	@Throws(Throwable::class)
	fun invokeOriginalMethod(method: Method, instance: Any?, args: Array<out Any?>?): Any? =
		XposedRuntime.invokeOriginal(method, instance, *(args ?: emptyArray()))

	fun getAdditionalInstanceField(instance: Any?, key: String): Any? =
		XposedRuntime.getAdditionalInstanceField(instance, key)

	fun setAdditionalInstanceField(instance: Any?, key: String, value: Any?): Any? =
		XposedRuntime.setAdditionalInstanceField(instance, key, value)

	fun removeAdditionalInstanceField(instance: Any?, key: String): Any? =
		XposedRuntime.removeAdditionalInstanceField(instance, key)

	private fun instanceClass(instance: Any?, fieldName: String): Class<*> =
		instance?.javaClass ?: throw NullPointerException("Cannot read $fieldName from null")

	private fun requireClass(clazz: Class<*>?, fieldName: String): Class<*> =
		clazz ?: throw NoSuchFieldError("null#$fieldName")

	private fun getField(clazz: Class<*>, fieldName: String, static: Boolean): Field {
		val field =
			findFieldIfExists(clazz, fieldName)
				?: throw NoSuchFieldError("${clazz.name}#$fieldName")
		if (static && !Modifier.isStatic(field.modifiers)) {
			throw NoSuchFieldError("${clazz.name}#$fieldName is not static")
		}
		if (!static && Modifier.isStatic(field.modifiers)) {
			throw NoSuchFieldError("${clazz.name}#$fieldName is static")
		}
		return field
	}

	private fun invokeMethod(method: Method, instance: Any?, args: Array<out Any?>): Any? = try {
		method.invoke(instance, *args)
	} catch (t: InvocationTargetException) {
		throw t.targetException ?: t
	}

	private fun declaredMethodsInHierarchy(clazz: Class<*>): List<Method> {
		val methods = ArrayList<Method>()
		var current: Class<*>? = clazz
		while (current != null) {
			current.declaredMethods.forEach { method ->
				methods += method.apply { isAccessible = true }
			}
			current = current.superclass
		}
		clazz.interfaces.forEach { iface -> collectInterfaceMethods(iface, methods) }
		return methods
	}

	private fun collectInterfaceMethods(clazz: Class<*>, methods: MutableList<Method>) {
		clazz.declaredMethods.forEach { method -> methods += method.apply { isAccessible = true } }
		clazz.interfaces.forEach { iface -> collectInterfaceMethods(iface, methods) }
	}

	private fun findInterfaceMethod(clazz: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? {
		val methods = ArrayList<Method>()
		clazz.interfaces.forEach { iface -> collectInterfaceMethods(iface, methods) }
		return methods.firstOrNull {
			it.name == name && it.parameterTypes.contentEquals(parameterTypes)
		}?.apply { isAccessible = true }
	}

	private fun parametersMatchExactly(parameters: Array<Class<*>>, args: Array<out Any?>): Boolean {
		if (parameters.size != args.size) return false
		return parameters.indices.all { index ->
			val parameter = parameters[index]
			val argument = args[index]
			if (argument == null) {
				!parameter.isPrimitive
			} else {
				parameter == argument.javaClass || boxed(parameter) == argument.javaClass
			}
		}
	}

	private fun parametersMatch(parameters: Array<Class<*>>, args: Array<out Any?>): Boolean {
		if (parameters.size != args.size) return false
		return parameters.indices.all { index -> isAssignable(parameters[index], args[index]) }
	}

	private fun isAssignable(parameter: Class<*>, argument: Any?): Boolean {
		if (argument == null) return !parameter.isPrimitive
		val target = if (parameter.isPrimitive) boxed(parameter) else parameter
		return target.isAssignableFrom(argument.javaClass)
	}

	private fun boxed(type: Class<*>): Class<*> = when (type) {
		java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
		java.lang.Byte.TYPE -> java.lang.Byte::class.java
		java.lang.Character.TYPE -> java.lang.Character::class.java
		java.lang.Short.TYPE -> java.lang.Short::class.java
		java.lang.Integer.TYPE -> java.lang.Integer::class.java
		java.lang.Long.TYPE -> java.lang.Long::class.java
		java.lang.Float.TYPE -> java.lang.Float::class.java
		java.lang.Double.TYPE -> java.lang.Double::class.java
		java.lang.Void.TYPE -> java.lang.Void::class.java
		else -> type
	}
}
