package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools
import java.lang.reflect.Method

/**
 * Cursor control keys while a keyboard is open.
 *
 * With gesture navigation the launcher owns the bottom strip and the IME draws
 * its own back/switcher row, so SystemUI has neither a `NavigationBarView` nor
 * a window to attach extra keys to. The keys therefore live in two small
 * SystemUI-owned windows of their own, each holding the same `KeyButtonView`
 * the navigation bar uses. `sendEvent` on that view does the privileged input
 * injection, `KeyButtonDrawable` gives the glyph the IME row's white colour and
 * shadow, and `TYPE_NAVIGATION_BAR_PANEL` ranks them above the IME.
 *
 * They sit one IME key-width in from each edge, which is the gap between the
 * IME's own back and language buttons, and follow the IME status that
 * `system_server` pushes to `CommandQueue`.
 *
 * The status pushed while SystemUI starts is delivered straight to the
 * `CommandQueue$Callbacks` listener, not through `CommandQueue`, so a SystemUI
 * restart with the keyboard already open keeps the keys hidden until the IME
 * is toggled. Every later status change is seen here.
 */
@SystemUIModPack
class CursorControlKeys(context: Context) : XposedModPack(context) {

	private companion object {
		const val PREF_KEY = "CursorControlKeys"

		/** `CommandQueue.setImeWindowStatus(displayId, visibility, ...)`. */
		const val COMMAND_QUEUE_CLASS = "com.android.systemui.statusbar.CommandQueue"

		/** `InputMethodService.IME_VISIBLE`. */
		const val IME_VISIBLE_FLAG = 2

		const val KEY_BUTTON_CLASS =
			"com.android.systemui.navigationbar.views.buttons.KeyButtonView"
		const val KEY_BUTTON_DRAWABLE_CLASS =
			"com.android.systemui.navigationbar.views.buttons.KeyButtonDrawable"

		const val NAVIGATION_MODE_KEY = "navigation_mode"
		const val GESTURAL_NAVIGATION_MODE = 2

		/**
		 * `WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL`. The constant
		 * is @hide; the value ranks above `TYPE_INPUT_METHOD` and the launcher
		 * taskbar's `TYPE_NAVIGATION_BAR`.
		 */
		const val TYPE_NAVIGATION_BAR_PANEL = 2024

		const val REPEAT_START_DELAY_MS = 400L
		const val REPEAT_INTERVAL_MS = 60L

		val KEYS = listOf(
			KeySpec(
				keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
				icon = "ic_chevron_start",
				gravity = Gravity.START,
				descriptionRes = R.string.cursor_left_desc,
				title = "PixelXpert cursor left",
			),
			KeySpec(
				keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
				icon = "ic_chevron_end",
				gravity = Gravity.END,
				descriptionRes = R.string.cursor_right_desc,
				title = "PixelXpert cursor right",
			),
		)
	}

	private data class KeySpec(
		val keyCode: Int,
		val icon: String,
		val gravity: Int,
		val descriptionRes: Int,
		val title: String,
	)

	private class KeyButton(val spec: KeySpec, val view: View)

	/**
	 * The SystemUI classes the keys are made of, resolved once per process.
	 *
	 * `KeyButtonView.sendEvent` is kept as a [java.lang.reflect.Method] because
	 * it is also what long-press repetition calls. Everything else on the view
	 * is reached through the normal `ImageView` API.
	 */
	private class KeyInternals(context: Context) {
		private val loader = context.classLoader
		private val keyClass = Class.forName(KEY_BUTTON_CLASS, false, loader)

		val constructor = keyClass.getConstructor(Context::class.java, AttributeSet::class.java)
		val codeField = keyClass.getDeclaredField("mCode").apply { isAccessible = true }
		val sendEvent = keyClass.getMethod(
			"sendEvent",
			Int::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
		)
		val createDrawable = Class.forName(KEY_BUTTON_DRAWABLE_CLASS, false, loader).getMethod(
			"create",
			Context::class.java,
			Int::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			Int::class.javaPrimitiveType,
			Boolean::class.javaPrimitiveType,
		)
	}

	private val handler = Handler(Looper.getMainLooper())

	/** Separate from [handler] so hiding the keys cannot drop a pending update. */
	private val repeatHandler = Handler(Looper.getMainLooper())

	private val internals by lazy {
		runCatching { KeyInternals(mContext) }.getOrNull()
	}

	@Volatile
	private var enabled = false

	@Volatile
	private var imeVisible = false

	private var keys: List<KeyButton>? = null
	private var attached = false

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean(PREF_KEY, false)
		handler.post {
			hideKeys()
			keys = null
			update()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.of(COMMAND_QUEUE_CLASS)
			.before("setImeWindowStatus")
			.id("pxn-cursor-ime-status")
			.run { param ->
				// (displayId, visibility, backDisposition, showSwitcher)
				if (param.args.getOrNull(0) != 0) return@run

				val visible = (param.args.getOrNull(1) as? Int ?: return@run) and IME_VISIBLE_FLAG != 0
				if (visible != imeVisible) {
					imeVisible = visible
					update()
				}
			}
	}

	private fun update() {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			handler.post(::update)
			return
		}

