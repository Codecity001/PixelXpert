package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

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

	public StatusbarGestures(Context context) {
		super(context);
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

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass NotificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController");
		ReflectedClass PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView");

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

		NotificationPanelViewControllerClass
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

	private void onStatusbarLongpress() {
		if (StatusbarLongpressAppSwitch) {
			sendAppSwitchBroadcast();
		}
	}

	private void sendAppSwitchBroadcast() {
		new Thread(() -> mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())).start();
	}

	private GestureDetector.OnGestureListener getPullDownLPListener() {
		return new LongpressListener(true) {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (STATUSBAR_MODE_SHADE == (int) getObjectField(NotificationPanelViewController, "mBarState")
						&& isValidFling(e1, e2, velocityY, .15f, 0.01f)) {
					callMethod(NotificationPanelViewController, "expandToQs");
					return true;
				}
				return false;
			}
		};
	}

	private GestureDetector.OnGestureListener getPullUpListener() {
		return new LongpressListener(false) {
			@Override
			public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
				if (isValidFling(mDownEvent, e2, velocityY, -.15f, -.06f)) {
					callMethod(NotificationPanelViewController, "collapse", 1f, true);
					return true;
				}
				return false;
			}
		};
	}

	private class LongpressListener implements GestureDetector.OnGestureListener {
		final boolean mDetectLongpress;

		public LongpressListener(boolean detectLongpress) {
			mDetectLongpress = detectLongpress;
		}

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
		public void onLongPress(@NonNull MotionEvent e) {
			if (mDetectLongpress)
				onStatusbarLongpress();
		}

		@Override
		public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
			return false;
		}
	}
}