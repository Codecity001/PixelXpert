package sh.siava.pixelxpert.ui.activities;

import static sh.siava.pixelxpert.utils.MiscUtils.getColorFromAttribute;
import static sh.siava.pixelxpert.Constants.LAUNCH_REASON_EXTRA;
import static sh.siava.pixelxpert.Constants.LAUNCH_REASON_XPOSED_ENABLED;
import static sh.siava.pixelxpert.Constants.LAUNCH_REASON_XPOSED_SERVICE_FAIL;
import static sh.siava.pixelxpert.Constants.XPOSED_CLI_PATH;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.CountDownLatch;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.databinding.ActivitySplashScreenBinding;
import sh.siava.pixelxpert.utils.AppUtils;

@SuppressLint("CustomSplashScreen")
public class SplashScreenActivity extends BaseActivity {
	/**
	 * @noinspection unused
	 */
	String TAG = getClass().getSimpleName();
	private ActivitySplashScreenBinding mBinding;
	private final CountDownLatch mRootCheckPassed = new CountDownLatch(1);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		mBinding = ActivitySplashScreenBinding.inflate(getLayoutInflater());
		setContentView(mBinding.getRoot());

		Intent receivedIntent = getIntent();

		final String launchReason = receivedIntent.getStringExtra(LAUNCH_REASON_EXTRA);

		int color = getColorFromAttribute(this, R.attr.colorSurfaceContainer);
		boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		SystemBarStyle statusBarStyle = isNightMode ? SystemBarStyle.dark(color) : SystemBarStyle.light(color, color);
		EdgeToEdge.enable(this, statusBarStyle, SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT));

		// Root permission check
		new Thread(() -> {
			if (PixelXpert.get().hasRootAccess()) {
				mRootCheckPassed.countDown();
			} else {
				if (!getIntent().hasExtra("FromKSU")) {
					AppUtils.runKSURootActivity(this, true);
				}

				runOnUiThread(() ->
						new MaterialAlertDialogBuilder(SplashScreenActivity.this, R.style.MaterialComponents_MaterialAlertDialog)
								.setCancelable(false)
								.setMessage(getText(R.string.root_access_denied))
								.setPositiveButton(getText(R.string.exit), (dialog, i) -> System.exit(0))
								.show());
			}

			// Update the UI
			setCheckUIDone(mBinding.circularRoot.getId(), mBinding.doneRoot.getId(), mRootCheckPassed.getCount() == 0);
		}).start();

		// End splash screen and go to the main activity
		new Thread(() -> {
			try {
				// Wait for all checks to pass and for all operations to finish
				mRootCheckPassed.await();

				PixelXpert app = PixelXpert.get();
				if (!PixelXpert.get().isCoreRootServiceBound()) {
					app.tryConnectRootService();
				}

				app.mRootServiceConnected.await();

				if(LAUNCH_REASON_XPOSED_SERVICE_FAIL.equals(launchReason))
				{
					PixelXpert.get().runRootCommand(String.format("%s modules enable %s",XPOSED_CLI_PATH, BuildConfig.APPLICATION_ID));

					//giving lsposed a bit of time to understand the command. Otherwise, our restart will be useless
					Thread.sleep(1000);
					AppUtils.restartSelf(LAUNCH_REASON_XPOSED_ENABLED);
				}

				//at this point we know we've got root
				if(!checkXposedInstalled())
				{
					showErrorAndExit(getText(R.string.lsposed_not_found));
					return;
				}
				// Update the UI
				setCheckUIDone(mBinding.circularRootService.getId(), mBinding.doneRootService.getId(), app.mRootServiceConnected.getCount() == 0);

				// This is just for aesthetics: I don't want the splashscreen to be too fast
				Thread.sleep(1000);

				if (app.mRootServiceConnected.getCount() == 0) {
					// Start the main activity
					Intent intent = new Intent(SplashScreenActivity.this, SettingsActivity.class);
					Bundle extras = receivedIntent.getExtras();
					if(extras != null)
					{
						intent.putExtras(extras);
					}

					startActivity(intent);
					finish();
				} else {
					showErrorAndExit(getText(R.string.root_service_failed));
				}
			} catch (InterruptedException ignored) {}
		}).start();
	}

	private void showErrorAndExit(CharSequence message) {
		runOnUiThread(() ->
				new MaterialAlertDialogBuilder(SplashScreenActivity.this, R.style.MaterialComponents_MaterialAlertDialog)
						.setCancelable(false)
						.setMessage(message)
						.setPositiveButton(getText(R.string.exit), (dialog, i) -> System.exit(0))
						.show());
	}

	private void setCheckUIDone(int circularID, int doneImageID, boolean success) {
		View circular = findViewById(circularID);
		ImageView doneImage = findViewById(doneImageID);
		runOnUiThread(() -> {
			circular.setVisibility(View.GONE);
			doneImage.setImageResource(success ? R.drawable.ic_success : R.drawable.ic_fail);
			doneImage.setVisibility(View.VISIBLE);
		});
	}

	private boolean checkXposedInstalled() {
		String[] checkResult = PixelXpert.get().runRootCommand(String.format("[ -f \"%s\" ] && echo \"1\"", XPOSED_CLI_PATH));
		return checkResult.length > 0 && checkResult[0].equals("1");
	}
}
