package sh.siava.pixelxpert.xposed.annotations

import sh.siava.pixelxpert.annotations.BaseModPack

@BaseModPack(targetPackage = "android")
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class FrameworkModPack
