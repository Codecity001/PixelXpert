package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findClassIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SystemUIModPack
class KeyGuardPinScrambler(context: Context) : XposedModPack(context) {

    private val digits = mutableListOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

    companion object {
        private var shufflePinEnabled = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false)
        if (shufflePinEnabled) {
            synchronized(digits) {
                digits.shuffle()
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        // 1. View-based PIN bouncer
        try {
            val keyguardPinBasedInputViewClass = ReflectedClass.of("com.android.keyguard.KeyguardPinBasedInputView")

            val pinShuffleHook: ReflectedClass.ReflectionConsumer = ReflectedClass.ReflectionConsumer { runParam ->
                if (!shufflePinEnabled) return@ReflectionConsumer

                synchronized(digits) {
                    digits.shuffle()

                    val mButtons = getObjectField(runParam.thisObject, "mButtons") as? Array<*> ?: return@synchronized

                    for (button in mButtons) {
                        if (button == null) continue
                        val mDigit = getIntField(button, "mDigit")
                        val newDigit = digits[mDigit]
                        setObjectField(button, "mDigit", newDigit)

                        val digitText = getObjectField(button, "mDigitText")
                        if (digitText != null) {
                            callMethod(digitText, "setText", newDigit.toString())
                        }
                    }
                }
            }

            keyguardPinBasedInputViewClass.after("onFinishInflate").run(pinShuffleHook)
            keyguardPinBasedInputViewClass.after("resetPasswordText").run(pinShuffleHook)
        } catch (_: Throwable) {}

        // 2. Compose-based PIN bouncer
        val pinBouncerKtClass = findClassIfExists("com.android.systemui.bouncer.ui.composable.PinBouncerKt", param.classLoader)
        if (pinBouncerKtClass != null) {
            for (method in pinBouncerKtClass.declaredMethods) {
                if (method.name.startsWith("DigitButton-")) {
                    HookHelper.hookMethod(method, { hookParam ->
                        if (!shufflePinEnabled) return@hookMethod

                        val args = hookParam.args
                        val original = args.getOrNull(0) as? Int ?: return@hookMethod

                        // Only the keyguard bouncer's own pad is shuffled (CredentialPinPadKt is left unshuffled)
                        val lambdaArg = args.getOrNull(2)
                        if (lambdaArg == null || !lambdaArg.javaClass.name.contains("PinBouncerKt")) {
                            return@hookMethod
                        }
                        if (original == 0 && lambdaArg != null) {
                            try {
                                val f = findFieldIfExists(lambdaArg.javaClass, "\$r8\$classId")
                                if (f != null) {
                                    if (f.type == java.lang.Byte.TYPE) {
                                        f.setByte(lambdaArg, 0.toByte())
                                    } else if (f.type == java.lang.Integer.TYPE) {
                                        f.setInt(lambdaArg, 0)
                                    }
                                }
                            } catch (_: Throwable) {}
                        }

                        // Handle Jetpack Compose self-recomposition (e.g. ripple effects)
                        // The last argument is the $changed bitmask. Bit 0 indicates self-recomposition.
                        val lastArg = args.lastOrNull()
                        if (args.size >= 10 && lastArg is Int) {
                            val changed = lastArg
                            if ((changed and 1) != 0) {
                                return@hookMethod
                            }
                            // Clear static bits for digit argument (bits 1..3: 0x0E) so Compose treats it as dynamic
                            args[args.size - 1] = changed and 0x0E.inv()
                        }

                            val index = if (original == 0) 9 else (original - 1)
                            synchronized(digits) {
                                if (index in digits.indices) {
                                    args[0] = digits[index]
                                }
                            }
                        }
                    }, runBefore = true, xposedInterface = XPLauncher.getXposedInterface())
                    break
                }
            }
        }

        val pinBouncerViewModelClass = findClassIfExists("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel", param.classLoader)
        if (pinBouncerViewModelClass != null) {
            try {
                ReflectedClass.of(pinBouncerViewModelClass).before("onActivated").run {
                    if (!shufflePinEnabled) return@run
                    synchronized(digits) {
                        digits.shuffle()
                    }
                }
            } catch (_: Throwable) {}
        }
    }
}
