package sh.siava.pixelxpert.xposed.utils

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools

abstract class AlertSlider(
    private val context: Context,
    private val initialValue: Float,
    private val minValue: Float,
    private val maxValue: Float,
    private val stepSize: Float,
    private val eventCallback: SliderEventCallback
) : SystemUtils.ChangeListener {

    var mSlider: Any? = null
    var sliderDialog: AlertDialog? = null

    fun show() {
        if (sliderDialog == null) {
            createSliderDialog()
        }
        sliderDialog?.show()
    }

    private fun createSliderDialog() {
        val ambientVolumeLayoutClass = ReflectedClass.of("com.android.systemui.accessibility.hearingaid.AmbientVolumeLayout")

        sliderDialog = getSystemUIDialog()

        val contentFrameLayout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val sliderLayout = ambientVolumeLayoutClass.clazz?.getConstructor(Context::class.java)?.newInstance(context) as? View ?: return
        HookHelper.callMethod<Any>(sliderLayout, "createSlider", 0)

        val slidersMaps = HookHelper.getObjectField<Map<*, *>>(sliderLayout, "mSideToSliderMap") ?: return
        val sliderView = slidersMaps.values.firstOrNull() as? View ?: return

        val mTitle = sliderView.findViewById<TextView>(idOf("ambient_volume_slider_title"))
        mTitle?.text = " "

        val lp = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        sliderView.setPadding(0, 0, 0, ResourceTools.dpToPx(context, 24))
        sliderView.layoutParams = lp

        mSlider = HookHelper.getObjectField<Any>(sliderView, "mSlider")

        mSlider?.let { setSliderListeners(it, eventCallback) }
        setSliderValues(minValue, maxValue, stepSize)
        setSliderCurrentValue(initialValue)

        sliderDialog?.show()
        sliderDialog?.hide()

        sliderDialog?.setOnDismissListener { eventCallback.onDismiss(this) }

        val dialogInternalContainer = sliderDialog?.findViewById<FrameLayout>(android.R.id.content)
        contentFrameLayout.addView(sliderView)
        dialogInternalContainer?.addView(contentFrameLayout)

        eventCallback.onCreate(this)
    }

    private fun getSystemUIDialog(): AlertDialog? {
        val direct = runCatching {
            val systemUIDialogClass = ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog")
            systemUIDialogClass.clazz?.getConstructor(Context::class.java)?.newInstance(context) as? AlertDialog
        }.getOrNull()

        if (direct != null) return direct

        return runCatching {
            val systemUIDialogFactoryClass = ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog\$Factory")
            val constructor = systemUIDialogFactoryClass.clazz?.constructors?.firstOrNull()
            val factory = constructor?.newInstance(
                context,
                SystemUIDependencyProvider.get("com.android.systemui.statusbar.phone.SystemUIDialogManager"),
                SystemUIDependencyProvider.get("com.android.systemui.broadcast.BroadcastDispatcher"),
                SystemUIDependencyProvider.get("com.android.systemui.animation.DialogTransitionAnimator"),
                null
            )
            factory?.let { HookHelper.callMethod<AlertDialog>(it, "create") }
        }.getOrNull()
    }

    fun setSliderCurrentValue(currentValue: Float) {
        mSlider?.let { HookHelper.callMethod<Any>(it, "setValue", currentValue) }
    }

    private fun setSliderListeners(slider: Any, sliderEventCallback: SliderEventCallback) {
        val onSliderTouchListenerClass = ReflectedClass.of("com.google.android.material.slider.Slider\$OnSliderTouchListener")
        val onSliderChangeListenerClass = ReflectedClass.of("com.google.android.material.slider.Slider\$OnChangeListener")

        val touchListeners = HookHelper.getObjectField<MutableList<Any>>(slider, "touchListeners") ?: return
        val changeListeners = HookHelper.getObjectField<MutableList<Any>>(slider, "changeListeners") ?: return

        touchListeners.clear()
        changeListeners.clear()

        val touchClazz = onSliderTouchListenerClass.clazz ?: return
        val changeClazz = onSliderChangeListenerClass.clazz ?: return

        val combinedSliderListener = Proxy.newProxyInstance(
            touchClazz.classLoader,
            arrayOf(touchClazz, changeClazz),
            SliderEventListener(sliderEventCallback)
        )

        touchListeners.add(combinedSliderListener)
        changeListeners.add(combinedSliderListener)
    }

    fun setSliderValues(valueFrom: Float, valueTo: Float, stepSize: Float) {
        mSlider?.let {
            HookHelper.setObjectField(it, "valueFrom", valueFrom)
            HookHelper.setObjectField(it, "valueTo", valueTo)
            HookHelper.setObjectField(it, "stepSize", stepSize)
            HookHelper.setObjectField(it, "dirtyConfig", true)
            HookHelper.callMethod<Any>(it, "postInvalidate")
        }
    }

    private class SliderEventListener(private val mCallback: SliderEventCallback) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            val nonNullArgs = args ?: emptyArray()
            when (method.name) {
                "onStartTrackingTouch" -> {
                    runCatching { if (nonNullArgs.isNotEmpty()) mCallback.onStartTrackingTouch(nonNullArgs[0]) }
                }
                "onStopTrackingTouch" -> {
                    runCatching { if (nonNullArgs.isNotEmpty()) mCallback.onStopTrackingTouch(nonNullArgs[0]) }
                }
                "onValueChange" -> {
                    runCatching {
                        if (nonNullArgs.size >= 3) {
                            mCallback.onValueChange(nonNullArgs[0], nonNullArgs[1] as Float, nonNullArgs[2] as Boolean)
                        }
                    }
                }
            }
            return null
        }
    }

    interface SliderEventCallback {
        fun onStartTrackingTouch(slider: Any)
        fun onStopTrackingTouch(slider: Any)
        fun onValueChange(slider: Any, value: Float, fromUser: Boolean)
        fun onCreate(alertSlider: AlertSlider)
        fun onDismiss(alertSlider: AlertSlider)
    }
}
