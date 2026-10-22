package sh.siava.pixelxpert.annotations

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class ChildProcessModPack(val processNameContains: String = "")
