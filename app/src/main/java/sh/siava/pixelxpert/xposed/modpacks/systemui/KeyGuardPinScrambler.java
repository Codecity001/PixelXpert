package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyGuardPinScrambler extends XposedModPack {
	private static boolean shufflePinEnabled = false;

	public KeyGuardPinScrambler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		shufflePinEnabled = Xprefs.getBoolean("shufflePinEnabled", false);
		if (shufflePinEnabled) {
			synchronized (digits) {
				Collections.shuffle(digits);
			}
		}
	}

	final List<Integer> digits = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 0);

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		// 1. View-based PIN bouncer
		try {
			ReflectedClass KeyguardPinBasedInputViewClass = ReflectedClass.of("com.android.keyguard.KeyguardPinBasedInputView");

			ReflectionConsumer pinShuffleHook = param -> {
				if (!shufflePinEnabled) return;

				synchronized (digits) {
					Collections.shuffle(digits);

					Object[] mButtons = (Object[]) getObjectField(param.thisObject, "mButtons");

					for (Object button : mButtons) {
						int mDigit = getIntField(button, "mDigit");
						setObjectField(button, "mDigit", digits.get(mDigit));

						callMethod(
								getObjectField(button, "mDigitText"),
								"setText",
								Integer.toString(digits.get(mDigit)));
					}
				}
			};

			KeyguardPinBasedInputViewClass.after("onFinishInflate").run(pinShuffleHook);
			KeyguardPinBasedInputViewClass.after("resetPasswordText").run(pinShuffleHook);
		} catch (Throwable ignored) {}

		// 2. Compose-based PIN bouncer
		Class<?> PinBouncerKtClass = XposedHelpers.findClassIfExists("com.android.systemui.bouncer.ui.composable.PinBouncerKt", PRParam.getClassLoader());
		if (PinBouncerKtClass != null) {
			for (Method method : PinBouncerKtClass.getDeclaredMethods()) {
				if (method.getName().startsWith("DigitButton-")) {
					XposedBridge.hookMethod(method, new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							if (!shufflePinEnabled) return;

							if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
								int original = (int) param.args[0];

								// Fix R8 bytecode hardcoding: button 0's onClick lambda usually has $r8$classId == 1
								// which explicitly bypasses parameter injection. We reset it to 0.
								if (original == 0 && param.args.length > 2 && param.args[2] != null) {
									try {
										Field f = findFieldIfExists(param.args[2].getClass(), "$r8$classId");
										if (f != null) {
											if (f.getType() == byte.class) {
												f.setByte(param.args[2], (byte) 0);
											} else if (f.getType() == int.class) {
												f.setInt(param.args[2], 0);
											}
										}
									} catch (Throwable ignored) {}
								}

								// Handle Jetpack Compose self-recomposition (e.g. ripple effects)
								// The last argument is the $changed bitmask. Bit 0 indicates self-recomposition.
								if (param.args.length >= 10 && param.args[param.args.length - 1] instanceof Integer) {
									int changed = (int) param.args[param.args.length - 1];
									if ((changed & 1) != 0) {
										// This is a self-recomposition, do not re-scramble or map digits
										return;
									}
									// Clear static bits for digit argument (bits 1..3: 0x0E) so Compose treats it as dynamic
									param.args[param.args.length - 1] = changed & ~0x0E;
								}

								int index = (original == 0) ? 9 : (original - 1);
								synchronized (digits) {
									if (index >= 0 && index < digits.size()) {
										param.args[0] = digits.get(index);
									}
								}
							}
						}
					});
					break;
				}
			}
		}

		Class<?> PinBouncerViewModelClass = XposedHelpers.findClassIfExists("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel", PRParam.getClassLoader());
		if (PinBouncerViewModelClass != null) {
			try {
				XposedBridge.hookAllMethods(PinBouncerViewModelClass, "onActivated", new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						if (!shufflePinEnabled) return;
						synchronized (digits) {
							Collections.shuffle(digits);
						}
					}
				});
			} catch (Throwable ignored) {}
		}
	}
}