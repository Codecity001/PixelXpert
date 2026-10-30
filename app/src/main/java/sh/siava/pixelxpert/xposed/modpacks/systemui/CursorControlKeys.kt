package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
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

/**
 * Cursor control keys in the gesture navigation bar while the keyboard is open.
 *
 * Positions two lightweight overlay buttons above the IME row in gestural mode,
 * injecting DPAD left/right key events on tap and repeat.
 */
@SystemUIModPack
class CursorControlKeys(context: Context) : XposedModPack(context) {

	private companion object {
		const val PREF_KEY = "CursorControlKeys"
		const val TYPE_NAVIGATION_BAR_PANEL = 2024
		const val GESTURAL_NAVIGATION_MODE = 2
	}

	private val handler = Handler(Looper.getMainLooper())
	private val inputManager by lazy { mContext.getSystemService(InputManager::class.java) }
	private val injectInputEventMethod by lazy {
		InputManager::class.java.getMethod(
			"injectInputEvent",
			InputEvent::class.java,
			Int::class.javaPrimitiveType,
		)
	}

	@Volatile
	private var enabled = false

	@Volatile
	private var imeVisible = false

	private var leftView: View? = null
	private var rightView: View? = null
	private var attached = false

	override fun onPreferenceUpdated(vararg key: String?) {
		enabled = Xprefs.getBoolean(PREF_KEY, false)
		handler.post {
			hideKeys()
			leftView = null
			rightView = null
			update()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		ReflectedClass.of("com.android.systemui.statusbar.CommandQueue")
			.before("setImeWindowStatus")
			.id("pxn-cursor-ime-status")
			.run { param ->
				if (param.args.getOrNull(0) != 0) return@run
				val visible = ((param.args.getOrNull(1) as? Int) ?: return@run) and 2 != 0
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
		val wm = mContext.getSystemService(WindowManager::class.java) ?: return
		val left = leftView ?: createKey(KeyEvent.KEYCODE_DPAD_LEFT, "ic_chevron_start").also { leftView = it }
		val right = rightView ?: createKey(KeyEvent.KEYCODE_DPAD_RIGHT, "ic_chevron_end").also { rightView = it }
		runCatching {
			wm.addView(left, layoutParams(Gravity.START))
			wm.addView(right, layoutParams(Gravity.END))
			attached = true
		}.onFailure {
			hideKeys()
		}
	}

	private fun hideKeys() {
		handler.removeCallbacksAndMessages(null)
		leftView?.isPressed = false
		rightView?.isPressed = false
		if (!attached) return
		attached = false
		val wm = mContext.getSystemService(WindowManager::class.java) ?: return
		leftView?.let { if (it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) } }
		rightView?.let { if (it.isAttachedToWindow) runCatching { wm.removeViewImmediate(it) } }
	}

	private fun createKey(keyCode: Int, iconName: String): ImageView {
		val iconRes = mContext.resources.getIdentifier(iconName, "drawable", "android")
			.takeIf { it != 0 }
			?: mContext.resources.getIdentifier("${iconName}_rounded", "drawable", mContext.packageName)

		return ImageView(mContext).apply {
			if (iconRes != 0) setImageResource(iconRes)
			scaleType = ImageView.ScaleType.CENTER
			val isAccent = Xprefs.getBoolean("navPillColorAccent", false)
			imageTintList = ColorStateList.valueOf(
				if (isAccent) mContext.getColor(android.R.color.system_accent1_200) else Color.WHITE,
			)
			val outValue = TypedValue()
			if (mContext.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
				setBackgroundResource(outValue.resourceId)
			}
			contentDescription = XPLauncher.moduleResources?.getString(
				if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) R.string.cursor_left_desc else R.string.cursor_right_desc,
			)
			bindTouchRepeat(this, keyCode)
		}
	}

	private fun bindTouchRepeat(view: View, keyCode: Int) {
		val repeat = object : Runnable {
			override fun run() {
				send(keyCode, KeyEvent.ACTION_UP)
				send(keyCode, KeyEvent.ACTION_DOWN)
				handler.postDelayed(this, 60L)
			}
		}
		view.setOnTouchListener { v, event ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					v.isPressed = true
					v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
					send(keyCode, KeyEvent.ACTION_DOWN)
					handler.postDelayed(repeat, 400L)
				}

				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					v.isPressed = false
					handler.removeCallbacks(repeat)
					send(keyCode, KeyEvent.ACTION_UP)
					if (event.actionMasked == MotionEvent.ACTION_UP) {
						v.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
					}
				}
			}
			true
		}
	}

	private fun send(keyCode: Int, action: Int) {
		val now = SystemClock.uptimeMillis()
		val event = KeyEvent(
			now,
			now,
			action,
			keyCode,
			0,
			0,
			KeyCharacterMap.VIRTUAL_KEYBOARD,
			0,
			KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_SOFT_KEYBOARD,
			InputDevice.SOURCE_KEYBOARD,
		)
		runCatching { injectInputEventMethod.invoke(inputManager, event, 0) }
	}

	private fun layoutParams(gravity: Int): WindowManager.LayoutParams {
		val keyWidth = frameworkDimension("input_method_navigation_key_width", 70)
		val rowHeight = frameworkDimension("input_method_navigation_bar_height", 48)

		return WindowManager.LayoutParams(
			keyWidth,
			rowHeight,
			TYPE_NAVIGATION_BAR_PANEL,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT,
		).apply {
			this.gravity = Gravity.BOTTOM or gravity
			x = keyWidth
			title = if (gravity == Gravity.START) "PixelXpert cursor left" else "PixelXpert cursor right"
			setFitInsetsTypes(0)
			layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
		}
	}

	private fun frameworkDimension(name: String, fallbackDp: Int): Int {
		val id = mContext.resources.getIdentifier(name, "dimen", "android")
		if (id != 0) {
			runCatching { return mContext.resources.getDimensionPixelSize(id) }
		}
		return ResourceTools.dpToPx(mContext, fallbackDp)
	}

	private fun isGesturalNavigation(): Boolean = runCatching {
		Settings.Secure.getInt(mContext.contentResolver, "navigation_mode", 0) == GESTURAL_NAVIGATION_MODE
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
}
