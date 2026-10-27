package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findFieldIfExists
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer
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

					val mButtons = getObjectField(param.thisObject, "mButtons") as Array<Any?>

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

		val PinBouncerKtClass = XposedHelpers.findClassIfExists(
			"com.android.systemui.bouncer.ui.composable.PinBouncerKt",
			PRParam.classLoader,
		)
		if (PinBouncerKtClass != null) {
			for (method in PinBouncerKtClass.declaredMethods) {
				if (method.name.startsWith("DigitButton-")) {
					XposedBridge.hookMethod(
						method,
						object : XC_MethodHook() {
							override fun beforeHookedMethod(param: MethodHookParam) {
								if (!shufflePinEnabled) return

								if (param.args != null && param.args.size > 2 && param.args[0] is Int) {
									// Only the keyguard bouncer's own pad is shuffled.
									val onClick = param.args[2]
									if (onClick == null || !onClick.javaClass.name.contains("PinBouncerKt")) {
										return
									}

									val original = param.args[0] as Int

									// Fix R8 bytecode hardcoding: button 0's onClick lambda usually has $r8$classId == 1
									// which explicitly bypasses parameter injection. We reset it to 0.
									if (original == 0 && param.args.size > 2 && param.args[2] != null) {
										try {
											val f = findFieldIfExists(param.args[2].javaClass, "\$r8\$classId")
											if (f != null) {
												if (f.type == Byte::class.javaPrimitiveType) {
													f.setByte(param.args[2], 0.toByte())
												} else if (f.type == Int::class.javaPrimitiveType) {
													f.setInt(param.args[2], 0)
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
											return
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
						},
					)
					break
				}
			}
		}

		val PinBouncerViewModelClass = XposedHelpers.findClassIfExists(
			"com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel",
			PRParam.classLoader,
		)
		if (PinBouncerViewModelClass != null) {
			try {
				XposedBridge.hookAllMethods(
					PinBouncerViewModelClass,
					"onActivated",
					object : XC_MethodHook() {
						override fun beforeHookedMethod(param: MethodHookParam) {
							if (!shufflePinEnabled) return
							synchronized(digits) {
								Collections.shuffle(digits)
							}
						}
					},
				)
			} catch (ignored: Throwable) {
			}
		}
	}
}
