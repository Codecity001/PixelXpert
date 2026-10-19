package sh.siava.pixelxpert.xposed.utils

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.Nullable
import androidx.appcompat.widget.LinearLayoutCompat

class ShyLinearLayout : LinearLayout {
	constructor(context: Context) : super(context) {
		visibility = GONE
	}

	constructor(context: Context, @Nullable attrs: AttributeSet?) : super(context, attrs)

	constructor(context: Context, @Nullable attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
		layoutParams = LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
	}

	override fun onViewAdded(v: View) {
		super.onViewAdded(v)

		visibility = VISIBLE
	}

	override fun onViewRemoved(v: View) {
		super.onViewRemoved(v)

		if (childCount == 0) {
			visibility = GONE
		}
	}
}
