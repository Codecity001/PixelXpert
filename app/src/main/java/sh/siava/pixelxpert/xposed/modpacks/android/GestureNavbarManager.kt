package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import de.robv.android.xposed.XposedHelpers.callMethod
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Framework-side counterpart of the SystemUI `GestureNavbarManager`: gesture
 * navigation settings whose effect lives in `system_server` belong here.
 *
 * Currently only the "Keyboard space" toggle, which takes the row the IME
 * draws under its keys away.
 *
 * With `config_imeDrawsImeNavBar` on (gesture navigation does exactly that),
 * the framework builds an `InputMethodService` navigation bar inside the IME
 * process: a back button that dismisses the keyboard plus the keyboard
 * switcher, sized from `input_method_navigation_bar_height`. That row is
 * drawn by the IME app, not by SystemUI or the launcher, so the only part of
 * it the system server owns is the flag set it hands over through
 * `IInputMethod.onNavButtonFlagsChanged`.
 *
 * [InputMethodManagerService#getInputMethodNavButtonFlagsLocked] composes that
 * set: bit 1 = "the IME draws the navigation bar", bit 2 = "show the IME
 * switcher", bit 4 = "the IME switcher belongs in the navigation bar". The
 * after-hook drops bit 1 and bit 4 while the preference is on, which makes the
 * IME uninstall the whole row - back chevron, switcher and its reserved space
 * all go with it - and keeps the switcher out of the system navigation bar in
 * 3-button mode as well.
 *
 */
@FrameworkModPack
class GestureNavbarManager(context: Context) : XposedModPack(context) {

	companion object {
		private const val PREF_HIDE_BUTTONS = "KeyboardSpaceHideButtons"

		/** `NAV_BUTTON_FLAG_IME_DRAWS_NAV_BAR` in `InputMethodManagerService`. */
		private const val FLAG_IME_DRAWS_NAV_BAR = 1

		/** `NAV_BUTTON_FLAG_IME_SWITCHER_IN_NAV_BAR` in `InputMethodManagerService`. */
		private const val FLAG_IME_SWITCHER_IN_NAV_BAR = 4

		private const val HIDDEN_FLAGS = FLAG_IME_DRAWS_NAV_BAR or FLAG_IME_SWITCHER_IN_NAV_BAR

		private var hideButtons = false

		private var service: Any? = null
		private var lastUserData: Any? = null
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		val enabled = Xprefs.getBoolean(PREF_HIDE_BUTTONS, false)
		if (enabled == hideButtons) return

		hideButtons = enabled
		resendFlags()
	}

	/**
	 * Makes the service recompute and re-send the flag set for the user the
	 * flags were last built for, so toggling applies without waiting for the
	 * IME to rebind.
	 */
	private fun resendFlags() {
		val inputMethodService = service ?: return
		val userData = lastUserData ?: return

		try {
			callMethod(
				inputMethodService,
				"sendOnNavButtonFlagsChangedLocked",
				userData,
			)
		} catch (ignored: Throwable) {
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			ReflectedClass.of("com.android.server.inputmethod.InputMethodManagerService")
				.after("getInputMethodNavButtonFlagsLocked")
				.run { param ->
					service = param.thisObject
					lastUserData = param.args[0]

					if (hideButtons) {
						val flags = param.getResult() as Int
						param.setResult(flags and HIDDEN_FLAGS.inv())
					}
				}
		} catch (ignored: Throwable) {
		}
	}
}
