package sh.siava.pixelxpert.xposed.utils

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.LinearLayoutCompat

/**
 * A LinearLayout that hides itself when it's got no children.
 * Using this, we don't need to track children and make it visible and hidden all the time
 */
open class ShyLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        visibility = GONE
        if (defStyleAttr != 0) {
            layoutParams = LinearLayoutCompat.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        visibility = VISIBLE
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        if (childCount == 0) {
            visibility = GONE
        }
    }
}
