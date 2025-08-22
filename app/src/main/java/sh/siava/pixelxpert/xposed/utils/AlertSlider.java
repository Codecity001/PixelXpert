package sh.siava.pixelxpert.xposed.utils;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools;

public abstract class AlertSlider implements SystemUtils.ChangeListener {
	private final Context context;
	private final float initialValue;
	private final float minValue;
	private final float maxValue;
	private final float stepSize;
	private final SliderEventCallback eventCallback;
	Object mSlider;
	AlertDialog sliderDialog;

	public AlertSlider(Context context, float initialValue, float minValue, float maxValue, float stepSize, SliderEventCallback eventCallback) {
		this.context = context;
		this.initialValue = initialValue;
		this.minValue = minValue;
		this.maxValue = maxValue;
		this.stepSize = stepSize;
		this.eventCallback = eventCallback;
	}

	public void show() throws Throwable {
		if(sliderDialog == null)
		{
			createSliderDialog();
		}
		sliderDialog.show();
	}

	private void createSliderDialog() throws Throwable {
		ReflectedClass AmbientVolumeSliderClass = ReflectedClass.of("com.android.systemui.accessibility.hearingaid.AmbientVolumeSlider");
		ReflectedClass SystemUIDialogClass = ReflectedClass.of("com.android.systemui.statusbar.phone.SystemUIDialog");

		sliderDialog = (AlertDialog) SystemUIDialogClass.getClazz().getConstructor(Context.class).newInstance(context);

		FrameLayout contentFrameLayout = new FrameLayout(context);
		contentFrameLayout.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

		View sliderView = (View) AmbientVolumeSliderClass.getClazz().getConstructor(Context.class).newInstance(context);

		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
		lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
		sliderView.setPadding(0, 0, 0, ResourceTools.dpToPx(context, 24));
		sliderView.setLayoutParams(lp);

		mSlider = getObjectField(sliderView, "mSlider");

		setSliderListeners(mSlider, eventCallback);

		setSliderValues(minValue, maxValue, stepSize);

		setSliderCurrentValue(initialValue);

		sliderDialog.show();
		sliderDialog.hide();

		sliderDialog.setOnDismissListener(dialog -> eventCallback.onDismiss(this));

		FrameLayout dialogInternalContainer = sliderDialog.findViewById(android.R.id.content);

		contentFrameLayout.addView(sliderView);
		dialogInternalContainer.addView(contentFrameLayout);

		eventCallback.onCreate(this);
	}

	/** @noinspection SameParameterValue*/
	public void setSliderCurrentValue(float currentValue) {
		callMethod(mSlider, "setValue", currentValue);
	}

	private void setSliderListeners(Object slider, SliderEventCallback sliderEventCallback) {
		ReflectedClass OnSliderTouchListenerClass = ReflectedClass.of("com.google.android.material.slider.Slider$OnSliderTouchListener");
		ReflectedClass OnSliderChangeListenerClass = ReflectedClass.of("com.google.android.material.slider.Slider$OnChangeListener");

		//noinspection unchecked
		List<Object> touchListeners = (List<Object>) getObjectField(slider, "touchListeners");
		//noinspection unchecked
		List<Object> changeListeners = (List<Object>) getObjectField(slider, "changeListeners");

		//Cleanup whatever listener is on this slider
		touchListeners.clear();
		changeListeners.clear();

		Object combinedSliderListener = Proxy.newProxyInstance(
				OnSliderTouchListenerClass.getClazz().getClassLoader(),
				new Class[]{OnSliderTouchListenerClass.getClazz(), OnSliderChangeListenerClass.getClazz()},
				new SliderEventListener(sliderEventCallback));

		touchListeners.add(combinedSliderListener);
		changeListeners.add(combinedSliderListener);
	}

	/** @noinspection SameParameterValue*/
	public void setSliderValues(float valueFrom, float valueTo, float stepSize) {
		setObjectField(mSlider, "valueFrom", valueFrom);
		setObjectField(mSlider, "valueTo",valueTo);
		setObjectField(mSlider, "stepSize", stepSize);

		setObjectField(mSlider, "dirtyConfig", true);
		callMethod(mSlider, "postInvalidate");
	}


	 static class SliderEventListener implements InvocationHandler {
		SliderEventCallback mCallback;
		public SliderEventListener(SliderEventCallback callback)
		{
			mCallback = callback;
		}
		/** @noinspection SuspiciousInvocationHandlerImplementation*/
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "onStartTrackingTouch":
					try
					{
						mCallback.onStartTrackingTouch(args[0]);
					}
					catch (Throwable ignored){}
					break;

				case "onStopTrackingTouch":
					try {
						mCallback.onStopTrackingTouch(args[0]);
					} catch (Throwable ignored) {}
					break;

				case "onValueChange":
					try {
						mCallback.onValueChange(args[0], (float) args[1], (boolean) args[2]);
					} catch (Throwable ignored) {}
					break;
			}
			return null;
		}
	}

	public interface SliderEventCallback
	{
		void onStartTrackingTouch(Object slider) throws Throwable;
		void onStopTrackingTouch(Object slider) throws Throwable;
		void onValueChange(Object slider, float value, boolean fromUser) throws Throwable;
		void onCreate(AlertSlider alertSlider);
		void onDismiss(AlertSlider alertSlider);
	}
}