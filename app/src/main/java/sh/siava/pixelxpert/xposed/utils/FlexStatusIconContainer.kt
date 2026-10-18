package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import sh.siava.pixelxpert.xposed.utils.SystemUtils.dimenIdOf
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A LinearLayout that measures its available space and aligns its statusbar icons
 * in a way that they can either fit in a row, or fits them in different rows to use maximum
 * available space
 */
@SuppressLint("ViewConstructor")
open class FlexStatusIconContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var statusIconContainer: Any? = null
) : LinearLayout(context, attrs) {

    companion object {
        const val STATE_ICON = 0
        const val STATE_DOT = 1
        const val STATE_HIDDEN = 2

        const val SORT_CLEAN = 0
        const val SORT_TIGHT = 1

        private const val DEBUG_OVERFLOW = false
        // Max 8 status icons including battery
        private const val MAX_ROW_ICONS = 7

        @JvmStatic
        var sortPlan: Int = SORT_CLEAN
    }

    private var mIconSpacing: Int = 0
    private var mUnderflowWidth: Int = 0
    // Individual StatusBarIconViews draw their etc dots centered in this width
    private var mIconDotFrameWidth: Int = 0
    // So we can count and measure properly
    private val mMeasureViews: MutableList<View> = ArrayList()
    // Any ignored icon will never be added as a child
    private val mIgnoredSlots: ArrayList<String> = ArrayList()
    private var mIconSize: Int = 0
    private var mDotIcon: View? = null
    private var mTagID: Int = 0
    private val mColWidths = SparseIntArray()
    private val mIconWidths: MutableList<Int> = ArrayList()
    private var mHasDot = false
    private var mRowCount: Int = 0

    private val stateAlphaField: String
    private val stateXTranslationField: String
    private val stateYTranslationField: String

    init {
        val viewStateClass = ReflectedClass.of("com.android.systemui.statusbar.notification.stack.ViewState")
        val viewStateClazz = viewStateClass.getClazz()

        if (viewStateClazz != null && findFieldIfExists(viewStateClazz, "mAlpha") == null) {
            // 13 QPR1
            stateAlphaField = "alpha"
            stateXTranslationField = "xTranslation"
            stateYTranslationField = "yTranslation"
        } else {
            // 13 QPR2+
            stateAlphaField = "mAlpha"
            stateXTranslationField = "mXTranslation"
            stateYTranslationField = "mYTranslation"
        }

        initDimens()
        setWillNotDraw(!DEBUG_OVERFLOW)
    }

    @Suppress("unused")
    fun setShouldRestrictIcons(should: Boolean) {
    }

    @Suppress("unused")
    fun isRestrictingIcons(): Boolean = true

    @SuppressLint("DiscouragedApi")
    private fun initDimens() {
        val res: Resources = resources

        mTagID = idOf("status_bar_view_state_tag")
        // This is the same value that StatusBarIconView uses
        mIconDotFrameWidth = res.getDimensionPixelSize(
            res.getIdentifier("status_bar_icon_size", "dimen", "android")
        )
        mIconSize = mIconDotFrameWidth
        val mDotPadding = res.getDimensionPixelSize(dimenIdOf("overflow_icon_dot_padding"))
        mIconSpacing = res.getDimensionPixelSize(dimenIdOf("status_bar_system_icon_spacing"))
        val radius = res.getDimensionPixelSize(dimenIdOf("overflow_dot_radius"))
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
            val paint = Paint().apply {
                style = Style.STROKE
                color = Color.RED
            }
            // Show bounding box
            canvas.drawRect(paddingStart.toFloat(), 0f, (width - paddingEnd).toFloat(), height.toFloat(), paint)

            // Show etc box
            paint.color = Color.GREEN
            val mUnderflowStart = 0f
            canvas.drawRect(mUnderflowStart, 0f, (mUnderflowStart + mUnderflowWidth), height.toFloat(), paint)
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

            if (width == 0) {
                setDefaultResponse(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val parent = this.parent as? ViewGroup
            if (parent != null && parent.layoutParams.height != MATCH_PARENT) {
                parent.layoutParams.height = MATCH_PARENT
            }

            val totalIconHeight = mIconSize
            val totalPossibleRows = if (totalIconHeight > 0) height / totalIconHeight else 1

            val paddings = paddingLeft + paddingRight
            val availableWidth = width - paddings

            var totalWidth = 0

            when (sortPlan) {
                SORT_CLEAN -> {
                    mRowCount = 0
                    mIconWidths.clear()
                    val initialCapacity = totalPossibleRows * MAX_ROW_ICONS

                    for (i in childCount - 1 downTo 0) {
                        val icon = getChildAt(i)

                        var isBlocked = false
                        try {
                            isBlocked = callMethod<Boolean>(icon, "isIconBlocked") ?: false
                        } catch (ignored: Throwable) {}

                        val isVisible = callMethod<Boolean>(icon, "isIconVisible") ?: false
                        val slot = callMethod<String>(icon, "getSlot")

                        if (isVisible && !isBlocked && !mIgnoredSlots.contains(slot)) {
                            if (mIconWidths.size <= initialCapacity) {
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

                        for (i in 0 until (mIconWidths.size + if (mHasDot) 1 else 0)) {
                            if (colIndex == iconsPerRow) {
                                colIndex = 0
                            }
                            val currentWidth = if (i == mIconWidths.size) {
                                mIconDotFrameWidth
                            } else {
                                mIconWidths[i]
                            }

                            totalWidth -= mColWidths.get(colIndex, 0)
                            mColWidths.put(colIndex, max(currentWidth, mColWidths.get(colIndex, 0)))
                            totalWidth += mColWidths.get(colIndex, 0)

                            if (totalWidth > availableWidth) {
                                success = false
                                iconsPerRow = min(i + 1, iconsPerRow)
                                break
                            }
                            colIndex++
                        }
                        iconsPerRow--
                    }

                    if (mHasDot && mMeasureViews.size > mIconWidths.size) {
                        mMeasureViews.subList(mIconWidths.size + 1, mMeasureViews.size).forEach { icon ->
                            setChildVisibleState(icon, STATE_HIDDEN)
                        }
                        setChildVisibleState(mMeasureViews[mIconWidths.size], STATE_DOT)
                        mMeasureViews.subList(mIconWidths.size, mMeasureViews.size).clear()
                    }
                }
                SORT_TIGHT -> {
                    mRowCount = 1
                    var availableRows = totalPossibleRows - 1
                    var remainingWidth = availableWidth
                    var colIndex = 0

                    for (i in childCount - 1 downTo 0) {
                        var icon = getChildAt(i)

                        var isBlocked = false
                        try {
                            isBlocked = callMethod<Boolean>(icon, "isIconBlocked") ?: false
                        } catch (ignored: Throwable) {}

                        val isVisible = callMethod<Boolean>(icon, "isIconVisible") ?: false
                        val slot = callMethod<String>(icon, "getSlot")

                        if (isVisible && !isBlocked && !mIgnoredSlots.contains(slot)) {
                            if (mHasDot) {
                                setChildVisibleState(icon, STATE_HIDDEN)
                                continue
                            }

                            val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED)
                            measureChild(icon, childWidthSpec, heightMeasureSpec)
                            val iconWidth = getViewTotalMeasuredWidth(icon)

                            if (iconWidth > remainingWidth || colIndex >= MAX_ROW_ICONS) {
                                if (availableRows > 0) {
                                    colIndex = 0
                                    mRowCount++
                                    availableRows--
                                    totalWidth = max(totalWidth, availableWidth - remainingWidth)
                                    remainingWidth = availableWidth
                                    // re-evaluate this icon for next row
                                    val childWidthSpec2 = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED)
                                    measureChild(icon, childWidthSpec2, heightMeasureSpec)
                                    remainingWidth -= iconWidth + mIconSpacing
                                    mMeasureViews.add(icon)
                                    colIndex++
                                } else {
                                    if (mIconDotFrameWidth < remainingWidth || mMeasureViews.isEmpty()) {
                                        setChildVisibleState(icon, STATE_DOT)
                                    } else {
                                        setChildVisibleState(icon, STATE_HIDDEN)
                                        icon = mMeasureViews[mMeasureViews.size - 1]
                                        remainingWidth += icon.measuredWidth
                                        setChildVisibleState(icon, STATE_DOT)
                                        mMeasureViews.removeAt(mMeasureViews.size - 1)
                                    }
                                    remainingWidth -= mIconDotFrameWidth
                                    totalWidth = max(totalWidth, availableWidth - remainingWidth)
                                }
                            } else {
                                remainingWidth -= iconWidth + mIconSpacing
                                mMeasureViews.add(icon)
                                totalWidth = max(totalWidth, availableWidth - remainingWidth)
                                colIndex++
                            }
                        } else {
                            setChildVisibleState(icon, STATE_HIDDEN)
                        }
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
        if (childState != null) {
            setObjectField(childState, "visibleState", state)
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        try {
            statusIconContainer?.let { callMethod<Any>(it, "onViewAdded", child) }
        } catch (ignored: Throwable) {}
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
                if (callMethod<String>(child, "getSlot") == slot) {
                    return child
                }
            } catch (ignored: Throwable) {}
        }
        return null
    }

    /**
     * Layout is happening from end -> start
     */
    private fun calculateIconTranslations() {
        try {
            val iconCount = mMeasureViews.size
            val widthF = width.toFloat()

            if (widthF == 0f) return

            if (mRowCount == 0) {
                val totalCols = if (mColWidths.size() > 0) mColWidths.size().toDouble() else 1.0
                mRowCount = ceil((mIconWidths.size + (if (mHasDot) 1 else 0)) / totalCols).toInt()
            }

            val xEndPoint = widthF - paddingEnd
            var xPosition = xEndPoint
            var currentRow = 0
            var rowTop = if (iconCount == 0) {
                (height - mIconDotFrameWidth) / 2f
            } else {
                (height - (mRowCount * mIconSize)) / 2f
            }

            var colIndex = 0
            val totalItems = iconCount + (if (mHasDot) 1 else 0)
            for (i in 0 until totalItems) {
                val icon = if (i < iconCount) mMeasureViews[i] else mDotIcon
                if (icon == null) continue

                val iconWidth = if (i < iconCount) icon.width else mIconDotFrameWidth
                val childState = getViewStateFromChild(icon) ?: continue

                var iconTranslationY = 0f
                var iconTranslationX = 0f

                when (sortPlan) {
                    SORT_CLEAN -> {
                        if (colIndex == mColWidths.size()) {
                            colIndex = 0
                            xPosition = xEndPoint
                            currentRow++
                            rowTop = (height - (mRowCount * mIconSize) + (2 * mIconSize * currentRow)) / 2f
                        }

                        if (i < iconCount) {
                            setChildVisibleState(icon, STATE_ICON)
                        }

                        val colW = mColWidths.get(colIndex, iconWidth)
                        val shift = (colW - iconWidth) / 2f

                        iconTranslationX = xPosition - colW + shift
                        xPosition -= (colW + mIconSpacing)
                        iconTranslationY = (rowTop + (mIconSize - icon.measuredHeight) / 2f).roundToInt().toFloat()
                        colIndex++
                    }
                    SORT_TIGHT -> {
                        if (xPosition >= iconWidth) {
                            xPosition -= iconWidth
                            iconTranslationX = xPosition
                            setObjectField(childState, stateXTranslationField, xPosition)
                        } else {
                            currentRow++
                            rowTop = (height - (mRowCount * mIconSize) + (2 * mIconSize * currentRow)) / 2f
                            xPosition = xEndPoint
                            xPosition -= iconWidth
                            iconTranslationX = xPosition
                            setObjectField(childState, stateXTranslationField, xPosition)
                        }
                        iconTranslationY = (rowTop + (mIconSize - icon.measuredHeight) / 2f).roundToInt().toFloat()
                    }
                }

                if (icon != mDotIcon) {
                    setChildVisibleState(icon, STATE_ICON)
                }
                setObjectField(childState, stateXTranslationField, iconTranslationX)
                setObjectField(childState, stateYTranslationField, iconTranslationY)
            }

            mMeasureViews.clear()
            mDotIcon = null
        } catch (t: Throwable) {
            Logger.log("PixelXpert Error - Flex Statusbar Container")
            Logger.log(t)
        }
    }

    private fun applyIconStates() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val vs = getViewStateFromChild(child)
            if (vs != null) {
                callMethod<Any>(vs, "applyToView", child)
            }
        }
    }

    private fun resetViewStates() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val vs = getViewStateFromChild(child) ?: continue

            callMethod<Any>(vs, "initFrom", child)
            setObjectField(vs, stateAlphaField, 1.0f)
            setObjectField(vs, "hidden", false)
        }
    }

    private fun getViewStateFromChild(child: View): Any? {
        return child.getTag(mTagID)
    }

    override fun hasOverlappingRendering(): Boolean = false

    companion object Utils {
        private fun getViewTotalMeasuredWidth(child: View): Int {
            return child.measuredWidth + child.paddingStart + child.paddingEnd
        }

        @Suppress("unused")
        private fun getViewTotalMeasuredHeight(child: View): Int {
            return child.measuredHeight + child.paddingTop + child.paddingBottom
        }
    }
}
