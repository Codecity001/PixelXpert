package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.annotation.Nullable
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools
import java.lang.ref.WeakReference

/**
 * Status-bar (and QS) network speed indicator: a [FrameLayout] holding a small
 * column of direction chevrons next to a two-line text view. A main-looper
 * [Handler] samples [TrafficStats] every `refreshInterval` seconds, formats the
 * rx/tx rate, and hides the view whenever the connection is down or the speed
 * sits under the shared auto-hide threshold.
 *
 * The configuration is static-like (companion) state shared by every instance
 * so the status-bar and QS copies always agree, and the shadowed text span the
 * formatters apply lives here as the nested [TrafficStyle] class.
 */
@SuppressLint("ViewConstructor")
class NetworkTraffic private constructor(context: Context, attrs: AttributeSet?, defStyle: Int, onStatusbar: Boolean) :
	FrameLayout(context, attrs, defStyle) {
	private constructor(context: Context, onStatusbar: Boolean) : this(context, null, 0, onStatusbar)

	private constructor(context: Context, attrs: AttributeSet?, onStatusbar: Boolean) : this(
		context,
		attrs,
		0,
		onStatusbar,
	)

	private val isSBInstance: Boolean
	private var lastInstanceParamUpdate: Long = -1

	private val iconLayout: LinearLayout
	protected var mAttached: Boolean = false
	private var totalRxBytes: Long = 0
	private var totalTxBytes: Long = 0
	private var lastUpdateTime: Long = 0
	private val mContext: Context
	private val mTextView: TextView

	private var mViewVisible = true
	private var mTrafficVisible = false
	private var mNetworkAvailable = false
	private val mConnectivityManager: ConnectivityManager?

	// Typed explicitly: mRunnable below reads this property, and this handler's
	// body reads mRunnable, so letting the compiler infer the anonymous object
	// type makes the two declarations depend on each other and inference fails.
	private val mTrafficHandler: Handler = object : Handler(Looper.getMainLooper()) {
		override fun handleMessage(msg: Message) {
			var timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime

			if (timeDelta < 1) {
				// Can't div by 0 so make sure the value displayed is minimal
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
						val RXOutput = formatOutput(timeDelta, rxData, if (colorTraffic) downloadColor else null)

						val TXOutput = formatOutput(timeDelta, txData, if (colorTraffic) uploadColor else null)
						output = if (RXonTop) {
							RXOutput.append("\n").append(TXOutput)
						} else {
							TXOutput.append("\n").append(RXOutput)
						}
					}

					MODE_SHOW_TOTAL -> output = formatOutput(timeDelta, rxData + txData, null)

					MODE_SHOW_RX -> output = formatOutput(timeDelta, rxData, if (colorTraffic) downloadColor else null)

					MODE_SHOW_TX -> output = formatOutput(timeDelta, txData, if (colorTraffic) uploadColor else null)
				}
				mTextView.text = output
				makeVisible(true)
			}

			totalRxBytes = newTotalRxBytes
			totalTxBytes = newTotalTxBytes
			clearHandlerCallbacks()
			mTrafficHandler.postDelayed(mRunnable, refreshInterval * 1000L)
		}

		private fun formatOutput(timeDelta: Long, data: Long, @Nullable @ColorInt textColor: Int?): SpannableStringBuilder {
			val speed = (data / (timeDelta / 1000F)).toLong()

			return ObjectTools.getHumanizedBytes(
				speed,
				showInBits,
				.7f,
				if (indicatorMode ==
					MODE_SHOW_RXTX
				) {
					" "
				} else {
					"\n"
				},
				symbol,
				textColor,
			)
		}

		private fun shouldHide(rxData: Long, txData: Long, timeDelta: Long): Boolean {
			val speedRx = (rxData / (timeDelta / 1000f)).toLong()
			val speedTx = (txData / (timeDelta / 1000f)).toLong()

			val lowSpeed = when (indicatorMode) {
				MODE_SHOW_RXTX ->
					(
						speedRx < autoHideThreshold &&
							speedTx < autoHideThreshold
						)

				MODE_SHOW_RX -> speedRx < autoHideThreshold

				MODE_SHOW_TX -> speedTx < autoHideThreshold

				MODE_SHOW_TOTAL -> (speedRx + speedTx) < autoHideThreshold

				else -> false
			}

			return !getConnectAvailable() || lowSpeed
		}
	}

	private val mRunnable = Runnable { mTrafficHandler.sendEmptyMessage(0) }

	private val mNetworkCallback = object : ConnectivityManager.NetworkCallback() {
		override fun onAvailable(network: Network) {
			mNetworkAvailable = true
			update()
		}

		override fun onLost(network: Network) {
			mNetworkAvailable = false
			update()
		}
	}

	init {
		mContext = context
		mTextView = TextView(mContext)
		iconLayout = LinearLayout(mContext)
		iconLayout.orientation = VERTICAL
		iconLayout.gravity = Gravity.CENTER_VERTICAL
		iconLayout.layoutParams =
			FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
		val contentLayout = LinearLayout(mContext)
		contentLayout.layoutParams =
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER_VERTICAL,
			)
		addView(contentLayout)
		contentLayout.addView(iconLayout)
		contentLayout.addView(mTextView)
		mConnectivityManager = SystemUtils.ConnectivityManager()

		isSBInstance = onStatusbar
		if (onStatusbar) {
			SBInstance = WeakReference(this)
			setTintColor(StatusbarMods.registerTextColorCallback { textColor -> setTintColor(textColor, true) }, true)
			StatusbarMods.registerClockVisibilityCallback { visible ->
				if (visible) {
					makeVisible(false)
				} else {
					hide(false)
				}
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
			if (trafficRelated) {
				mTrafficVisible = true
			} else {
				mViewVisible = true
			}

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
		} catch (ignored: Throwable) {
		}
	}

	@SuppressLint("DiscouragedApi")
	private fun setIndicatorMode() {
		val res = XPLauncher.moduleResources!!

		val typedValue = TypedValue()
		mContext.resources.getValue(
			mContext.resources.getIdentifier("status_bar_icon_scale_factor", "dimen", mContext.packageName),
			typedValue,
			true,
		)
		val iconScaleFactor = typedValue.float * .65f

		val Height = mContext.resources.getDimensionPixelSize(
			mContext.resources.getIdentifier("status_bar_icon_drawing_size", "dimen", mContext.packageName),
		)
		val iconR = ImageView(mContext)
		iconR.setImageDrawable(ResourcesCompat.getDrawable(res, R.drawable.ic_chevron_down, mContext.theme))
		iconR.layoutParams = LinearLayout.LayoutParams((Height * iconScaleFactor).toInt(), (Height * iconScaleFactor).toInt())

		val iconT = ImageView(mContext)
		iconT.setImageDrawable(ResourcesCompat.getDrawable(res, R.drawable.ic_chevron_up, mContext.theme))
		iconT.layoutParams = LinearLayout.LayoutParams((Height * iconScaleFactor).toInt(), (Height * iconScaleFactor).toInt())

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
		mTextView.textAlignment =
			if (indicatorMode == MODE_SHOW_RXTX) View.TEXT_ALIGNMENT_TEXT_END else View.TEXT_ALIGNMENT_CENTER
		val iconPadding = Math.round(Height * iconScaleFactor / 4)
		iconR.setPadding(0, if (RXonTop) 0 else iconPadding, 0, if (RXonTop) iconPadding else 0)
		iconT.setPadding(0, if (RXonTop) iconPadding else 0, 0, if (RXonTop) 0 else iconPadding)
	}

	@SuppressLint("MissingPermission")
	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		if (!mAttached) {
			mAttached = true
			mConnectivityManager?.registerDefaultNetworkCallback(mNetworkCallback)
		}
		update()
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		clearHandlerCallbacks()
		if (mAttached) {
			mConnectivityManager?.unregisterNetworkCallback(mNetworkCallback)
			mAttached = false
		}
	}

	private fun getConnectAvailable(): Boolean = mNetworkAvailable

	fun update() {
		if (mAttached) {
			totalRxBytes = TrafficStats.getTotalRxBytes()
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
				(iconLayout.getChildAt(i) as ImageView).setColorFilter(color)
			} catch (ignored: Exception) {
			}
		}
		mTextView.setTextColor(color)
		mTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
	}

	protected fun applyTextEffect() {
		when (textEffect) {
			1 -> mTextView.setShadowLayer(2f, 1f, 1.5f, 0x99000000.toInt())
			2 -> mTextView.setShadowLayer(1.2f, 0f, 0f, Color.BLACK)
			else -> mTextView.setShadowLayer(0f, 0f, 0f, 0)
		}
		mTextView.invalidate()
		postInvalidate()
	}

	protected fun setSpacingAndFonts() {
		@SuppressLint("DiscouragedApi")
		val txtFont = resources.getString(
			resources.getIdentifier("android:string/config_headlineFontFamily", "string", mContext.opPackageName),
		)
		mTextView.typeface = Typeface.create(txtFont, Typeface.BOLD)
		mTextView.setLineSpacing(0.85f, 0.85f)
		mTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
		applyTextEffect()
	}

	companion object {
		private const val KB = 1024
		private const val symbol = "/s"

		const val MODE_SHOW_RXTX = 0
		const val MODE_SHOW_RX = 1
		const val MODE_SHOW_TX = 2
		const val MODE_SHOW_TOTAL = 3

		// Same on all instances - default values needed in case not set by parents
		private var autoHideThreshold = 10 * KB // unit: Bytes/second
		private var indicatorMode = MODE_SHOW_RXTX
		private var RXonTop = true // if download is above upload
		private var refreshInterval = 1 // seconds
		private var showIcons = true

		@ColorInt
		private var downloadColor: Int = Color.GREEN

		@ColorInt
		private var uploadColor: Int = Color.RED
		private var colorTraffic: Boolean = false
		private var opacity = 100
		private var lastParamUpdate: Long = 0
		private var showInBits = false
		private var textEffect = 0

		@JvmStatic
		fun setConstants(
			refreshInterval: Int,
			autoHideThreshold: Int,
			indicatorMode: Int,
			RXonTop: Boolean,
			colorTraffic: Boolean,
			downloadColor: Int,
			uploadColor: Int,
			opacity: Int,
			showIcons: Boolean,
			showInBits: Boolean,
			textEffect: Int = 0,
		) {
			Companion.refreshInterval = refreshInterval
			Companion.autoHideThreshold = autoHideThreshold * KB
			Companion.indicatorMode = indicatorMode
			Companion.RXonTop = RXonTop
			Companion.colorTraffic = colorTraffic
			Companion.downloadColor = downloadColor
			Companion.uploadColor = uploadColor
			Companion.opacity = opacity
			Companion.showIcons = showIcons
			Companion.showInBits = showInBits
			Companion.textEffect = textEffect

			lastParamUpdate = SystemClock.elapsedRealtime()
			SBInstance?.get()?.mTextView?.post {
				SBInstance?.get()?.applyTextEffect()
				SBInstance?.get()?.update()
			}
			QSInstance?.get()?.mTextView?.post {
				QSInstance?.get()?.applyTextEffect()
				QSInstance?.get()?.update()
			}
		}

		private var SBInstance: WeakReference<NetworkTraffic>? = null
		private var SBTintColor: Int = 0

		private var QSInstance: WeakReference<NetworkTraffic>? = null
		private var QSTintColor: Int = 0

		@JvmStatic
		fun getInstance(context: Context, onStatusbar: Boolean): NetworkTraffic {
			val instance = if (onStatusbar) SBInstance else QSInstance
			if (instance == null || instance.get() == null) {
				NetworkTraffic(context, onStatusbar)
			}
			return if (onStatusbar) SBInstance!!.get()!! else QSInstance!!.get()!!
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

	class TrafficStyle(@param:ColorInt private val textColor: Int) : CharacterStyle() {
		override fun updateDrawState(textPaint: TextPaint) {
			// Simple color span without shadow layer to keep text sharp
			textPaint.color = textColor
		}
	}
}
