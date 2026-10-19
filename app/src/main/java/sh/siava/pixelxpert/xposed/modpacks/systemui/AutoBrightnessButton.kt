package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools

/**
 * The adaptive-brightness toggle next to its brightness slider.
 *
 * The button is mounted through `AndroidView` (see [QSBrightnessSlider])
 *
 * The setting lives in `Settings.System.SCREEN_BRIGHTNESS_MODE`.
 * A [ContentObserver] - registered only while the button is attached.
 *
 * Everything drawn on it is a platform drawable ([GradientDrawable],
 * [LayerDrawable], [RippleDrawable]) - no custom `onDraw` to keep in
 * sync with SystemUI's theming; the button themes like a Quick Settings tile.
 */
class AutoBrightnessButton(context: Context) : View(context) {

	/** The `materialColor*` roles this button borrows from the QS tiles. */
	private class Colors(
		val primary: Int,
		val onPrimary: Int,
		val surfaceContainerHigh: Int,
		val onSurface: Int,
	)

	private val shape = GradientDrawable()
	private val rippleMask = GradientDrawable()
	private val layers: LayerDrawable
	private val ripple: RippleDrawable
	private val icon: Drawable?

	private var colors: Colors = resolveColors()

	/** Corner radii: squircle when on, pill when off. */
	private val cornerOn = ResourceTools.dpToPx(context, CORNER_ON_DP).toFloat()
	private val cornerOff = ResourceTools.dpToPx(context, CORNER_OFF_DP).toFloat()

	private var animator: ValueAnimator? = null

	/** Null until first read - first [sync] always applies. */
	private var active: Boolean? = null

	private var fraction = 0f

