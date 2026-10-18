package sh.siava.pixelxpert.xposed.utils.toolkit

import android.graphics.Color
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.text.DecimalFormat
import java.util.Random
import java.util.regex.Pattern
import java.util.stream.Stream
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

object ObjectTools {
    private const val KILO = 1024
    private const val MEGA = 1024 * KILO
    private const val GIGA = 1024 * MEGA

    @JvmStatic
    fun tryParseInt(string: String?, fallbackResult: Int): Int {
        return string?.toIntOrNull() ?: fallbackResult
    }

    @JvmStatic
    fun <T> concatArrays(array1: Array<T>, array2: Array<T>): Stream<T> {
        return Stream.concat(array1.toList().stream(), array2.toList().stream()).distinct()
    }

    @JvmStatic
    fun getHumanizedBytes(
        bytesIn: Long,
        showInBits: Boolean,
        unitSizeFactor: Float,
        unitSeparator: String,
        indicatorSymbol: String,
        @ColorInt textColor: Int?
    ): SpannableStringBuilder {
        var bytes = bytesIn
        if (showInBits) {
            bytes = bytes shl 3
        }

        val decimalFormat: DecimalFormat
        val formattedData: CharSequence
        val unit: String

        if (bytes >= GIGA) {
            unit = if (showInBits) "Gb" else "GB"
            decimalFormat = DecimalFormat("0.00")
            formattedData = decimalFormat.format(bytes / GIGA.toFloat())
        } else if (bytes >= 100 * MEGA) {
            decimalFormat = DecimalFormat("000")
            unit = if (showInBits) "Mb" else "MB"
            formattedData = decimalFormat.format(bytes / MEGA.toFloat())
        } else if (bytes >= 10 * MEGA) {
            decimalFormat = DecimalFormat("00.0")
            unit = if (showInBits) "Mb" else "MB"
            formattedData = decimalFormat.format(bytes / MEGA.toFloat())
        } else if (bytes >= MEGA) {
            decimalFormat = DecimalFormat("0.00")
            unit = if (showInBits) "Mb" else "MB"
            formattedData = decimalFormat.format(bytes / MEGA.toFloat())
        } else if (bytes >= 100 * KILO) {
            decimalFormat = DecimalFormat("000")
            unit = if (showInBits) "Kb" else "KB"
            formattedData = decimalFormat.format(bytes / KILO.toFloat())
        } else if (bytes >= 10 * KILO) {
            decimalFormat = DecimalFormat("00.0")
            unit = if (showInBits) "Kb" else "KB"
            formattedData = decimalFormat.format(bytes / KILO.toFloat())
        } else {
            decimalFormat = DecimalFormat("0.00")
            unit = if (showInBits) "Kb" else "KB"
            formattedData = decimalFormat.format(bytes / KILO.toFloat())
        }

        val spanSizeString = SpannableString(formattedData)
        if (textColor != null) {
            spanSizeString.setSpan(
                NetworkTraffic.TrafficStyle(textColor),
                0,
                formattedData.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val spanUnitString = SpannableString(unit + indicatorSymbol)
        spanUnitString.setSpan(
            RelativeSizeSpan(unitSizeFactor),
            0,
            (unit + indicatorSymbol).length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return SpannableStringBuilder().append(spanSizeString).append(unitSeparator).append(spanUnitString)
    }

    @JvmStatic
    fun isColorDark(@ColorInt color: Int): Boolean {
        return ColorUtils.calculateLuminance(color) < 0.5
    }

    @JvmStatic
    fun removeItemFromCommaString(string: String, key: String): String {
        return string.replace(getCommaSearchPattern(key).toRegex(), "$2$3$5")
    }

    @JvmStatic
    fun addItemToCommaStringIfNotPresent(string: String, key: String): String {
        if (Pattern.matches(getCommaSearchPattern(key), string)) return string
        return "$key${if (string.isNotEmpty()) "," else ""}$string"
    }

    private fun getCommaSearchPattern(tile: String): String {
        return "^($tile,)(.+)|(.+)(,$tile)(,.+|$)"
    }

    @ColorInt
    @JvmStatic
    fun getRandomColor(): Int {
        val rnd = Random()
        return Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
    }

    @JvmStatic
    fun getStateFlowImplOf(obj: Any): Any? {
        return runCatching {
            val stateFlowImplClass = ReflectedClass.of("kotlinx.coroutines.flow.StateFlowImpl")
            val atomicRefClass = ReflectedClass.of("kotlinx.atomicfu.AtomicRef")

            val atomicRef = ObjenesisHelper.newInstance(atomicRefClass.clazz)
            val valueField = atomicRef.javaClass.getDeclaredField("value").apply { isAccessible = true }
            valueField.set(atomicRef, obj)

            val stateFlow = ObjenesisHelper.newInstance(stateFlowImplClass.clazz)
            val stateField = stateFlow.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            stateField.set(stateFlow, atomicRef)

            stateFlow
        }.getOrNull()
    }
}
