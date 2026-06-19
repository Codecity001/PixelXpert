package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class StatusbarGestures extends XposedModPack {
	private static final int PULLDOWN_SIDE_RIGHT = 1;
	@SuppressWarnings("unused")
	private static final int PULLDOWN_SIDE_LEFT = 2;
	private static final int STATUSBAR_MODE_SHADE = 0;
	private static final int STATUSBAR_MODE_KEYGUARD = 1;
	/**
	 * @noinspection unused
	 */
	private static final int STATUSBAR_MODE_SHADE_LOCKED = 2;

	private static int pullDownSide = PULLDOWN_SIDE_RIGHT;
	private static boolean oneFingerPulldownEnabled = false;
	private boolean oneFingerPullupEnabled = false;
	private static float statusbarPortion = 0.25f; // now set to 25% of the screen. it can be anything between 0 to 100%
	private Object NotificationPanelViewController;
	GestureDetector mGestureDetector;
	private boolean StatusbarLongpressAppSwitch = false;
	private MotionEvent mDownEvent;
	@SuppressLint("StaticFieldLeak")
	private static StatusbarGestures instance;
	private Object ShadeInteractorSceneContainerImpl;

	public StatusbarGestures(Context context) {
		super(context);
		instance = this;
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;
		oneFingerPulldownEnabled = Xprefs.getBoolean("QSPullodwnEnabled", false);
		oneFingerPullupEnabled = oneFingerPulldownEnabled && Xprefs.getBoolean("oneFingerPullupEnabled", false);
		statusbarPortion = Xprefs.getSliderInt("QSPulldownPercent", 25) / 100f;
		pullDownSide = Integer.parseInt(Xprefs.getString("QSPulldownSide", "1"));

		StatusbarLongpressAppSwitch = Xprefs.getBoolean("StatusbarLongpressAppSwitch", false);
	}

	public static void collapseQSPanel()
	{
		if(instance != null)
		{
			instance.collapseQS();
		}
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass NotificationPanelViewControllerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationPanelViewController"); //Pre 17QPR1
		ReflectedClass PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView");

		//17QPR1
		ReflectedClass ShadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl");
		ReflectedClass ShadeSurfaceImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ShadeSurfaceImpl");

		ShadeSurfaceImplClass
				.before("onStatusBarLongPress")
				.run(this::onStatusBarLongPress);

		ShadeInteractorSceneContainerImplClass
				.afterConstruction()
				.run(param -> ShadeInteractorSceneContainerImpl = param.thisObject);

		mGestureDetector = new GestureDetector(mContext, getPullDownLPListener());

		PhoneStatusBarViewClass
				.after("onTouchEvent")
				.run(param -> {
					if (!oneFingerPulldownEnabled) return;

					MotionEvent event =
							param.args[0] instanceof MotionEvent
									? (MotionEvent) param.args[0]
									: (MotionEvent) param.args[1];

					mGestureDetector.onTouchEvent(event);
				});

		GestureDetector pullUpDetector = new GestureDetector(mContext, getPullUpListener());

		final long[] lastPullupTouchTime = {0};

		NotificationPanelViewControllerClass //Pre 17QPR1
				.before("onStatusBarLongPress")
				.run(this::onStatusBarLongPress);

		NotificationPanelViewControllerClass //Pre 17QPR1
				.afterConstruction()
				.run(param -> {
					NotificationPanelViewController = param.thisObject;
					Object mTouchHandler = getObjectField(param.thisObject, "mTouchHandler");
					ReflectedClass.of(mTouchHandler.getClass())
							.before("onTouchEvent")
							.run(param2 -> {
								MotionEvent motionEvent = (MotionEvent) param2.args[0];

								if (oneFingerPullupEnabled
										&& STATUSBAR_MODE_KEYGUARD != (int) getObjectField(NotificationPanelViewController, "mBarState")) {
									if(SystemClock.uptimeMillis() - lastPullupTouchTime[0] > 1000)
									{
										motionEvent.setAction(MotionEvent.ACTION_DOWN);
										lastPullupTouchTime[0] = SystemClock.uptimeMillis();
										mDownEvent = MotionEvent.obtain(motionEvent);
										return;
									}
									else if (MotionEvent.ACTION_UP == motionEvent.getAction()) {
										lastPullupTouchTime[0] = 0;
									}
									pullUpDetector.onTouchEvent(motionEvent);
								}
							});
				});
	}

	private void onStatusBarLongPress(HookHelper.RunParam param) {
		if (StatusbarLongpressAppSwitch) {
			sendAppSwitchBroadcast();
			param.setResult(null);
		}
	}

	//speedfactor & heightfactor are based on display height
	private boolean isValidFling(MotionEvent e1, MotionEvent e2, float velocityY, float speedFactor, float heightFactor) {
		//noinspection DataFlowIssue
		Rect displayBounds = SystemUtils.WindowManager().getCurrentWindowMetrics().getBounds();
		try {
			return ((e2.getY() - e1.getY()) / heightFactor) > displayBounds.height() //enough travel in right direction
					&& isTouchInRegion(e1, displayBounds.width()) //start point in hot zone
					&& (velocityY / speedFactor > displayBounds.height()); //enough speed in right direction
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isTouchInRegion(MotionEvent motionEvent, float width) {
		float x = motionEvent.getX();
		float region = width * statusbarPortion;

		return (pullDownSide == PULLDOWN_SIDE_RIGHT)
				? width - region < x
				: x < region;
	}

	private void sendAppSwitchBroadcast() {
		new Thread(() -> mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())).start();
	}

	private GestureDetector.OnGestureListener getPullDownLPListener() {
		return new GestureListener() {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (isStatusbarClosed()
						&& isValidFling(e1, e2, velocityY, .15f, 0.01f)) {
					if(NotificationPanelViewController != null) { //Pre 17QPR1
						callMethod(NotificationPanelViewController, "expandToQs");
					} else {
						callMethod(ShadeInteractorSceneContainerImpl, "expandQuickSettingsShade", "asdf", null);
					}
					return true;
				}
				return false;
			}
		};
	}

	@SuppressWarnings("ConstantValue")
	private boolean isStatusbarClosed()
	{
		return  (NotificationPanelViewController != null && STATUSBAR_MODE_SHADE == (int) getObjectField(NotificationPanelViewController, "mBarState")
		||  ShadeInteractorSceneContainerImpl != null); //touch only gets here if statusbar is closed
	}

	private GestureDetector.OnGestureListener getPullUpListener() {
		return new GestureListener() {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (isValidFling(mDownEvent, e2, velocityY, -.15f, -.06f)) {
					collapseQS();
					return true;
				}
				return false;
			}
		};
	}

	private void collapseQS() { //for now only used on pre 17QPR1
		try
		{
			callMethod(NotificationPanelViewController, "collapse", true, 1f);
		}
		catch (Throwable ignored) {
			callMethod(NotificationPanelViewController, "collapse", 1f, true);
		}
	}

	private static class GestureListener implements GestureDetector.OnGestureListener {
		@Override
		public boolean onDown(@NonNull MotionEvent e) {
			return false;
		}

		@Override
		public void onShowPress(@NonNull MotionEvent e) {
		}

		@Override
		public boolean onSingleTapUp(@NonNull MotionEvent e) {
			return false;
		}

		@Override
		public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
			return false;
		}

		@Override
		public void onLongPress(@NonNull MotionEvent e) {}

		@Override
		public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
			return false;
		}
	}
}