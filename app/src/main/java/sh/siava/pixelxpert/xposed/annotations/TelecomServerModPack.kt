package sh.siava.pixelxpert.xposed.annotations

import sh.siava.pixelxpert.annotations.BaseModPack

@BaseModPack(targetPackage = "com.android.server.telecom")
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class TelecomServerModPack
