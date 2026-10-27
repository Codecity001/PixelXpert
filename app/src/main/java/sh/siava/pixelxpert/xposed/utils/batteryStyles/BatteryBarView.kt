package sh.siava.pixelxpert.xposed.utils.batteryStyles

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.BaseInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import sh.siava.pixelxpert.xposed.modpacks.systemui.BatteryDataProvider
import sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods
import java.util.Locale

/**
 * The thin battery bar strip drawn at the edge of the status bar. A
 * [ShapeDrawable] rectangle lives inside a clipping "mask" frame whose width
 * tracks the current charge level; depending on the settings the paint is a
 * flat threshold colour, a radial gradient spanning all thresholds
 * ("colorful"), or the charging / power-save colour, while a small indicator
 * view slides along the bar whenever the device is charging and the charging
 * animation flag is on.
 *
 * The knobs (threshold levels, colours, animation flags, and the singleton
 * [instance]) are static-like companion state shared by every copy so the
 * status-bar and keyguard bars always agree, and the constructor subscribes to
 * [StatusbarMods] and [BatteryDataProvider] so layout and colours refresh as
 * the clock colour, clock visibility and battery state change.
 */
class BatteryBarView(context: Context) : FrameLayout(context) {
	private val RTL: Boolean
	private val mDrawable = ShapeDrawable()
	private var maskLayout: FrameLayout
	private val chargingIndicatorView: ImageView
	private val chargingIndicatorViewForCenter: ImageView
	private var colorful = false
	private var alphaPct = 100
	private var isCenterBased = false
	private var barHeight = 10
	private val barView: ImageView
	private var onTop = true
	private var onlyWhileCharging = false
	private var mIsEnabled = true
	private var mIsHidden = false
	private val animationHandler = Handler(Looper.getMainLooper())
	private var chargingAnimationRunnable: Runnable? = null

	companion object {
		@SuppressLint("StaticFieldLeak")
		private var instance: BatteryBarView? = null

		private var shadeColors: IntArray? = null
		private var shadeLevels: FloatArray = FloatArray(0)
		private var batteryLevels: List<Float> = listOf(20f, 40f)
		private var batteryColors: IntArray? = intArrayOf(Color.RED, Color.YELLOW)
		private var chargingColor = Color.WHITE
		private var fastChargingColor = Color.WHITE
		private var powerSaveColor = Color.parseColor("#FFBF00")
		private var indicateCharging = false
		private var indicateFastCharging = false
		private var indicatePowerSave = false
		private var transitColors = false
		private var animateCharging = false

		private const val ANIM_DURATION = 1000
		private const val ANIM_DELAY = 2000

		@JvmStatic
		fun setStaticColor(
			batteryLevels: List<Float>,
			batteryColors: IntArray,
			indicateCharging: Boolean,
			chargingColor: Int,
			indicateFastCharging: Boolean,
			fastChargingColor: Int,
			indicatePowerSave: Boolean,
			powerSaveColor: Int,
			transitColors: Boolean,
			animate: Boolean,
		) {
			this.transitColors = transitColors
			this.batteryLevels = batteryLevels
			this.batteryColors = batteryColors
			this.chargingColor = chargingColor
			this.fastChargingColor = fastChargingColor
			this.indicatePowerSave = indicatePowerSave
			this.powerSaveColor = powerSaveColor
			this.indicateCharging = indicateCharging
			this.indicateFastCharging = indicateFastCharging
			this.animateCharging = animate
		}

		@JvmStatic
		fun getInstance(context: Context): BatteryBarView {
			val current = instance
			if (current != null) return current
			return BatteryBarView(context)
		}

		@JvmStatic
		fun getInstance(): BatteryBarView? = instance

		@JvmStatic
		fun hasInstance(): Boolean = instance != null

		private fun refreshShadeColors() {
			if (batteryColors == null || batteryLevels.isEmpty()) return

			shadeColors = IntArray(batteryLevels.size * 2 + 2)
			shadeLevels = FloatArray(shadeColors!!.size)
			val colors = shadeColors!!
			var prev = 0f
			for (i in batteryLevels.indices) {
				val rangeLength = batteryLevels[i] - prev
				shadeLevels[2 * i] = (prev + rangeLength * .3f) / 100
				colors[2 * i] = batteryColors!![i]

				shadeLevels[2 * i + 1] = (batteryLevels[i] - rangeLength * .3f) / 100
				colors[2 * i + 1] = batteryColors!![i]

				prev = batteryLevels[i]
			}

			shadeLevels[shadeLevels.size - 2] =
				(batteryLevels[batteryLevels.size - 1] + (100 - batteryLevels[batteryLevels.size - 1]) * .3f) / 100
			colors[colors.size - 2] = Color.GREEN
			shadeLevels[shadeLevels.size - 1] = 1f
			colors[colors.size - 1] = Color.GREEN
		}
	}

