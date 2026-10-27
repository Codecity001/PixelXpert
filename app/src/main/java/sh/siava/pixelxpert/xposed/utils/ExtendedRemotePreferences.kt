package sh.siava.pixelxpert.xposed.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.crossbowffs.remotepreferences.RemotePreferences
import sh.siava.pixelxpert.data.PrefStore
import java.util.Collections

/**
 * The preferences handle every mod pack reads through.
 *
 * It layers two things over the plain [RemotePreferences] the library gives us:
 *
 *  1. a fan-out list of listeners, so more than one pack can observe changes
 *     (the library keeps its own single slot), and
 *  2. a gate on [PrefStore.LEGACY_INIT_MARKER] - the module app writes that key
 *     once its own store is populated, and until then changes are withheld so a
 *     pack never sees half a preference set.
 *
 * That marker is the same constant the app side writes, which is what keeps the
 * two halves agreeing on when "ready" happens.
 *
 * The slider helpers forward to [SliderValues], the in-module copy of the
 * RangeSlider statics rather than a separate dependency.
 */
class ExtendedRemotePreferences(context: Context, authority: String, prefFileName: String, strictMode: Boolean) :
	RemotePreferences(context, authority, prefFileName, strictMode) {

	@JvmField
	val mOnSharedPreferenceChangeListeners: MutableList<SharedPreferences.OnSharedPreferenceChangeListener> =
		Collections.synchronizedList(ArrayList())

	@JvmField
	var mIsPrefsInitiated: Boolean = false

	private var mListenerRegistered = false

	/*
	 * Must stay a field: RemotePreferences registers this with the content
	 * resolver, and a lambda handed over inline would be collectable the moment
	 * this method returns.
	 */
	private val l =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (PrefStore.LEGACY_INIT_MARKER == key) {
				mIsPrefsInitiated = getBoolean(PrefStore.LEGACY_INIT_MARKER, false)
			}

			if (mIsPrefsInitiated && mOnSharedPreferenceChangeListeners.isNotEmpty()) {
				synchronized(mOnSharedPreferenceChangeListeners) {
					mOnSharedPreferenceChangeListeners.toList()
				}.forEach { it.onSharedPreferenceChanged(this, key) }
			}
		}

	override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
		if (listener == null) return
		if (!mListenerRegistered) initListener()

		mOnSharedPreferenceChangeListeners.add(listener)
	}

	override fun unregisterOnSharedPreferenceChangeListener(
		listener: SharedPreferences.OnSharedPreferenceChangeListener?,
	) {
		if (listener == null) return
		mOnSharedPreferenceChangeListeners.remove(listener)
	}

	private fun initListener() {
		mIsPrefsInitiated = super.getBoolean(PrefStore.LEGACY_INIT_MARKER, false)
		super.registerOnSharedPreferenceChangeListener(l)
		mListenerRegistered = true
	}

	override fun getInt(key: String, defValue: Int): Int = try {
		super.getInt(key, defValue)
	} catch (e: Exception) {
		val str = try {
			getString(key, null)
		} catch (_: Exception) {
			null
		}
		if (str != null) {
			str.toIntOrNull()
				?: runCatching { Color.parseColor(str) }.getOrNull()
				?: str.removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toInt()
				?: defValue
		} else {
			defValue
		}
	}

	/** An int-typed slider's stored value. */
	fun getSliderInt(key: String, defaultVal: Int): Int = SliderValues.getSingleIntValue(this, key, defaultVal)

	/** A float-typed slider's stored value. */
	fun getSliderFloat(key: String, defaultVal: Float): Float = SliderValues.getSingleFloatValue(this, key, defaultVal)

	/** Every value of a range slider stored under [key]. */
	fun getSliderValues(key: String, defaultValue: Float): List<Float> = SliderValues.getValues(this, key, defaultValue)
}

/** Only used to name the marker in this file's docs; keeps the import honest. */
private const val unusedDocAnchor = 0
