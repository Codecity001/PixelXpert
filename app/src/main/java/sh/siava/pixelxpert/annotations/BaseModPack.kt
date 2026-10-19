package sh.siava.pixelxpert.annotations

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class BaseModPack(val targetPackage: String = "")