	init {
		instance = this

		chargingIndicatorView = ImageView(context)
		chargingIndicatorViewForCenter = ImageView(context)
		initChargingAnimationView()

		layoutParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		)

		mDrawable.setShape(RectShape())
		setAlphaPct(alphaPct)

		barView = ImageView(context)
		barView.setImageDrawable(mDrawable)

		maskLayout = FrameLayout(context)
		maskLayout.addView(barView)
		maskLayout.setClipChildren(true)

		addView(maskLayout)
		addView(chargingIndicatorView)
		addView(chargingIndicatorViewForCenter)
		setClipChildren(true)

		RTL = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == LAYOUT_DIRECTION_RTL

		StatusbarMods.registerTextColorCallback { refreshLayout() }

		StatusbarMods.registerClockVisibilityCallback { visible -> setVisible(visible) }

		refreshLayout()

		val instance: BatteryBarView = this
		BatteryDataProvider.registerInfoCallback(object : BatteryDataProvider.BatteryInfoCallback {
			override fun onBatteryInfoChanged() {
				instance.post { instance.refreshLayout() }
			}
		})
	}

	fun setOnTop(onTop: Boolean) {
		this.onTop = onTop

		refreshLayout()
	}

	fun setOnlyWhileCharging(state: Boolean) {
		onlyWhileCharging = state
		refreshLayout()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		refreshLayout()
	}

	fun refreshLayout() {
		if (!isAttachedToWindow) return

		refreshVisibility()

		if (barView.visibility == GONE) return
		maskLayout.layoutParams = maskLayoutParams()
		barView.layoutParams = barLayoutParams()
		chargingIndicatorView.layoutParams = charginLayoutParams()
		chargingIndicatorViewForCenter.layoutParams = charginLayoutParams()

		refreshColors(barView.width, barView.height)
		mDrawable.invalidateSelf()
	}

	private fun maskLayoutParams(): FrameLayout.LayoutParams {
		val result = FrameLayout.LayoutParams(
			Math.round(width * BatteryDataProvider.getCurrentLevel() / 100f),
			ViewGroup.LayoutParams.MATCH_PARENT,
		)
		result.gravity = if (isCenterBased) Gravity.CENTER else Gravity.START
		return result
	}

	@Suppress("SpellCheckingInspection")
	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		refreshLayout()
	}

	private fun startChargingAnimation() {
		if (chargingAnimationRunnable == null) {
			val runnable = object : Runnable {
				override fun run() {
					animateChargingIndicator()
					animationHandler.postDelayed(this, ANIM_DELAY.toLong())
				}
			}
			chargingAnimationRunnable = runnable
			animationHandler.post(runnable)
		}
	}

	private fun stopChargingAnimation() {
		// the framework marks Handler.removeCallbacks' argument @NonNull (Kotlin
		// enforces it); this handler only ever holds the animation runnable, so
		// guarding the call is a no-op-equivalent
		chargingAnimationRunnable?.let { animationHandler.removeCallbacks(it) }

		post {
			chargingIndicatorView.visibility = GONE
			chargingIndicatorView.clearAnimation()

			chargingIndicatorViewForCenter.visibility = GONE
			chargingIndicatorViewForCenter.clearAnimation()
		}

		chargingAnimationRunnable = null
	}

	private fun animateChargingIndicator() {
		val screenWidth = Resources.getSystem().displayMetrics.widthPixels

		chargingIndicatorView.post { chargingIndicatorView.visibility = VISIBLE }
		val secondaryVisibility = if (isCenterBased) GONE else VISIBLE
		chargingIndicatorViewForCenter.post { chargingIndicatorViewForCenter.visibility = secondaryVisibility }

		var startX: Float
		var startXCenter: Float
		var endX: Float

		if (RTL) {
			startX = 0f
			startXCenter = width.toFloat()
			endX = (width - Math.round(width * BatteryDataProvider.getCurrentLevel() / 100f)).toFloat()
		} else {
			startX = screenWidth.toFloat()
			startXCenter = 0f
			endX = Math.round(width * BatteryDataProvider.getCurrentLevel() / 100f).toFloat()
		}

		if (isCenterBased) endX = width * (1 - ((100 - BatteryDataProvider.getCurrentLevel()) / 200f))

		val interpolator: BaseInterpolator = AccelerateInterpolator()

		val animation = TranslateAnimation(startX, endX, 0f, 0f)
		animation.setDuration(ANIM_DURATION.toLong())
		animation.setInterpolator(interpolator)
		chargingIndicatorView.startAnimation(animation)

		if (isCenterBased) {
			val animationCenter = TranslateAnimation(startXCenter, width - endX, 0f, 0f)
			animationCenter.setDuration(ANIM_DURATION.toLong())
			animationCenter.setInterpolator(interpolator)
			chargingIndicatorViewForCenter.startAnimation(animationCenter)
		}
	}

	private fun initChargingAnimationView() {
		// the very same LayoutParams instance is handed to both views
		val lp = FrameLayout.LayoutParams(20, barHeight)

		chargingIndicatorView.layoutParams = lp
		chargingIndicatorViewForCenter.layoutParams = lp

		chargingIndicatorView.setBackgroundColor(StatusbarMods.getCurrentClockColor())
		chargingIndicatorViewForCenter.setBackgroundColor(StatusbarMods.getCurrentClockColor())
	}

	override fun setLayoutDirection(direction: Int) {
		super.setLayoutDirection(direction)
	}

	private fun barLayoutParams(): FrameLayout.LayoutParams {
		val result = FrameLayout.LayoutParams(width, barHeight)

		result.gravity = if (isCenterBased) Gravity.CENTER else Gravity.START

		result.gravity = result.gravity or (if (onTop) Gravity.TOP else Gravity.BOTTOM)

		return result
	}

	private fun charginLayoutParams(): FrameLayout.LayoutParams {
		val metrics: DisplayMetrics = context.resources.displayMetrics
		val dp = 4f
		val pixels = (metrics.density * dp + 0.5f).toInt()
		val result = FrameLayout.LayoutParams(pixels, barHeight)

		result.gravity = if (RTL) Gravity.END else Gravity.START
		result.gravity = result.gravity or (if (onTop) Gravity.TOP else Gravity.BOTTOM)

		return result
	}

	fun setBarHeight(height: Int) {
		barHeight = height
		refreshLayout()
	}

	fun setColorful(colorful: Boolean) {
		this.colorful = colorful
		refreshLayout()
	}

	fun refreshColors(lenX: Int, lenY: Int) {
		if (lenX == 0) return // we're not ready yet
		refreshShadeColors()
		val mPaint: Paint = mDrawable.paint
		mPaint.setShader(null)
		mDrawable.setIntrinsicWidth(lenX)
		mDrawable.setIntrinsicHeight(lenY)
		if (BatteryDataProvider.isFastCharging() && indicateFastCharging) { // fast charging color
			chargingIndicatorView.setBackgroundColor(fastChargingColor)
			chargingIndicatorViewForCenter.setBackgroundColor(fastChargingColor)
			mPaint.setColor(fastChargingColor)
		} else if (BatteryDataProvider.isCharging() && indicateCharging) { // normal charging color
			chargingIndicatorView.setBackgroundColor(chargingColor)
			chargingIndicatorViewForCenter.setBackgroundColor(chargingColor)
			mPaint.setColor(chargingColor)
		} else if (BatteryDataProvider.isPowerSaving() && indicatePowerSave) { // power saving color
			mPaint.setColor(powerSaveColor)
		} else if (!colorful || shadeColors == null) { // not charging color
			for (i in batteryLevels.indices) {
				if (BatteryDataProvider.getCurrentLevel() <= batteryLevels[i]) {
					if (transitColors && i > 0) {
						val range = batteryLevels[i] - batteryLevels[i - 1]
						val currentPos = BatteryDataProvider.getCurrentLevel() - batteryLevels[i - 1]
						val ratio = currentPos / range
						mPaint.setColor(ColorUtils.blendARGB(batteryColors!![i - 1], batteryColors!![i], ratio))
					} else {
						mPaint.setColor(batteryColors!![i])
					}
					return
				}
			}
			mPaint.setColor(StatusbarMods.getCurrentClockColor())
		} else { // it's colorful
			val cX = if (isCenterBased) lenX / 2f else (if (RTL) lenX else 0).toFloat()
			val cY = if (isCenterBased) lenY / 2f else (if (RTL) lenY else 0).toFloat()
			val radius = if (isCenterBased) lenX / 2f else lenX.toFloat()

			val colorfulShader = RadialGradient(cX, cY, radius, shadeColors!!, shadeLevels, Shader.TileMode.CLAMP)
			mPaint.setShader(colorfulShader)
		}
	}

	fun setAlphaPct(alphaPct: Int) {
		this.alphaPct = alphaPct
		mDrawable.setAlpha(Math.round(alphaPct * 2.55f))

		chargingIndicatorView.alpha = alphaPct / 100f
		chargingIndicatorViewForCenter.alpha = alphaPct / 100f
	}

	// this override never calls super.setEnabled(), so the View-level enabled
	// flag stays untouched - the omission is deliberate
	override fun setEnabled(enabled: Boolean) {
		mIsEnabled = enabled
		refreshVisibility()
	}

	fun setVisible(visible: Boolean) {
		mIsHidden = !visible
		refreshVisibility()
	}

	private fun refreshVisibility() {
		if (!mIsEnabled || mIsHidden || (onlyWhileCharging && !BatteryDataProvider.isCharging())) {
			barView.visibility = GONE
			stopChargingAnimation()
		} else {
			barView.visibility = VISIBLE

			if (BatteryDataProvider.isCharging() && animateCharging) {
				startChargingAnimation()
			} else {
				stopChargingAnimation()
			}
		}
	}

	fun setCenterBased(bbSetCentered: Boolean) {
		isCenterBased = bbSetCentered
	}
}
