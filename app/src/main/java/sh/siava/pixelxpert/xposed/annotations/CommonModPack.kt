package sh.siava.pixelxpert.xposed.annotations

import sh.siava.pixelxpert.annotations.BaseModPack

/**
 * Applies to every scoped package, so the pack itself decides what it does.
 *
 * This carries [@BaseModPack][BaseModPack], which is how :modPackProcessor
 * finds every class annotated with this one and registers it.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@BaseModPack(targetPackage = "")
annotation class CommonModPack
