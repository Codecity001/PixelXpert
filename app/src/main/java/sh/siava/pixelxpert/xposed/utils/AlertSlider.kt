package sh.siava.pixelxpert.xposed.utils

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Puts SystemUI's own alert-slider widget (the one behind the ambient-volume
 * accessibility slider) on screen inside a SystemUI-styled dialog, so a quick
 * settings tile can offer a volume or flashlight slider without
 * reimplementing the widget. The layout, the dialog and the material slider
 * behind it are all reached through reflection: whatever listeners the slider
 * already carries are wiped, a single combined proxy takes their place, the
 * range is pushed in, and only then do the callbacks fire. Subclasses override
 * [SystemUtils.ChangeListener.onChanged] to receive the value while the
 * dialog is up.
 */
abstract class AlertSlider(
	private val context: Context,
	private val initialValue: Float,
	private val minValue: Float,
	private val maxValue: Float,
	private val stepSize: Float,
	private val eventCallback: SliderEventCallback,
) : SystemUtils.ChangeListener {
	// Kotlin has no package-private; `internal` is the closest equivalent.
	internal var mSlider: Any? = null
	internal var sliderDialog: AlertDialog? = null

	/**
	 * Builds the dialog on first use, then shows it. Note that on the very
	 * first call [createSliderDialog] has already shown the dialog once, so it
	 * gets shown twice.
	 */
	@Throws(Throwable::class)
	fun show() {
		if (sliderDialog == null) {
			createSliderDialog()
		}
		sliderDialog!!.show()
	}

	@Throws(Throwable::class)
	private fun createSliderDialog() {
		val AmbientVolumeLayoutClass =
			ReflectedClass.of("com.android.systemui.accessibility.hearingaid.AmbientVolumeLayout")

		sliderDialog = getSystemUIDialog()

		val contentFrameLayout = FrameLayout(context)
		contentFrameLayout.layoutParams =
			FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

		// sliderview has removed the constructor. attacking it from a higher level
		val sliderLayout =
			AmbientVolumeLayoutClass.clazz!!.getConstructor(Context::class.java).newInstance(context) as View

		callMethod(sliderLayout, "createSlider", 0)

		@Suppress("UNCHECKED_CAST")
		val slidersMaps = getObjectField(sliderLayout, "mSideToSliderMap") as Map<Any?, Any?>
		val sliderView = slidersMaps.values.iterator().next() as View

		val mTitle = sliderView.findViewById<TextView>(SystemUtils.idOf("ambient_volume_slider_title"))
		mTitle.text = " " // setting the title to blank

		val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
		sliderView.setPadding(0, 0, 0, ResourceTools.dpToPx(context, 24))
		sliderView.layoutParams = lp

		mSlider = getObjectField(sliderView, "mSlider")

		setSliderListeners(mSlider, eventCallback)

		setSliderValues(minValue, maxValue, stepSize)

		setSliderCurrentValue(initialValue)

		// the dialog is shown and immediately hidden before the views move in
		sliderDialog!!.show()
		sliderDialog!!.hide()

		sliderDialog!!.setOnDismissListener { eventCallback.onDismiss(this) }

		val dialogInternalContainer = sliderDialog!!.findViewById<FrameLayout>(android.R.id.content)

		contentFrameLayout.addView(sliderView)
		dialogInternalContainer.addView(contentFrameLayout)

		eventCallback.onCreate(this)
	}

	@Throws(
		IllegalAccessException::class,
		InstantiationException::class,
		InvocationTargetException::class,
		NoSuchMethodException::class,
	)
	@Suppress("DEPRECATION")
	private fun getSystemUIDialog(): AlertDialog {
		try {
			val SystemUIDialogClass = ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog")
			return SystemUIDialogClass.clazz!!.getConstructor(Context::class.java).newInstance(context) as AlertDialog
		} catch (_: Throwable) {
		}

		try {
			val SystemUIDialogFactoryClass =
				ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog\$Factory")
			val ctor = SystemUIDialogFactoryClass.clazz!!.constructors[0]
			val args = ctor.parameterTypes.map { paramType ->
				when {
					paramType.isAssignableFrom(context.javaClass) || paramType == Context::class.java -> context

					paramType.name.contains("SystemUIDialogManager") ->
						SystemUIDependencyProvider.get("com.android.systemui.statusbar.phone.SystemUIDialogManager")

					paramType.name.contains("BroadcastDispatcher") ->
						SystemUIDependencyProvider.get("com.android.systemui.broadcast.BroadcastDispatcher")

					paramType.name.contains("DialogTransitionAnimator") ->
						SystemUIDependencyProvider.get("com.android.systemui.animation.DialogTransitionAnimator")

					else -> null
				}
			}.toTypedArray()
			val factory = ctor.newInstance(*args)
			return callMethod(factory, "create") as AlertDialog
		} catch (_: Throwable) {
		}

		val dialog = AlertDialog.Builder(context).create()
		try {
			val SystemUIDialogClass = ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog")
			callMethod(SystemUIDialogClass.clazz, "applyFlags", dialog, true)
		} catch (_: Throwable) {
			dialog.window?.apply {
				setType(2017) // WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
				addFlags(
					WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
						WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
				)
			}
		}
		return dialog
	}

	/** @noinspection SameParameterValue*/
	fun setSliderCurrentValue(currentValue: Float) {
		callMethod(mSlider, "setValue", currentValue)
	}

	private fun setSliderListeners(slider: Any?, sliderEventCallback: SliderEventCallback) {
		val OnSliderTouchListenerClass =
			ReflectedClass.of("com.google.android.material.slider.Slider\$OnSliderTouchListener")
		val OnSliderChangeListenerClass =
			ReflectedClass.of("com.google.android.material.slider.Slider\$OnChangeListener")

		@Suppress("UNCHECKED_CAST")
		val touchListeners = getObjectField(slider, "touchListeners") as MutableList<Any?>

		@Suppress("UNCHECKED_CAST")
		val changeListeners = getObjectField(slider, "changeListeners") as MutableList<Any?>

		touchListeners.clear()
		changeListeners.clear()

		val combinedSliderListener = Proxy.newProxyInstance(
			OnSliderTouchListenerClass.clazz!!.classLoader,
			arrayOf(OnSliderTouchListenerClass.clazz!!, OnSliderChangeListenerClass.clazz!!),
			SliderEventListener(sliderEventCallback),
		)

		touchListeners.add(combinedSliderListener)
		changeListeners.add(combinedSliderListener)
	}

	/** @noinspection SameParameterValue*/
	fun setSliderValues(valueFrom: Float, valueTo: Float, stepSize: Float) {
		setObjectField(mSlider, "valueFrom", valueFrom)
		setObjectField(mSlider, "valueTo", valueTo)
		setObjectField(mSlider, "stepSize", stepSize)

		setObjectField(mSlider, "dirtyConfig", true)
		callMethod(mSlider, "postInvalidate")
	}

	internal class SliderEventListener(internal val mCallback: SliderEventCallback) : InvocationHandler {
		/**
		 * @noinspection SuspiciousInvocationHandlerImplementation
		 */
		override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
			when (method.name) {
				"onStartTrackingTouch" -> try {
					mCallback.onStartTrackingTouch(args!![0])
				} catch (ignored: Throwable) {
				}

				"onStopTrackingTouch" -> try {
					mCallback.onStopTrackingTouch(args!![0])
				} catch (ignored: Throwable) {
				}

				"onValueChange" -> try {
					mCallback.onValueChange(args!![0], args!![1] as Float, args!![2] as Boolean)
				} catch (ignored: Throwable) {
				}
			}
			return null
		}
	}

	interface SliderEventCallback {
		@Throws(Throwable::class)
		fun onStartTrackingTouch(slider: Any?)

		@Throws(Throwable::class)
		fun onStopTrackingTouch(slider: Any?)

		@Throws(Throwable::class)
		fun onValueChange(slider: Any?, value: Float, fromUser: Boolean)

		fun onCreate(alertSlider: AlertSlider)

		fun onDismiss(alertSlider: AlertSlider)
	}
}
