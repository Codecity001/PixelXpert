package sh.siava.pixelxpert.annotations

data class ModPackData(
	val clazz: Class<*>,
	val targetPackage: String,
	val mainProcess: Boolean = true,
	val childProcess: Boolean = false,
	val childProcessName: String = "",
) {
	// Compatibility properties for legacy calls
	val targetsMainProcess: Boolean get() = mainProcess
	val targetsChildProcess: Boolean get() = childProcess
}
