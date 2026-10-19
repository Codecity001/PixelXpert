package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.View
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Unlocks the keyguard without a full credential check once the module has
 * learned how long the user's secret is.
 *
 * Every successful credential check records the entered length; from then on,
 * typing a secret of exactly that length (and getting longer each time, so a
 * prefix cannot fire it) has the controller verify the credential on a
 * background thread and, if it matches, dismiss the security view straight
 * away. On builds without `isAutoPinConfirmEnabled` the same learning runs
 * through the older `KeyguardAbsKeyInputViewController` path instead.
 *
 * `WakeUpToSecurityInput` additionally pulls up the primary bouncer whenever
 * the device wakes while the lock screen is dismissible.
 */
@SystemUIModPack
class EasyUnlock(context: Context) : XposedModPack(context) {

	companion object {
		private var WakeUpToSecurityInput = false
	}

	private var expectedPassLen = -1
	private var easyUnlockEnabled = false

	private var lastPassLen = 0

	override fun onPreferenceUpdated(vararg key: String?) {
		easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false)
		expectedPassLen = Xprefs.getInt("expectedPassLen", -1)
		WakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val StatusBarKeyguardViewManagerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager")
		val LockPatternUtilsClass = ReflectedClass.ofIfPossible("com.android.internal.widget.LockPatternUtils")

		StatusBarKeyguardViewManagerClass
			.before("onDozingChanged")
			.run { param ->
				//noinspection ConstantValue
				if (WakeUpToSecurityInput && param.args[0]!!.equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")) //waking up
				) {
					callMethod(param.thisObject, "showPrimaryBouncer", /*reason*/"PXAsked", true)
				}
			}

		if (LockPatternUtilsClass.clazz != null) {
			try {
				LockPatternUtilsClass
					.after("isAutoPinConfirmEnabled")
					.run { param ->
						if (easyUnlockEnabled) {
							try {
								val userId = param.args[0] as Int
								val pinLength = callMethod(param.thisObject, "getPinLength", userId) as Int
								param.setResult(pinLength > 0)
							} catch (e: Throwable) {
								param.setResult(false)
							}
						}
					}
			} catch (ignored: Throwable) {
				// Fallback for older versions if isAutoPinConfirmEnabled doesn't exist
				legacyEasyUnlock()
			}
		} else {
			legacyEasyUnlock()
		}
	}

	private fun legacyEasyUnlock() {
		val KeyguardAbsKeyInputViewControllerClass = ReflectedClass.of("com.android.keyguard.KeyguardAbsKeyInputViewController")
		val LockscreenCredentialClass = ReflectedClass.of("com.android.internal.widget.LockscreenCredential")

		KeyguardAbsKeyInputViewControllerClass
			.after("onUserInput")
			.run { param ->
				if (!easyUnlockEnabled) return@run

				val passwordLen = callMethod(getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText"), "length") as Int

				if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
					Thread {
						try { //don't crash systemUI if failed
							var userId: Int
							try { //14 QPR3 beta 2.1
								userId = callMethod(
									getObjectField(
										getObjectField(param.thisObject, "mKeyguardUpdateMonitor"),
										"mSelectedUserInteractor",
									),
									"getSelectedUserId",
								) as Int
							} catch (ignored: Throwable) { //14 QPR3 beta 2 and older
								userId = getObjectField(getObjectField(param.thisObject, "mKeyguardUpdateMonitor"), "sCurrentUser") as Int
							}

							val methodName = if (param.thisObject!!.javaClass.name.contains("Password")) "createPassword" else "createPin"

							val password = LockscreenCredentialClass.callStaticMethod(
								methodName,
								getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText").toString(),
							)

							val verificationResult = callMethod(
								getObjectField(param.thisObject, "mLockPatternUtils"),
								"checkCredential",
								password,
								userId,
								null, /* callback */
							)

							var accepted: Boolean

							try { //16qpr3
								accepted = callMethod(verificationResult, "isMatched") as Boolean
							} catch (ignored: Throwable) //older
							{
								accepted = verificationResult as Boolean
							}

							if (accepted) {
								val mView = getObjectField(param.thisObject, "mView") as View
								val finalUserId = userId
								mView.post {
									try { //13 QPR3
										callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, getObjectField(param.thisObject, "mSecurityMode"))
									} catch (ignored: Throwable) {
									}
								}
							}
						} catch (ignored: Throwable) {
						}
					}.start()
				}
				lastPassLen = passwordLen
			}

		KeyguardAbsKeyInputViewControllerClass
			.after("onPasswordChecked")
			.run { param ->
				if (!easyUnlockEnabled) return@run

				val successful = param.args[1] as Boolean

				if (successful) {
					expectedPassLen = lastPassLen
					Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply()
				}
			}
	}
}
