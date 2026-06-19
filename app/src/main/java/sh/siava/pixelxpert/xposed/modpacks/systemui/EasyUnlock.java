package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
//@SystemUIModPack
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
		ReflectedClass KeyguardAbsKeyInputViewControllerClass = ReflectedClass.ofIfPossible("com.android.keyguard.KeyguardAbsKeyInputViewController");
		ReflectedClass LockscreenCredentialClass = ReflectedClass.of("com.android.internal.widget.LockscreenCredential");
		ReflectedClass StatusBarKeyguardViewManagerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");

		ReflectedClass.of("com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel").before("onPinButtonClicked").run(new ReflectedClass.ReflectionConsumer() {
			@Override
			public void run(HookHelper.RunParam param) throws Throwable {
//				log("pin click " + param.getArg(0));
				List i = callMethod(param.thisObject, "getInput");
				String pin = "";
				for (Object o : i)
				{
					pin += o;
				}
				log("pin " + pin + param.getArg(0).toString());
			}
		});



		ReflectedClass d = ReflectedClass.of("com.android.systemui.biometrics.ui.CredentialPasswordView");
		d.after("init").run(new ReflectedClass.ReflectionConsumer() {
			@Override
			public void run(HookHelper.RunParam param) throws Throwable {
				log("init");
				ViewGroup v = param.getThisObject();
				EditText lp = v.findViewById(SystemUtils.idOf("lockPassword"));
				lp.addTextChangedListener(new TextWatcher() {
					@Override
					public void afterTextChanged(Editable s) {
						log("text chanaged to "+ s.toString());
					}

					@Override
					public void beforeTextChanged(CharSequence s, int start, int count, int after) {

					}

					@Override
					public void onTextChanged(CharSequence s, int start, int before, int count) {

					}
				});
			}
		});

		StatusBarKeyguardViewManagerClass
				.before("onDozingChanged")
				.run(param -> {
					//noinspection ConstantValue
					if(WakeUpToSecurityInput && param.args[0].equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")))//waking up
					{
						callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked", true);
					}
				});

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