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
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import java.util.Locale
import sh.siava.pixelxpert.xposed.modpacks.systemui.BatteryDataProvider
import sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods

class BatteryBarView(context: Context) : FrameLayout(context) {

    companion object {
        private var shadeColors: IntArray? = null
        private var shadeLevels: FloatArray = FloatArray(0)
        private var batteryLevels: List<Float> = listOf(20f, 40f)
        private var batteryColors: IntArray = intArrayOf(Color.RED, Color.YELLOW)
        private var chargingColor: Int = Color.WHITE
        private var fastChargingColor: Int = Color.WHITE
        private var powerSaveColor: Int = Color.parseColor("#FFBF00")
        private var indicateCharging: Boolean = false
        private var indicateFastCharging: Boolean = false
        private var indicatePowerSave: Boolean = false
        private var transitColors: Boolean = false
        private var animateCharging: Boolean = false
        private const val ANIM_DURATION: Long = 1000L
        private const val ANIM_DELAY: Long = 2000L

        @SuppressLint("StaticFieldLeak")
        private var instance: BatteryBarView? = null

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
            animate: Boolean
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
            return instance ?: BatteryBarView(context)
        }

        @JvmStatic
        fun getInstance(): BatteryBarView? = instance

        @JvmStatic
        fun hasInstance(): Boolean = instance != null

