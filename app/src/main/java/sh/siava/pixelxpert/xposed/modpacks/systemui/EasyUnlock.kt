package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.concurrent.thread

@SystemUIModPack
class EasyUnlock(context: Context) : XposedModPack(context) {

    private var expectedPassLen = -1
    private var easyUnlockEnabled = false
    private var lastPassLen = 0

    companion object {
        private var wakeUpToSecurityInput = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false)
        expectedPassLen = Xprefs.getInt("expectedPassLen", -1)
        wakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val statusBarKeyguardViewManagerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager")
        val lockPatternUtilsClass = ReflectedClass.ofIfPossible("com.android.internal.widget.LockPatternUtils")

        statusBarKeyguardViewManagerClass
            .before("onDozingChanged")
            .run { runParam ->
                if (wakeUpToSecurityInput && runParam.args[0] == false) {
                    val keyguardStateController = getObjectField(runParam.thisObject, "mKeyguardStateController")
                    val canDismiss = keyguardStateController?.let { getBooleanField(it, "mCanDismissLockScreen") } ?: false
                    if (!canDismiss) {
                        callMethod(runParam.thisObject, "showPrimaryBouncer", "PXAsked", true)
                    }
                }
            }

        if (lockPatternUtilsClass?.clazz != null) {
            try {
                lockPatternUtilsClass
                    .after("isAutoPinConfirmEnabled")
                    .run { runParam ->
                        if (easyUnlockEnabled) {
                            try {
                                val userId = runParam.args.getOrNull(0) as? Int ?: return@run
                                val pinLength = callMethod<Int>(runParam.thisObject, "getPinLength", userId) ?: return@run
                                runParam.result = pinLength > 0
                            } catch (_: Throwable) {
                                runParam.result = false
                            }
                        }
                    }
            } catch (_: Throwable) {
                legacyEasyUnlock()
            }
        } else {
            legacyEasyUnlock()
        }
    }

    private fun legacyEasyUnlock() {
        val keyguardAbsKeyInputViewControllerClass = ReflectedClass.of("com.android.keyguard.KeyguardAbsKeyInputViewController")
        val lockscreenCredentialClass = ReflectedClass.of("com.android.internal.widget.LockscreenCredential")

        keyguardAbsKeyInputViewControllerClass
            .after("onUserInput")
            .run { runParam ->
                if (!easyUnlockEnabled) return@run

                val passwordEntry = getObjectField(runParam.thisObject, "mPasswordEntry") ?: return@run
                val mText = getObjectField(passwordEntry, "mText") ?: return@run
                val passwordLen = callMethod<Int>(mText, "length") ?: return@run

                if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
                    thread {
                        try {
                            val keyguardUpdateMonitor = getObjectField(runParam.thisObject, "mKeyguardUpdateMonitor") ?: return@thread
                            val userId = try {
                                val selectedUserInteractor = getObjectField(keyguardUpdateMonitor, "mSelectedUserInteractor")
                                callMethod<Int>(selectedUserInteractor, "getSelectedUserId") ?: return@thread
                            } catch (_: Throwable) {
                                getObjectField(keyguardUpdateMonitor, "sCurrentUser") as? Int ?: return@thread
                            }

                            val methodName = if (runParam.thisObject.javaClass.name.contains("Password")) "createPassword" else "createPin"
                            val password = lockscreenCredentialClass.callStaticMethod(methodName, mText.toString())

                            val lockPatternUtils = getObjectField(runParam.thisObject, "mLockPatternUtils")
                            val verificationResult = callMethod(
                                lockPatternUtils,
                                "checkCredential",
                                password,
                                userId,
                                null
                            )

                            val accepted = try {
                                callMethod<Boolean>(verificationResult, "isMatched") == true
                            } catch (_: Throwable) {
                                verificationResult as? Boolean == true
                            }

                            if (accepted) {
                                val view = getObjectField(runParam.thisObject, "mView") as? View
                                view?.post {
                                    try {
                                        val securityCallback = callMethod(runParam.thisObject, "getKeyguardSecurityCallback")
                                        val securityMode = getObjectField(runParam.thisObject, "mSecurityMode")
                                        callMethod(securityCallback, "dismiss", userId, securityMode)
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
                lastPassLen = passwordLen
            }

        keyguardAbsKeyInputViewControllerClass
            .after("onPasswordChecked")
            .run { runParam ->
                if (!easyUnlockEnabled) return@run
                val successful = runParam.args.getOrNull(1) as? Boolean ?: return@run
                if (successful) {
                    expectedPassLen = lastPassLen
                    Xprefs.edit().putInt("expectedPassLen", expectedPassLen).apply()
                }
            }
    }
}
