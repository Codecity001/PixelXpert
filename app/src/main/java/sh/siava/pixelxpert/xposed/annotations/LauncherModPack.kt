package sh.siava.pixelxpert.xposed.annotations

import sh.siava.pixelxpert.annotations.BaseModPack

@BaseModPack(targetPackage = "com.google.android.apps.nexuslauncher")
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class LauncherModPack