        private fun refreshShadeColors() {
            val colors = batteryColors
            if (colors.isEmpty() || batteryLevels.isEmpty()) return

            val newShadeColors = IntArray(batteryLevels.size * 2 + 2)
            val newShadeLevels = FloatArray(newShadeColors.size)
            var prev = 0f
            for (i in batteryLevels.indices) {
                val rangeLength = batteryLevels[i] - prev
                newShadeLevels[2 * i] = (prev + rangeLength * 0.3f) / 100f
                newShadeColors[2 * i] = colors[i]

                newShadeLevels[2 * i + 1] = (batteryLevels[i] - rangeLength * 0.3f) / 100f
                newShadeColors[2 * i + 1] = colors[i]

                prev = batteryLevels[i]
            }

            newShadeLevels[newShadeLevels.size - 2] = (batteryLevels.last() + (100f - batteryLevels.last()) * 0.3f) / 100f
            newShadeColors[newShadeColors.size - 2] = Color.GREEN
            newShadeLevels[newShadeLevels.size - 1] = 1f
            newShadeColors[newShadeColors.size - 1] = Color.GREEN

            shadeColors = newShadeColors
            shadeLevels = newShadeLevels
        }
    }

    private val RTL: Boolean = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == LAYOUT_DIRECTION_RTL
    private val mDrawable: ShapeDrawable = ShapeDrawable()
    var maskLayout: FrameLayout
    private val chargingIndicatorView: ImageView
    private val chargingIndicatorViewForCenter: ImageView
    private var colorful: Boolean = false
    private var alphaPct: Int = 100
    private var isCenterBased: Boolean = false
    private var barHeight: Int = 10
    private val barView: ImageView
    private var onTop: Boolean = true
    private var onlyWhileCharging: Boolean = false
    private var mIsEnabled: Boolean = true
    private var mIsHidden: Boolean = false
    private val animationHandler: Handler = Handler(Looper.getMainLooper())
    private var chargingAnimationRunnable: Runnable? = null

    init {
        instance = this

        chargingIndicatorView = ImageView(context)
        chargingIndicatorViewForCenter = ImageView(context)
        initChargingAnimationView()

        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        mDrawable.shape = RectShape()
        setAlphaPct(alphaPct)

        barView = ImageView(context)
        barView.setImageDrawable(mDrawable)

        maskLayout = FrameLayout(context).apply {
            addView(barView)
            clipChildren = true
        }

        addView(maskLayout)
        addView(chargingIndicatorView)
        addView(chargingIndicatorViewForCenter)
        clipChildren = true

        StatusbarMods.registerTextColorCallback { refreshLayout() }
        StatusbarMods.registerClockVisibilityCallback { setVisible(it) }

        refreshLayout()

        val self = this
        BatteryDataProvider.registerInfoCallback { self.post { self.refreshLayout() } }
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

    private fun maskLayoutParams(): LayoutParams {
        val result = LayoutParams(
            Math.round(width * BatteryDataProvider.getCurrentLevel() / 100f),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        result.gravity = if (isCenterBased) Gravity.CENTER else Gravity.START
        return result
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshLayout()
    }

    private fun startChargingAnimation() {
        if (chargingAnimationRunnable == null) {
            chargingAnimationRunnable = object : Runnable {
                override fun run() {
                    animateChargingIndicator()
                    animationHandler.postDelayed(this, ANIM_DELAY)
                }
            }
            chargingAnimationRunnable?.let { animationHandler.post(it) }
        }
    }

    private fun stopChargingAnimation() {
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

        val startX: Float
        val startXCenter: Float
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

        if (isCenterBased) {
            endX = width * (1f - ((100f - BatteryDataProvider.getCurrentLevel()) / 200f))
        }

        val interpolator = AccelerateInterpolator()

        val animation = TranslateAnimation(startX, endX, 0f, 0f).apply {
            duration = ANIM_DURATION
            setInterpolator(interpolator)
        }
        chargingIndicatorView.startAnimation(animation)

        if (isCenterBased) {
            val animationCenter = TranslateAnimation(startXCenter, width - endX, 0f, 0f).apply {
                duration = ANIM_DURATION
                setInterpolator(interpolator)
            }
            chargingIndicatorViewForCenter.startAnimation(animationCenter)
        }
    }

    private fun initChargingAnimationView() {
        val lp = LayoutParams(20, barHeight)
        chargingIndicatorView.layoutParams = lp
        chargingIndicatorViewForCenter.layoutParams = lp

        val clockColor = StatusbarMods.getCurrentClockColor()
        chargingIndicatorView.setBackgroundColor(clockColor)
        chargingIndicatorViewForCenter.setBackgroundColor(clockColor)
    }

    private fun barLayoutParams(): LayoutParams {
        val result = LayoutParams(width, barHeight)
        result.gravity = if (isCenterBased) Gravity.CENTER else Gravity.START
        result.gravity = result.gravity or (if (onTop) Gravity.TOP else Gravity.BOTTOM)
        return result
    }

    private fun charginLayoutParams(): LayoutParams {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        val dp = 4f
        val pixels = (metrics.density * dp + 0.5f).toInt()
        val result = LayoutParams(pixels, barHeight)
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
        if (lenX == 0) return
        refreshShadeColors()
        val mPaint: Paint = mDrawable.paint
        mPaint.shader = null
        mDrawable.intrinsicWidth = lenX
        mDrawable.intrinsicHeight = lenY

        if (BatteryDataProvider.isFastCharging() && indicateFastCharging) {
            chargingIndicatorView.setBackgroundColor(fastChargingColor)
            chargingIndicatorViewForCenter.setBackgroundColor(fastChargingColor)
            mPaint.color = fastChargingColor
        } else if (BatteryDataProvider.isCharging() && indicateCharging) {
            chargingIndicatorView.setBackgroundColor(chargingColor)
            chargingIndicatorViewForCenter.setBackgroundColor(chargingColor)
            mPaint.color = chargingColor
        } else if (BatteryDataProvider.isPowerSaving() && indicatePowerSave) {
            mPaint.color = powerSaveColor
        } else if (!colorful || shadeColors == null) {
            val currentLevel = BatteryDataProvider.getCurrentLevel()
            for (i in batteryLevels.indices) {
                if (currentLevel <= batteryLevels[i]) {
                    if (transitColors && i > 0) {
                        val range = batteryLevels[i] - batteryLevels[i - 1]
                        val currentPos = currentLevel - batteryLevels[i - 1]
                        val ratio = currentPos / range
                        mPaint.color = ColorUtils.blendARGB(batteryColors[i - 1], batteryColors[i], ratio)
                    } else {
                        mPaint.color = batteryColors[i]
                    }
                    return
                }
            }
            mPaint.color = StatusbarMods.getCurrentClockColor()
        } else {
            val cX = if (isCenterBased) lenX / 2f else (if (RTL) lenX.toFloat() else 0f)
            val cY = if (isCenterBased) lenY / 2f else (if (RTL) lenY.toFloat() else 0f)
            val radius = if (isCenterBased) lenX / 2f else lenX.toFloat()

            val colors = shadeColors ?: return
            val colorfulShader = RadialGradient(cX, cY, radius, colors, shadeLevels, Shader.TileMode.CLAMP)
            mPaint.shader = colorfulShader
        }
    }

    fun setAlphaPct(alphaPct: Int) {
        this.alphaPct = alphaPct
        mDrawable.alpha = Math.round(alphaPct * 2.55f)
        chargingIndicatorView.alpha = alphaPct / 100f
        chargingIndicatorViewForCenter.alpha = alphaPct / 100f
    }

    override fun setEnabled(enabled: Boolean) {
        this.mIsEnabled = enabled
        refreshVisibility()
    }

    fun setVisible(visible: Boolean) {
        this.mIsHidden = !visible
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