		if (enabled && imeVisible && isGesturalNavigation()) {
			showKeys()
		} else {
			hideKeys()
		}
	}

	private fun showKeys() {
		if (attached) return

		val keyButtons = keys ?: createKeys()?.also { keys = it } ?: return
		val windowManager = windowManager() ?: return

		try {
			keyButtons.forEach { key ->
				windowManager.addView(key.view, layoutParams(key.spec))
			}
			attached = true
		} catch (t: Throwable) {
			keyButtons.forEach { runCatching { windowManager.removeViewImmediate(it.view) } }
		}
	}

	private fun hideKeys() {
		// A pending repeat must not keep injecting after the window is gone.
		repeatHandler.removeCallbacksAndMessages(null)
		keys?.forEach { it.view.isPressed = false }

		if (!attached) return
		attached = false

		val windowManager = windowManager() ?: return
		keys?.forEach { key ->
			if (key.view.isAttachedToWindow) {
				runCatching { windowManager.removeViewImmediate(key.view) }
			}
		}
	}

	private fun createKeys(): List<KeyButton>? {
		val internals = internals ?: return null

		val created = KEYS.map { spec ->
			KeyButton(spec, createKey(internals, spec) ?: return null)
		}

		return created
	}

	private fun createKey(internals: KeyInternals, spec: KeySpec): View? {
		val iconRes = mContext.resources.getIdentifier(spec.icon, "drawable", "android")
			.takeIf { it != 0 }
			?: mContext.resources.getIdentifier("${spec.icon}_rounded", "drawable", mContext.packageName)
		if (iconRes == 0) return null

		return runCatching {
			val key = internals.constructor.newInstance(mContext, null) as ImageView
			internals.codeField.setInt(key, spec.keyCode)

			val isAccent = Xprefs.getBoolean("navPillColorAccent", false)
			val lightColor = if (isAccent) {
				mContext.getColor(android.R.color.system_accent1_200)
			} else {
				pillColor("light", android.R.color.white)
			}
			val darkColor = if (isAccent) {
				mContext.getColor(android.R.color.system_accent1_600)
			} else {
				pillColor("dark", android.R.color.black)
			}

			val drawable = internals.createDrawable.invoke(
				null,
				mContext,
				lightColor,
				darkColor,
				iconRes,
				true,
			) as Drawable

			key.setImageDrawable(drawable)
			key.scaleType = ImageView.ScaleType.CENTER
			key.isClickable = true
			key.isHapticFeedbackEnabled = true
			key.contentDescription = XPLauncher.moduleResources?.getString(spec.descriptionRes)
			key.setOnTouchListener(RepeatTouchListener(key, spec.keyCode, internals.sendEvent))

			key
		}.getOrNull()
	}

	private fun pillColor(type: String, fallback: Int): Int {
		val id = mContext.resources.getIdentifier("navigation_bar_home_handle_${type}_color", "color", mContext.packageName)
		return mContext.getColor(if (id != 0) id else fallback)
	}

	private fun layoutParams(spec: KeySpec): WindowManager.LayoutParams {
		val keyWidth = keyWidthPx()

		return WindowManager.LayoutParams(
			keyWidth,
			rowHeightPx(),
			TYPE_NAVIGATION_BAR_PANEL,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.BOTTOM or spec.gravity
			x = keyWidth
			title = spec.title
			setFitInsetsTypes(0)
			layoutInDisplayCutoutMode =
				WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
		}
	}

	/**
	 * One IME button width in from the edge: the IME navigation row puts its
	 * back key and language switcher in slots of exactly that size.
	 */
	private fun keyWidthPx(): Int = frameworkDimension(
		"input_method_navigation_key_width",
		70,
	)

	private fun rowHeightPx(): Int = frameworkDimension(
		"input_method_navigation_bar_height",
		48,
	)

	private fun frameworkDimension(name: String, fallbackDp: Int): Int {
		val id = mContext.resources.getIdentifier(name, "dimen", "android")
		if (id != 0) {
			runCatching { return mContext.resources.getDimensionPixelSize(id) }
		}

		return ResourceTools.dpToPx(mContext, fallbackDp)
	}

	private fun windowManager(): WindowManager? = mContext.getSystemService(WindowManager::class.java)

	/**
	 * The keys only make sense where the IME draws the navigation row. In
	 * three-button navigation the launcher owns that strip and already puts
	 * its own keys there.
	 */
	private fun isGesturalNavigation(): Boolean = runCatching {
		Settings.Secure.getInt(
			mContext.contentResolver,
			NAVIGATION_MODE_KEY,
			0,
		) == GESTURAL_NAVIGATION_MODE
	}.getOrDefault(false)

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		outState["imeVisible"] = imeVisible
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		imeVisible = savedState["imeVisible"] as? Boolean ?: false
		update()
	}

	override fun onUnloaded() {
		handler.post { hideKeys() }
	}

	/**
	 * Sends a DPAD event per tap and repeats it while the key is held.
	 *
	 * The injection itself is `KeyButtonView.sendEvent`, which is also what the
	 * real navigation bar calls on a tap.
	 */
	private inner class RepeatTouchListener(
		private val key: View,
		private val keyCode: Int,
		private val sendEvent: Method,
	) : View.OnTouchListener {
		private val repeat = object : Runnable {
			override fun run() {
				// End the previous press and start the next one, the same way a
				// held hardware key keeps generating events.
				send(KeyEvent.ACTION_UP)
				send(KeyEvent.ACTION_DOWN)
				repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
			}
		}

		override fun onTouch(view: View, event: MotionEvent): Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					view.isPressed = true
					send(KeyEvent.ACTION_DOWN)
					repeatHandler.postDelayed(repeat, REPEAT_START_DELAY_MS)
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					view.isPressed = false
					repeatHandler.removeCallbacks(repeat)
					send(KeyEvent.ACTION_UP)
					if (event.actionMasked == MotionEvent.ACTION_UP) {
						view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
					}
				}
			}

			return true
		}

		private fun send(action: Int) {
			if (action == KeyEvent.ACTION_DOWN) {
				key.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
			}
			runCatching { sendEvent.invoke(key, action, 0) }
		}
	}
}
