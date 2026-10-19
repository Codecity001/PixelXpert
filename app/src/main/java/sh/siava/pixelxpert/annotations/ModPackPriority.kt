package sh.siava.pixelxpert.annotations

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class ModPackPriority(val priority: Int = 99)
