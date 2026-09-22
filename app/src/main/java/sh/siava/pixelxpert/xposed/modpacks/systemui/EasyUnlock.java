package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.view.View;

import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class EasyUnlock extends XposedModPack {
	private int expectedPassLen = -1;
	private boolean easyUnlockEnabled = false;

	private int lastPassLen = 0;
	private static boolean WakeUpToSecurityInput = false;

	public EasyUnlock(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false);
		expectedPassLen = Xprefs.getInt("expectedPassLen", -1);
		WakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		// 1. Legacy View-based PIN bouncer (Android 14 - 17 pre-QPR2)
		ReflectedClass KeyguardAbsKeyInputViewControllerClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardAbsKeyInputViewController");
		ReflectedClass LockscreenCredentialClass = ReflectedClass.ofIfPossible("com.android.internal.widget.LockscreenCredential");
		ReflectedClass StatusBarKeyguardViewManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");

		// 2. Compose-based PIN bouncer (Android 17 QPR2+)
		ReflectedClass PinBouncerViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel");
		ReflectedClass AuthMethodBouncerViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.bouncer.ui.viewmodel.AuthMethodBouncerViewModel");
		ReflectedClass AuthenticationInteractorClass = ReflectedClass.ofIfPossible("com.android.systemui.authentication.domain.interactor.AuthenticationInteractor");

		if (AuthenticationInteractorClass.getClazz() != null) {
			AuthenticationInteractorClass
					.before("authenticate")
					.run(param -> {
						if (!easyUnlockEnabled) return;
						if (param.args != null && param.args.length > 0 && param.args[0] instanceof List) {
							lastPassLen = ((List<?>) param.args[0]).size();
						}
					});

			AuthenticationInteractorClass
					.after("notifyOnAuthResultListeners")
					.run(param -> {
						if (!easyUnlockEnabled) return;
						if (param.args != null && param.args.length > 0 && param.args[0] != null) {
							try {
								boolean isSuccessful = getBooleanField(param.args[0], "isSuccessful");
								if (isSuccessful && lastPassLen > 0) {
									expectedPassLen = lastPassLen;
									Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
								}
							} catch (Throwable ignored) {}
						}
					});
		}

		if (StatusBarKeyguardViewManagerClass.getClazz() != null) {
			StatusBarKeyguardViewManagerClass
					.before("onDozingChanged")
					.run(param -> {
						//noinspection ConstantValue
						if(WakeUpToSecurityInput && param.args[0].equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")))//waking up
						{
							try {
								callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked", true);
							} catch (Throwable ignored) {
								try {
									callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked");
								} catch (Throwable ignored2) {}
							}
						}
					});
		}

		if (PinBouncerViewModelClass.getClazz() != null) {
			PinBouncerViewModelClass
					.before("onActivated")
					.run(param -> {
						if (!easyUnlockEnabled) return;
						seedHintedPinLength(param.thisObject);
					});

			PinBouncerViewModelClass
					.after("onPinButtonClicked")
					.run(param -> {
						if (!easyUnlockEnabled) return;

						seedHintedPinLength(param.thisObject);

						int passwordLen = (int) callMethod(param.thisObject, "getEnteredPinLength");

						if (expectedPassLen > 0 && passwordLen == expectedPassLen && passwordLen > lastPassLen) {
							try {
								Class<?> authVmClass = AuthMethodBouncerViewModelClass.getClazz() != null
										? AuthMethodBouncerViewModelClass.getClazz()
										: param.thisObject.getClass().getSuperclass();
								try {
									callStaticMethod(authVmClass, "tryAuthenticate$default", param.thisObject, null, false, 1);
								} catch (Throwable t1) {
									try {
										callStaticMethod(authVmClass, "tryAuthenticate$default", param.thisObject, null, false, 3);
									} catch (Throwable t2) {
										callStaticMethod(authVmClass, "tryAuthenticate$default", param.thisObject, callMethod(param.thisObject, "getInput"), false, 1);
									}
								}
							} catch (Throwable ignored) {}
						}
						lastPassLen = passwordLen;
					});

			PinBouncerViewModelClass
					.after("clearInput")
					.run(param -> {
						lastPassLen = 0;
					});

			PinBouncerViewModelClass
					.after("onBackspaceButtonClicked")
					.run(param -> {
						if (!easyUnlockEnabled) return;
						try {
							lastPassLen = (int) callMethod(param.thisObject, "getEnteredPinLength");
						} catch (Throwable ignored) {}
					});

			if (StatusBarKeyguardViewManagerClass.getClazz() != null) {
				StatusBarKeyguardViewManagerClass
						.after("notifyKeyguardAuthenticated")
						.run(param -> {
							if (!easyUnlockEnabled) return;
							if (param.args != null && param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
								if (lastPassLen > 0) {
									expectedPassLen = lastPassLen;
									Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
								}
							}
						});
			}
		}

		if (KeyguardAbsKeyInputViewControllerClass.getClazz() != null) {
			KeyguardAbsKeyInputViewControllerClass
					.after("onUserInput")
					.run(param -> {
						if (!easyUnlockEnabled) return;

						int passwordLen = (int) callMethod(getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText"), "length");

						if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
							new Thread(() -> {
								try { //don't crash systemUI if failed
									int userId;
									try { //14 QPR3 beta 2.1
										userId = (int) callMethod(
												getObjectField(
														getObjectField(param.thisObject, "mKeyguardUpdateMonitor"),
														"mSelectedUserInteractor")
												, "getSelectedUserId");
									}
									catch (Throwable ignored)
									{ //14 QPR3 beta 2 and older
										userId = (int) getObjectField(getObjectField(param.thisObject, "mKeyguardUpdateMonitor"), "sCurrentUser");
									}

									String methodName = param.thisObject.getClass().getName().contains("Password") ? "createPassword" : "createPin";

									Object password = LockscreenCredentialClass.callStaticMethod(methodName, getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText").toString());

									Object verificationResult = callMethod(
											getObjectField(param.thisObject, "mLockPatternUtils"),
											"checkCredential",
											password,
											userId,
											null /* callback */);

									boolean accepted;

									try{ //16qpr3
										accepted = (boolean) callMethod(verificationResult, "isMatched");
									}
									catch (Throwable ignored) //older
									{
										accepted = (boolean) verificationResult;
									}

									if (accepted) {
										View mView = (View) getObjectField(param.thisObject, "mView");
										int finalUserId = userId;
										mView.post(() -> {
											try { //13 QPR3
												callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, getObjectField(param.thisObject, "mSecurityMode"));
											} catch (Throwable ignored) {}
										});
									}
								} catch (Throwable ignored){}
							}).start();
						}
						lastPassLen = passwordLen;
					});

			KeyguardAbsKeyInputViewControllerClass
					.after("onPasswordChecked")
					.run(param -> {
						if (!easyUnlockEnabled) return;

						boolean successful = (boolean) param.args[1];

						if (successful) {
							expectedPassLen = lastPassLen;
							Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
						}
					});
		}
	}

	private void seedHintedPinLength(Object pinBouncerVm) {
		if (expectedPassLen > 0) return;
		try {
			Object flow = null;
			try {
				flow = callMethod(pinBouncerVm, "getHintedPinLength");
			} catch (Throwable t) {
				flow = getObjectField(pinBouncerVm, "hintedPinLength");
			}
			if (flow != null) {
				Object val = callMethod(flow, "getValue");
				if (val instanceof Integer) {
					int len = (Integer) val;
					if (len > 0) {
						expectedPassLen = len;
						Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply();
					}
				}
			}
		} catch (Throwable ignored) {}
	}
}