package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import java.util.Collections

/**
 * Shuffles the digits of the PIN bouncer so onlookers cannot read a PIN off
 * the keypad, and so shoulder-surfing the positions gains nothing.
 *
 * Both bouncer implementations are covered: the classic view-based keypad,
 * where every `mDigit` button's label is re-assigned from a shuffled digit
 * list after each inflation and password reset, and the newer Compose
 * `PinBouncerKt`/`PinBouncerViewModel` path, where the digit handed to
 * `DigitButton` is remapped (and the `$changed` bitmask is adjusted so Compose
 * does not treat it as a compile-time constant). The shuffle list is re-rolled
 * whenever the preference loads and whenever the view bouncer is rebuilt.
 */
@SystemUIModPack
class KeyGuardPinScrambler(context: Context) : XposedModPack(context) {

	companion object {
		private var shufflePinEnabled = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false)
		if (shufflePinEnabled) {
			synchronized(digits) {
				Collections.shuffle(digits)
			}
		}
	}

	val digits: MutableList<Int> = mutableListOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val KeyguardPinBasedInputViewClass = ReflectedClass.of("com.android.keyguard.KeyguardPinBasedInputView")

			val pinShuffleHook = ReflectionConsumer { param ->
				if (!shufflePinEnabled) return@ReflectionConsumer

				synchronized(digits) {
					Collections.shuffle(digits)

					val mButtons = getObjectField(param.thisObject, "mButtons") as? Array<*> ?: return@ReflectionConsumer

					for (button in mButtons) {
						val mDigit = getIntField(button, "mDigit")
						setObjectField(button, "mDigit", digits[mDigit])

						callMethod(
							getObjectField(button, "mDigitText"),
							"setText",
							digits[mDigit].toString(),
						)
					}
				}
			}

			KeyguardPinBasedInputViewClass.after("onFinishInflate").run(pinShuffleHook)
			KeyguardPinBasedInputViewClass.after("resetPasswordText").run(pinShuffleHook)
		} catch (ignored: Throwable) {
		}

		val PinBouncerKtClass = Reflection.findClassIfExists(
			"com.android.systemui.bouncer.ui.composable.PinBouncerKt",
			PRParam.classLoader,
		)
		if (PinBouncerKtClass != null) {
			val digitButton = PinBouncerKtClass.declaredMethods.firstOrNull { it.name.startsWith("DigitButton-") }
			if (digitButton != null) {
				ReflectedClass.of(PinBouncerKtClass)
					.before(digitButton)
					.run { param ->
						if (!shufflePinEnabled) return@run

						if (param.args.size > 2 && param.args[0] is Int) {
							// Only the keyguard bouncer's own pad is shuffled.
							val onClick = param.args[2]
							if (onClick == null || !onClick.javaClass.name.contains("PinBouncerKt")) {
								return@run
							}

							val original = param.args[0] as Int

							// Fix R8 bytecode hardcoding: button 0's onClick lambda usually has $r8$classId == 1
							// which explicitly bypasses parameter injection. We reset it to 0.
							if (original == 0) {
								try {
									val f = findFieldIfExists(onClick.javaClass, "\$r8\$classId")
									if (f != null) {
										if (f.type == Byte::class.javaPrimitiveType) {
											f.setByte(onClick, 0.toByte())
										} else if (f.type == Int::class.javaPrimitiveType) {
											f.setInt(onClick, 0)
										}
									}
								} catch (ignored: Throwable) {
								}
							}

							// Handle Jetpack Compose self-recomposition (e.g. ripple effects)
							// The last argument is the $changed bitmask. Bit 0 indicates self-recomposition.
							if (param.args.size >= 10 && param.args[param.args.size - 1] is Int) {
								val changed = param.args[param.args.size - 1] as Int
								if ((changed and 1) != 0) {
									// This is a self-recomposition, do not re-scramble or map digits
									return@run
								}
								// Clear static bits for digit argument (bits 1..3: 0x0E) so Compose treats it as dynamic
								param.args[param.args.size - 1] = changed and 0x0E.inv()
							}

							val index = if (original == 0) 9 else (original - 1)
							synchronized(digits) {
								if (index >= 0 && index < digits.size) {
									param.args[0] = digits[index]
								}
							}
						}
					}
			}
		}

		val PinBouncerViewModelClass = Reflection.findClassIfExists(
			"com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel",
			PRParam.classLoader,
		)
		if (PinBouncerViewModelClass != null) {
			try {
				ReflectedClass.of(PinBouncerViewModelClass)
					.before("onActivated")
					.run {
						if (!shufflePinEnabled) return@run
						synchronized(digits) {
							Collections.shuffle(digits)
						}
					}
			} catch (ignored: Throwable) {
			}
		}
	}
}