	private val modeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
		override fun onChange(selfChange: Boolean) {
			sync(animate = true)
		}
	}

	init {
		shape.shape = GradientDrawable.RECTANGLE
		rippleMask.shape = GradientDrawable.RECTANGLE
		rippleMask.setColor(android.graphics.Color.WHITE)

		icon = ResourcesCompat.getDrawable(
			XPLauncher.moduleResources ?: context.resources,
			R.drawable.ic_brightness_auto,
			context.theme,
		)?.mutate()
		if (icon == null) {
		}

		layers = if (icon != null) LayerDrawable(arrayOf(shape, icon)) else LayerDrawable(arrayOf(shape))
		if (icon != null) {
			val inset = (ResourceTools.dpToPx(context, SIZE_DP) - ResourceTools.dpToPx(context, ICON_DP)) / 2
			layers.setLayerInset(1, inset, inset, inset, inset)
		}

		ripple = RippleDrawable(ColorStateList.valueOf(colors.onSurface), layers, rippleMask)
		background = ripple

		isClickable = true
		isFocusable = true
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
		contentDescription = moduleString(R.string.QS_auto_brightness_title) ?: "Adaptive brightness"

		setOnLongClickListener {
			openDisplaySettings()
			true
		}

		minimumWidth = ResourceTools.dpToPx(context, SIZE_DP)
		minimumHeight = minimumWidth

		apply(active = isAutoBrightness(), animate = false)
	}

	// Brings the button in line with the current setting, animating the change.
	fun sync(animate: Boolean) {
		apply(active = isAutoBrightness(), animate = animate)
	}

	override fun performClick(): Boolean {
		super.performClick()

		val target = active != true

		performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

		try {
			Settings.System.putInt(
				context.contentResolver,
				Settings.System.SCREEN_BRIGHTNESS_MODE,
				if (target) {
					Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
				} else {
					Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
				},
			)
		} catch (t: Throwable) {
		}

		// the write is asynchronous, animate a moment later.
		apply(active = target, animate = true)
		return true
	}

	private fun openDisplaySettings() {
		performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

		val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

		try {
			context.startActivity(intent)
		} catch (t: Throwable) {
		}

		context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()

		context.contentResolver.registerContentObserver(
			Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
			false,
			modeObserver,
		)

		sync(animate = false)
	}

	override fun onDetachedFromWindow() {
		animator?.cancel()
		animator = null
		try {
			context.contentResolver.unregisterContentObserver(modeObserver)
		} catch (t: Throwable) {
		}
		super.onDetachedFromWindow()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		colors = resolveColors()
		apply(active = isAutoBrightness(), animate = false, force = true)
	}

	private fun apply(active: Boolean, animate: Boolean, force: Boolean = false) {
		if (!force && this.active == active && animator == null) return

		this.active = active
		val target = if (active) 1f else 0f

		// The ripple never shows during the morph, so it takes the colour the
		// button is heading for rather than one interpolated per frame.
		ripple.setColor(
			ColorStateList.valueOf(
				ColorUtils.setAlphaComponent(if (active) colors.onPrimary else colors.onSurface, RIPPLE_ALPHA),
			),
		)

		stateDescription = moduleString(if (active) R.string.general_on else R.string.general_off)

		if (!animate) {
			animator?.cancel()
			animator = null
			setFraction(target)
			return
		}

		animator?.cancel()
		animator = ValueAnimator.ofFloat(fraction, target).apply {
			duration = MORPH_MILLIS
			interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
			addUpdateListener { setFraction(it.animatedValue as Float) }
			start()
		}
	}

	private fun setFraction(value: Float) {
		fraction = value

		val corner = cornerOff + (cornerOn - cornerOff) * value
		shape.cornerRadius = corner
		rippleMask.cornerRadius = corner

		shape.setColor(ColorUtils.blendARGB(colors.surfaceContainerHigh, colors.primary, value))
		icon?.setTint(ColorUtils.blendARGB(colors.onSurface, colors.onPrimary, value))

		invalidate()
	}

	private fun isAutoBrightness(): Boolean = try {
		Settings.System.getInt(
			context.contentResolver,
			Settings.System.SCREEN_BRIGHTNESS_MODE,
			Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
		) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
	} catch (t: Throwable) {
		Logger.log("QSBrightnessSlider: failed to read SCREEN_BRIGHTNESS_MODE", t)
		false
	}

	// The four colours SystemUI defines for every build.
	private fun resolveColors(): Colors = Colors(
		systemUiColor("materialColorPrimary", FALLBACK_PRIMARY),
		systemUiColor("materialColorOnPrimary", FALLBACK_ON_PRIMARY),
		systemUiColor("materialColorSurfaceContainerHigh", FALLBACK_SURFACE_CONTAINER_HIGH),
		systemUiColor("materialColorOnSurface", FALLBACK_ON_SURFACE),
	)

	private fun systemUiColor(name: String, fallback: Int): Int {
		val id = context.resources.getIdentifier(name, "color", Constants.SYSTEM_UI_PACKAGE)
		if (id == 0) {
			Logger.log("QSBrightnessSlider: $name missing from SystemUI, using the fallback")
			return fallback
		}
		return try {
			context.getColor(id)
		} catch (t: Throwable) {
			fallback
		}
	}

	private fun moduleString(id: Int): String? = try {
		XPLauncher.moduleResources?.getString(id)
	} catch (t: Throwable) {
		null
	}

	private companion object {
		const val SIZE_DP = 44
		const val ICON_DP = 24
		const val CORNER_ON_DP = 12
		const val CORNER_OFF_DP = 22
		const val MORPH_MILLIS = 200L
		const val RIPPLE_ALPHA = 0x1F

		// Only reachable if no SystemUI colours defined
		const val FALLBACK_PRIMARY = 0xFF6750A4.toInt()
		const val FALLBACK_ON_PRIMARY = 0xFFFFFFFF.toInt()
		const val FALLBACK_SURFACE_CONTAINER_HIGH = 0xFFECE6F0.toInt()
		const val FALLBACK_ON_SURFACE = 0xFF1D1B20.toInt()
	}
}
