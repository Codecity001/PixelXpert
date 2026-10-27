package sh.siava.pixelxpert.xposed.utils.toolkit

import android.graphics.Color
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.annotation.ColorInt
import androidx.annotation.Nullable
import de.robv.android.xposed.XposedHelpers
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.text.DecimalFormat
import java.util.Arrays
import java.util.stream.Stream

@Suppress("CommentedOutCode")
object ObjectTools {
	private const val KILO = 1024
	private const val MEGA = 1024 * KILO
	private const val GIGA = 1024 * MEGA

	// Nullable because the Java took a platform `String`: a null reached
	// Integer.parseInt, threw NumberFormatException and landed in the same
	// catch below, returning the fallback. A non-null Kotlin parameter would
	// turn that into an NPE escaping this method instead.
	@JvmStatic
	fun tryParseInt(string: String?, fallbackResult: Int): Int = try {
		Integer.parseInt(string)
	} catch (ignored: Exception) {
		fallbackResult
	}

	internal fun <T> concatArrays(array1: Array<T>, array2: Array<T>): Stream<T> =
		Stream.concat(Arrays.stream(array1), Arrays.stream(array2)).distinct()

	@JvmStatic
	fun getHumanizedBytes(
		bytes: Long,
		showInBits: Boolean,
		unitSizeFactor: Float,
		unitSeparator: String,
		indicatorSymbol: String,
		@Nullable @ColorInt textColor: Int?,
	): SpannableStringBuilder {
		// Kotlin parameters are immutable, so the shifted value lives in a local
		var byteCount = bytes
		if (showInBits) {
			byteCount = byteCount shl 3
		}

		val decimalFormat: DecimalFormat
		val formattedData: CharSequence
		val spanSizeString: SpannableString
		val spanUnitString: SpannableString
		val unit: String
		if (byteCount >= GIGA) {
			unit = if (showInBits) "Gb" else "GB"
			decimalFormat = DecimalFormat("0.00")
			formattedData = decimalFormat.format(byteCount / (GIGA.toFloat()))
		} else if (byteCount >= 100 * MEGA) {
			decimalFormat = DecimalFormat("000")
			unit = if (showInBits) "Mb" else "MB"
			formattedData = decimalFormat.format(byteCount / (MEGA.toFloat()))
		} else if (byteCount >= 10 * MEGA) {
			decimalFormat = DecimalFormat("00.0")
			unit = if (showInBits) "Mb" else "MB"
			formattedData = decimalFormat.format(byteCount / (MEGA.toFloat()))
		} else if (byteCount >= MEGA) {
			decimalFormat = DecimalFormat("0.00")
			unit = if (showInBits) "Mb" else "MB"
			formattedData = decimalFormat.format(byteCount / (MEGA.toFloat()))
		} else if (byteCount >= 100 * KILO) {
			decimalFormat = DecimalFormat("000")
			unit = if (showInBits) "Kb" else "KB"
			formattedData = decimalFormat.format(byteCount / (KILO.toFloat()))
		} else if (byteCount >= 10 * KILO) {
			decimalFormat = DecimalFormat("00.0")
			unit = if (showInBits) "Kb" else "KB"
			formattedData = decimalFormat.format(byteCount / (KILO.toFloat()))
		} else {
			decimalFormat = DecimalFormat("0.00")
			unit = if (showInBits) "Kb" else "KB"
			formattedData = decimalFormat.format(byteCount / (KILO.toFloat()))
		}
		spanSizeString = SpannableString(formattedData)

		if (textColor != null) {
			spanSizeString.setSpan(
				ForegroundColorSpan(textColor),
				0,
				(formattedData).length,
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}

		spanUnitString = SpannableString(unit + indicatorSymbol)
		spanUnitString.setSpan(
			RelativeSizeSpan(unitSizeFactor),
			0,
			(unit + indicatorSymbol).length,
			Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
		return SpannableStringBuilder().append(spanSizeString).append(unitSeparator).append(spanUnitString)
	}

	@JvmStatic
	fun getStateFlowImplOf(`object`: Any): Any {
		val StateFlowImplClass = ReflectedClass.of("kotlinx.coroutines.flow.StateFlowImpl")
		val AtomicRefClass = ReflectedClass.of("kotlinx.atomicfu.AtomicRef")

		val atomicRef = ObjenesisHelper.newInstance(AtomicRefClass.clazz)
		XposedHelpers.setObjectField(atomicRef, "value", `object`)

		val stateFlow = ObjenesisHelper.newInstance(StateFlowImplClass.clazz)

		XposedHelpers.setObjectField(stateFlow, "_state", atomicRef)

		return stateFlow
	}
}
