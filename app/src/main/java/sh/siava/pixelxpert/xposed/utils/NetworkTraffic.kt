package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.VERTICAL
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import java.lang.ref.WeakReference
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools

@SuppressLint("ViewConstructor")
class NetworkTraffic @JvmOverloads constructor(
    private val mContext: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
    private val isSBInstance: Boolean = true
) : FrameLayout(mContext, attrs, defStyle) {

    companion object {
        private const val KB = 1024
        private const val symbol = "/s"

        const val MODE_SHOW_RXTX = 0
        const val MODE_SHOW_RX = 1
        const val MODE_SHOW_TX = 2
        const val MODE_SHOW_TOTAL = 3

        // Same on all instances - default values needed in case not set by parents
        @JvmStatic
        var autoHideThreshold: Int = 10 * KB // unit: Bytes/second
            private set
        @JvmStatic
        var indicatorMode: Int = MODE_SHOW_RXTX
            private set
        @JvmStatic
        var RXonTop: Boolean = true
            private set
        @JvmStatic
        var refreshInterval: Int = 1 // seconds
            private set
        @JvmStatic
        var showIcons: Boolean = true
            private set

        @ColorInt
        @JvmStatic
        var downloadColor: Int = Color.GREEN
            private set
        @ColorInt
        @JvmStatic
        var uploadColor: Int = Color.RED
            private set
        @JvmStatic
        var colorTraffic: Boolean = false
            private set
        @JvmStatic
        var opacity: Int = 100
            private set
        @JvmStatic
        var lastParamUpdate: Long = 0
            private set
        @JvmStatic
        var showInBits: Boolean = false
            private set

        private var SBInstance: WeakReference<NetworkTraffic>? = null
        private var SBTintColor: Int = 0

        private var QSInstance: WeakReference<NetworkTraffic>? = null
        private var QSTintColor: Int = 0

        @JvmStatic
        fun setConstants(
            refreshInterval: Int,
            autoHideThreshold: Int,
            indicatorMode: Int,
            rxOnTop: Boolean,
            colorTraffic: Boolean,
            downloadColor: Int,
            uploadColor: Int,
            opacity: Int,
            showIcons: Boolean,
            showInBits: Boolean
        ) {
            Companion.refreshInterval = refreshInterval
            Companion.autoHideThreshold = autoHideThreshold * KB
            Companion.indicatorMode = indicatorMode
            Companion.RXonTop = rxOnTop
            Companion.colorTraffic = colorTraffic
            Companion.downloadColor = downloadColor
            Companion.uploadColor = uploadColor
            Companion.opacity = opacity
            Companion.showIcons = showIcons
            Companion.showInBits = showInBits

            lastParamUpdate = SystemClock.elapsedRealtime()
        }

        @JvmStatic
        fun getInstance(context: Context, onStatusbar: Boolean): NetworkTraffic? {
            val instance = if (onStatusbar) SBInstance else QSInstance
            if (instance == null || instance.get() == null) {
                NetworkTraffic(context, null, 0, onStatusbar)
            }
            return if (onStatusbar) SBInstance?.get() else QSInstance?.get()
        }

        @JvmStatic
        fun setTintColor(color: Int, isSBInstance: Boolean) {
            if ((SBTintColor != color && isSBInstance) || (QSTintColor != color && !isSBInstance)) {
                if (isSBInstance) {
                    SBTintColor = color
                    SBInstance?.get()?.updateTrafficDrawable()
                } else {
                    QSTintColor = color
                    QSInstance?.get()?.updateTrafficDrawable()
                }
            }
        }
    }

    private var lastInstanceParamUpdate: Long = -1
    private val iconLayout: LinearLayout
    protected var mAttached: Boolean = false
    private var totalRxBytes: Long = 0
    private var totalTxBytes: Long = 0
    private var lastUpdateTime: Long = 0
    private val mTextView: TextView
    private var mViewVisible = true
    private var mTrafficVisible = false
    private val mConnectivityManager: ConnectivityManager?

    private val mTrafficHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            var timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime

            if (timeDelta < 1) {
                timeDelta = Long.MAX_VALUE
            }
            lastUpdateTime = SystemClock.elapsedRealtime()

            val newTotalRxBytes = TrafficStats.getTotalRxBytes()
            val newTotalTxBytes = TrafficStats.getTotalTxBytes()
            val rxData = newTotalRxBytes - totalRxBytes
            val txData = newTotalTxBytes - totalTxBytes

            if (shouldHide(rxData, txData, timeDelta)) {
                hide(true)
            } else {
                var output: SpannableStringBuilder? = null
                when (indicatorMode) {
                    MODE_SHOW_RXTX -> {
                        val rxOutput = formatOutput(timeDelta, rxData, if (colorTraffic) downloadColor else null)
                        val txOutput = formatOutput(timeDelta, txData, if (colorTraffic) uploadColor else null)
                        output = if (RXonTop) {
                            rxOutput.append("\n").append(txOutput)
                        } else {
                            txOutput.append("\n").append(rxOutput)
                        }
                    }
                    MODE_SHOW_TOTAL -> {
                        output = formatOutput(timeDelta, rxData + txData, null)
                    }
                    MODE_SHOW_RX -> {
                        output = formatOutput(timeDelta, rxData, if (colorTraffic) downloadColor else null)
                    }
                    MODE_SHOW_TX -> {
                        output = formatOutput(timeDelta, txData, if (colorTraffic) uploadColor else null)
                    }
                }
                mTextView.text = output
                makeVisible(true)
            }

            totalRxBytes = newTotalRxBytes
            totalTxBytes = newTotalTxBytes
            clearHandlerCallbacks()
            postDelayed(mRunnable, refreshInterval * 1000L)
        }

        private fun formatOutput(timeDelta: Long, data: Long, @ColorInt textColor: Int?): SpannableStringBuilder {
            val speed = (data / (timeDelta / 1000f)).toLong()
            return ObjectTools.getHumanizedBytes(
                speed,
                showInBits,
                0.7f,
                if (indicatorMode == MODE_SHOW_RXTX) " " else "\n",
                symbol,
                textColor
            )
        }

        private fun shouldHide(rxData: Long, txData: Long, timeDelta: Long): Boolean {
            val speedRx = (rxData / (timeDelta / 1000f)).toLong()
            val speedTx = (txData / (timeDelta / 1000f)).toLong()

            val lowSpeed = when (indicatorMode) {
                MODE_SHOW_RXTX -> (speedRx < autoHideThreshold && speedTx < autoHideThreshold)
                MODE_SHOW_RX -> speedRx < autoHideThreshold
                MODE_SHOW_TX -> speedTx < autoHideThreshold
                MODE_SHOW_TOTAL -> (speedRx + speedTx) < autoHideThreshold
                else -> false
            }

            return !getConnectAvailable() || lowSpeed
        }
    }

    init {
        mTextView = TextView(mContext)
        iconLayout = LinearLayout(mContext).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val contentLayout = LinearLayout(mContext).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            addView(iconLayout)
            addView(mTextView)
        }
        addView(contentLayout)
        mConnectivityManager = SystemUtils.ConnectivityManager()

        if (isSBInstance) {
            SBInstance = WeakReference(this)
            setTintColor(StatusbarMods.registerTextColorCallback { textColor -> setTintColor(textColor, true) }, true)
            StatusbarMods.registerClockVisibilityCallback { visible ->
                if (visible) makeVisible(false) else hide(false)
            }
        } else {
            QSInstance = WeakReference(this)
        }
    }

    private fun hide(trafficRelated: Boolean) {
        if (!trafficRelated) {
            mViewVisible = false
        } else {
            mTrafficVisible = false
        }
        mTextView.text = ""
        visibility = View.GONE
    }

    protected fun makeVisible(trafficRelated: Boolean) {
        try {
            if (trafficRelated) mTrafficVisible = true
            else mViewVisible = true

            if (!mTrafficVisible || !mViewVisible) return

            if (lastInstanceParamUpdate < lastParamUpdate) {
                alpha = opacity / 100f
                setIndicatorMode()
                mTextView.gravity = Gravity.CENTER
                mTextView.maxLines = 2
                setSpacingAndFonts()
                updateTrafficDrawable()
                lastInstanceParamUpdate = lastParamUpdate
            }
            visibility = View.VISIBLE
        } catch (ignored: Throwable) {}
    }

    @SuppressLint("DiscouragedApi")
    private fun setIndicatorMode() {
        val res = XPLauncher.moduleResources ?: return

        val typedValue = TypedValue()
        mContext.resources.getValue(
            mContext.resources.getIdentifier("status_bar_icon_scale_factor", "dimen", mContext.packageName),
            typedValue,
            true
        )
        val iconScaleFactor = typedValue.float * 0.65f

        val height = mContext.resources.getDimensionPixelSize(
            mContext.resources.getIdentifier("status_bar_icon_drawing_size", "dimen", mContext.packageName)
        )
        val iconDim = (height * iconScaleFactor).toInt()

        val iconR = ImageView(mContext).apply {
            setImageDrawable(ResourcesCompat.getDrawable(res, R.drawable.ic_chevron_down, mContext.theme))
            layoutParams = LinearLayout.LayoutParams(iconDim, iconDim)
        }

        val iconT = ImageView(mContext).apply {
            setImageDrawable(ResourcesCompat.getDrawable(res, R.drawable.ic_chevron_up, mContext.theme))
            layoutParams = LinearLayout.LayoutParams(iconDim, iconDim)
        }

        iconLayout.removeAllViews()
        if (showIcons) {
            when (indicatorMode) {
                MODE_SHOW_RXTX -> {
                    iconLayout.addView(iconT)
                    iconLayout.addView(iconR, if (RXonTop) 0 else 1)
                }
                MODE_SHOW_RX -> iconLayout.addView(iconR)
                MODE_SHOW_TX -> iconLayout.addView(iconT)
            }
        }
        mTextView.textAlignment = if (indicatorMode == MODE_SHOW_RXTX) View.TEXT_ALIGNMENT_TEXT_END else View.TEXT_ALIGNMENT_CENTER
        val iconPadding = (height * iconScaleFactor / 4).toInt()
        iconR.setPadding(0, if (RXonTop) 0 else iconPadding, 0, if (RXonTop) iconPadding else 0)
        iconT.setPadding(0, if (RXonTop) iconPadding else 0, 0, if (RXonTop) 0 else iconPadding)
    }

    @Suppress("DEPRECATION")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!mAttached) {
            mAttached = true
            val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            mContext.registerReceiver(mIntentReceiver, filter, Context.RECEIVER_EXPORTED)
        }
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearHandlerCallbacks()
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver)
            mAttached = false
        }
    }

    private val mRunnable = Runnable { mTrafficHandler.sendEmptyMessage(0) }

    private val mIntentReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == ConnectivityManager.CONNECTIVITY_ACTION) {
                update()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getConnectAvailable(): Boolean {
        val network: Network? = mConnectivityManager?.activeNetwork
        return network != null
    }

    fun update() {
        if (mAttached) {
            totalRxBytes = TrafficStats.getTotalRxBytes()
            totalTxBytes = TrafficStats.getTotalTxBytes()
            lastUpdateTime = SystemClock.elapsedRealtime()
            clearHandlerCallbacks()
            mTrafficHandler.sendEmptyMessage(1)
        }
    }

    private fun clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable)
        mTrafficHandler.removeMessages(0)
        mTrafficHandler.removeMessages(1)
    }

    protected fun updateTrafficDrawable() {
        val color = if (isSBInstance) SBTintColor else QSTintColor
        for (i in 0 until iconLayout.childCount) {
            try {
                (iconLayout.getChildAt(i) as? ImageView)?.setColorFilter(color)
            } catch (ignored: Exception) {}
        }
        mTextView.setTextColor(color)
        mTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    protected fun setSpacingAndFonts() {
        @SuppressLint("DiscouragedApi")
        val fontResId = resources.getIdentifier("config_headlineFontFamily", "string", "android")
        val txtFont = if (fontResId != 0) resources.getString(fontResId) else "sans-serif"
        mTextView.typeface = Typeface.create(txtFont, Typeface.BOLD)
        mTextView.setLineSpacing(0.85f, 0.85f)
        mTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
    }

    class TrafficStyle(@param:ColorInt private val textColor: Int) : CharacterStyle() {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.setShadowLayer(textPaint.textSize / 10, 0f, 0f, Color.BLACK)
            textPaint.color = textColor
        }
    }
}
