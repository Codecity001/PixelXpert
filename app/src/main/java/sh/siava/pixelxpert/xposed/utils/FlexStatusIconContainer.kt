package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.Nullable
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.findFieldIfExists
import de.robv.android.xposed.XposedHelpers.setObjectField
import kotlin.math.max
import kotlin.math.min
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * A LinearLayout that measures its available space and aligns its statusbar icons
 * in a way that they can either fit in a row, or fit in different rows to use the
 * maximum available space. Children are walked from the end of the container
 * backwards (the direction the status bar draws them), every icon's state tag is
 * driven through `visibleState`, `hidden`, alpha and translation fields, and
 * whatever stops fitting collapses into an overflow "etc" dot.
 */
@SuppressLint("ViewConstructor")
class FlexStatusIconContainer(
	context: Context,
	attrs: AttributeSet?,
	statusIconContainerInstance: Any?
) : LinearLayout(context, attrs) {
	internal var StatusIconContainer: Any? = null

	companion object {
		const val STATE_ICON = 0
		const val STATE_DOT = 1
		const val STATE_HIDDEN = 2

		const val SORT_CLEAN = 0
		const val SORT_TIGHT = 1

		private const val DEBUG_OVERFLOW = false
		// Max 8 status icons including battery
		private const val MAX_ROW_ICONS = 7

		private var sortPlan = SORT_CLEAN

		@JvmStatic
		fun setSortPlan(plan: Int) {
			sortPlan = plan
		}

		@JvmStatic
		private fun getViewTotalMeasuredWidth(child: View): Int {
			return child.measuredWidth + child.paddingStart + child.paddingEnd
		}

		@Suppress("unused")
		@JvmStatic
		private fun getViewTotalMeasuredHeight(child: View): Int {
			return child.measuredHeight + child.paddingTop + child.paddingBottom
		}
	}

	private var mIconSpacing = 0
	private var mUnderflowWidth = 0
	// Individual StatusBarIconViews draw their etc dots centered in this width
	private var mIconDotFrameWidth = 0
	private val mMeasureViews: MutableList<View> = ArrayList()
	// Any ignored icon will never be added as a child
	private val mIgnoredSlots = ArrayList<String?>()
	private var mIconSize = 0
	private var mDotIcon: View? = null
	private var mTagID = 0
	private val mColWidths = SparseIntArray()
	private val mIconWidths: MutableList<Int> = ArrayList()
	private var mHasDot = false
	private var mRowCount = 0

	private val stateAlphaField: String
	private val stateXTranslationField: String
	private val stateYTranslationField: String

	constructor(context: Context, statusIconContainerInstance: Any?) : this(context, null, statusIconContainerInstance)

	init {
		StatusIconContainer = statusIconContainerInstance
		val ViewStateClass = ReflectedClass.of("com.android.systemui.statusbar.notification.stack.ViewState")

		if (findFieldIfExists(ViewStateClass.clazz, "mAlpha") == null) { // 13 QPR1
			stateAlphaField = "alpha"
			stateXTranslationField = "xTranslation"
			stateYTranslationField = "yTranslation"
		} else { // 13 QPR2
			stateAlphaField = "mAlpha"
			stateXTranslationField = "mXTranslation"
			stateYTranslationField = "mYTranslation"
		}

		initDimens()
		setWillNotDraw(!DEBUG_OVERFLOW)
	}

	override fun onFinishInflate() {
		super.onFinishInflate()
	}

	@Suppress("unused")
	fun setShouldRestrictIcons(should: Boolean) {
	}

	@Suppress("unused")
	fun isRestrictingIcons(): Boolean {
		return true
	}

	@SuppressLint("DiscouragedApi")
	private fun initDimens() {
		val res = resources

		mTagID = SystemUtils.idOf("status_bar_view_state_tag")
		// This is the same value that StatusBarIconView uses
		mIconDotFrameWidth = res.getDimensionPixelSize(
			res.getIdentifier("status_bar_icon_size", "dimen", "android"))
		mIconSize = mIconDotFrameWidth
		val mDotPadding = res.getDimensionPixelSize(SystemUtils.dimenIdOf("overflow_icon_dot_padding"))
		mIconSpacing = res.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_system_icon_spacing"))
		val radius = res.getDimensionPixelSize(SystemUtils.dimenIdOf("overflow_dot_radius"))
		val mStaticDotDiameter = 2 * radius
		mUnderflowWidth = mIconDotFrameWidth + mStaticDotDiameter + mDotPadding
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

		// Layout all child views so that we can move them around later
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			val width = child.measuredWidth
			val height = child.measuredHeight
			child.layout(0, 0, width, height)
		}

		resetViewStates()
		calculateIconTranslations()
		applyIconStates()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (DEBUG_OVERFLOW) {
			@SuppressLint("DrawAllocation")
			val paint = Paint()
			paint.style = Paint.Style.STROKE
			paint.color = Color.RED

			canvas.drawRect(
				paddingStart.toFloat(), 0f,
				(width - paddingEnd).toFloat(), height.toFloat(), paint)

			paint.color = Color.GREEN
			val mUnderflowStart = 0
			canvas.drawRect(
				mUnderflowStart.toFloat(), 0f,
				(mUnderflowStart + mUnderflowWidth).toFloat(), height.toFloat(), paint)
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		try {
			mColWidths.clear()
			mMeasureViews.clear()
			mIconWidths.clear()
			mDotIcon = null
			mHasDot = false

			val mode = MeasureSpec.getMode(widthMeasureSpec)
			val width = MeasureSpec.getSize(widthMeasureSpec)
			val height = MeasureSpec.getSize(heightMeasureSpec)

			if (width == 0) { //nothing to render. ignore
				setDefaultResponse(widthMeasureSpec, heightMeasureSpec)
				return
			}

			val parentView = parent as ViewGroup
			if (parentView.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
				parentView.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
			}

			val totalIconHeight = mIconSize
			val totalPossibleRows = height / totalIconHeight

			val paddings = paddingLeft + paddingRight
			val availableWidth = width - paddings

			var totalWidth = 0

			when (sortPlan) {
				SORT_CLEAN -> {
					mRowCount = 0
					mIconWidths.clear()
					val initialCapacity = totalPossibleRows * MAX_ROW_ICONS

					for (i in getChildCount() - 1 downTo 0) {
						val icon = getChildAt(i)

						var isBlocked = false
						try {
							isBlocked = callMethod(icon, "isIconBlocked") as Boolean
						} catch (ignored: Throwable) {
						}

						if (callMethod(icon, "isIconVisible") as Boolean && !isBlocked
							&& !mIgnoredSlots.contains(callMethod(icon, "getSlot") as String?)) { //icon should be considered!

							if (mIconWidths.size <= initialCapacity) { //enough widths recorded
								val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED)
								measureChild(icon, childWidthSpec, heightMeasureSpec)
								val iconWidth = getViewTotalMeasuredWidth(icon)

								mIconWidths.add(iconWidth)
								mMeasureViews.add(icon)
							} else {
								setChildVisibleState(icon, STATE_HIDDEN)
							}
						} else {
							setChildVisibleState(icon, STATE_HIDDEN)
						}
					}

					var iconsPerRow = min(mIconWidths.size, MAX_ROW_ICONS)

					var success = false
					mHasDot = false

					while (iconsPerRow > 0 && !success) {
						var iconCapacity = iconsPerRow * totalPossibleRows - (if (mHasDot) 1 else 0)

						if (mIconWidths.size > iconCapacity) {
							if (!mHasDot) {
								mHasDot = true
								if (iconCapacity > 0) iconCapacity--
							}
							mIconWidths.subList(iconCapacity, mIconWidths.size).clear()
						}
						mColWidths.clear()

						var colIndex = 0
						totalWidth = (iconsPerRow - 1) * mIconSpacing

						success = true

						var i = 0
						while (i < mIconWidths.size + (if (mHasDot) 1 else 0)) {
							if (colIndex == iconsPerRow) {
								colIndex = 0
							}
							val currentWidth = if (i == mIconWidths.size) mIconDotFrameWidth else mIconWidths[i]

							totalWidth -= mColWidths.get(colIndex, 0)
							mColWidths.put(colIndex, max(currentWidth, mColWidths.get(colIndex, 0)))
							totalWidth += mColWidths.get(colIndex, 0)

							if (totalWidth > availableWidth) {
								success = false
								iconsPerRow = min(i + 1, iconsPerRow)
								break
							}
							i++
							colIndex++
						}
						iconsPerRow--
					}

					if (mHasDot) {
						mMeasureViews.subList(mIconWidths.size + 1, mMeasureViews.size).forEach { setChildVisibleState(it, STATE_HIDDEN) }
						setChildVisibleState(mMeasureViews[mIconWidths.size], STATE_DOT)
						mMeasureViews.subList(mIconWidths.size, mMeasureViews.size).clear()
					}
				}
				SORT_TIGHT -> {
					mRowCount = 1
					var availableRows = totalPossibleRows - 1
					var remainingWidth = availableWidth
					var colIndex = 0

					var i = getChildCount() - 1
					while (i >= 0) {
						var icon = getChildAt(i)

						var isBlocked = false
						try {
							isBlocked = callMethod(icon, "isIconBlocked") as Boolean
						} catch (ignored: Throwable) {
						}

						if (callMethod(icon, "isIconVisible") as Boolean && !isBlocked
							&& !mIgnoredSlots.contains(callMethod(icon, "getSlot") as String?)) { //icon should be considered!

							if (mHasDot) {
								setChildVisibleState(icon, STATE_HIDDEN)
								i--
								continue
							}

							val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED)
							measureChild(icon, childWidthSpec, heightMeasureSpec)
							val iconWidth = getViewTotalMeasuredWidth(icon)

							if (iconWidth > remainingWidth || colIndex >= MAX_ROW_ICONS) { // row is full
								if (availableRows > 0) { //we still have more rows to fill: prepare for it
									colIndex = 0
									mRowCount++
									availableRows--
									i++ // Java's for update does i-- afterwards, so this icon is re-processed on the new row
									totalWidth = max(totalWidth, availableWidth - remainingWidth)
									remainingWidth = availableWidth
								} else { //no more rows available. let's close the case with a dot
									if (mIconDotFrameWidth < remainingWidth || mMeasureViews.isEmpty()) {
										setChildVisibleState(icon, STATE_DOT)
									} else {
										setChildVisibleState(icon, STATE_HIDDEN)

										icon = mMeasureViews[mMeasureViews.size - 1]
										remainingWidth += icon.measuredWidth
										setChildVisibleState(icon, STATE_DOT)
										mMeasureViews.remove(icon)
									}

									remainingWidth -= mIconDotFrameWidth
									totalWidth = max(totalWidth, availableWidth - remainingWidth)
								}
							} else {
								remainingWidth -= iconWidth + mIconSpacing
								mMeasureViews.add(icon)
								totalWidth = max(totalWidth, availableWidth - remainingWidth)
							}
						} else {
							setChildVisibleState(icon, STATE_HIDDEN)
						}
						i--
					}
				}
			}
			totalWidth += paddings

			if (mode == MeasureSpec.EXACTLY) {
				setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
			} else {
				if (totalWidth > width && mode == MeasureSpec.AT_MOST) {
					totalWidth = width
				}
				setMeasuredDimension(totalWidth, MeasureSpec.getSize(heightMeasureSpec))
			}
		} catch (ignored: Throwable) {
			setDefaultResponse(widthMeasureSpec, heightMeasureSpec)
		}
	}

	private fun setDefaultResponse(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
	}

	private fun setChildVisibleState(child: View, state: Int) {
		if (state == STATE_DOT) {
			mDotIcon = child
			mHasDot = true
		}
		val childState = getViewStateFromChild(child)
		setObjectField(childState, "visibleState", state)
	}

	override fun onViewAdded(child: View) {
		super.onViewAdded(child)
		try { //StatusIconState constructor is not accessible. using the container to construct one for us
			callMethod(StatusIconContainer, "onViewAdded", child)
		} catch (ignored: Throwable) {
		}
	}

	override fun onViewRemoved(child: View) {
		super.onViewRemoved(child)
		child.setTag(mTagID, null)
	}

	@Suppress("unused")
	fun addIgnoredSlot(slotName: String) {
		addIgnoredSlotInternal(slotName)
		requestLayout()
	}

	fun addIgnoredSlots(slots: List<String>) {
		for (slot in slots) {
			addIgnoredSlotInternal(slot)
		}
		requestLayout()
	}

	private fun addIgnoredSlotInternal(slotName: String) {
		if (!mIgnoredSlots.contains(slotName)) {
			mIgnoredSlots.add(slotName)
		}
	}

	@Suppress("unused")
	fun removeIgnoredSlot(slotName: String) {
		mIgnoredSlots.remove(slotName)

		requestLayout()
	}

	@Suppress("unused")
	fun removeIgnoredSlots(slots: List<String>) {
		for (slot in slots) {
			mIgnoredSlots.remove(slot)
		}

		requestLayout()
	}

	@Suppress("unused")
	fun setIgnoredSlots(slots: List<String>) {
		mIgnoredSlots.clear()
		addIgnoredSlots(slots)
	}

	@Suppress("unused")
	fun getViewForSlot(slot: String): View? {
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			try {
				if (callMethod(child, "getSlot").equals(slot)) {
					return child
				}
			} catch (ignored: Throwable) {
			}
		}
		return null
	}

	/**
	 * Layout is happening from end -> start
	 */
	private fun calculateIconTranslations() {
		try {
			val iconCount = mMeasureViews.size

			val width = getWidth().toFloat()

			if (width == 0f)
				return

			if (mRowCount == 0) {
				mRowCount = Math.ceil((mIconWidths.size + (if (mHasDot) 1 else 0)).toDouble() / mColWidths.size()).toInt()
			}

			val XEndPoint = width - paddingEnd
			var xPosition = XEndPoint
			var currentRow = 0
			var rowTop: Float

			if (iconCount == 0) /*unlikely!*/ {
				rowTop = (height - mIconDotFrameWidth) / 2f
			} else {
				rowTop = (height - (mRowCount * mIconSize)) / 2f
			}

			var colIndex = 0
			var i = 0
			while (i < iconCount + (if (mHasDot) 1 else 0)) {
				val icon: View = if (i < iconCount) mMeasureViews[i] else mDotIcon!!

				val iconWidth = if (i < iconCount) icon.width else mIconDotFrameWidth
				val childState = getViewStateFromChild(icon)

				var iconTranslationY = 0f
				var iconTranslationX = 0f

				when (sortPlan) {
					SORT_CLEAN -> {
						if (colIndex == mColWidths.size()) {
							colIndex = 0
							xPosition = XEndPoint
							currentRow++
							rowTop = (height - (mRowCount * mIconSize) + (2 * mIconSize * currentRow)) / 2f
						}

						if (i < iconCount) {
							setChildVisibleState(icon, STATE_ICON)
						}

						val shift = ((mColWidths.get(colIndex) - iconWidth) / 2f)

						iconTranslationX = xPosition - mColWidths.get(colIndex) + shift
						xPosition -= mColWidths.get(colIndex) + mIconSpacing
						iconTranslationY = Math.round(rowTop + (mIconSize - icon.measuredHeight) / 2f).toFloat()
					}
					SORT_TIGHT -> {
						if (xPosition >= iconWidth) {
							xPosition -= iconWidth
							iconTranslationX = xPosition

							setObjectField(childState, stateXTranslationField, xPosition)
						} else {
							currentRow++
							i--
							rowTop = (height - (mRowCount * mIconSize) + (2 * mIconSize * currentRow)) / 2f
							xPosition = XEndPoint
							// Java's `continue` still runs the for update (i++, colIndex++),
							// so the i-- above is cancelled and only colIndex really advances
							i++
							colIndex++
							continue
						}
						iconTranslationY = Math.round(rowTop + (mIconSize - icon.measuredHeight) / 2f).toFloat()
					}
				}
				if (icon !== mDotIcon) {
					setChildVisibleState(icon, STATE_ICON)
				}
				setObjectField(childState, stateXTranslationField, iconTranslationX)
				setObjectField(childState, stateYTranslationField, iconTranslationY)

				i++
				colIndex++
			}

			mMeasureViews.clear()
			mDotIcon = null
			System.gc()
		} catch (t: Throwable) {
			log("PixelXpert Error - Flex Statusbar Container")
			log(t)
		}
	}

	private fun applyIconStates() {
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			val vs = getViewStateFromChild(child)
			if (vs != null) {
				callMethod(vs, "applyToView", child)
			}
		}
	}

	private fun resetViewStates() {
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			val vs = getViewStateFromChild(child)
			if (vs == null) {
				continue
			}

			callMethod(vs, "initFrom", child)

			setObjectField(vs, stateAlphaField, 1.0f)

			setObjectField(vs, "hidden", false)
		}
	}

	@Nullable
	private fun getViewStateFromChild(child: View): Any? {
		return child.getTag(mTagID)
	}

	override fun hasOverlappingRendering(): Boolean {
		return false
	}
}
