package sh.siava.pixelxpert.xposed.annotations

import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.annotations.BaseModPack

/**
 * Applies to the KernelSU manager.
 *
 * This carries [@BaseModPack][BaseModPack], which is how :modPackProcessor
 * finds every class annotated with this one and registers it for that
 * package.
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
@BaseModPack(targetPackage = Constants.KSU_PACKAGE)
annotation class KSUModPack
