package sh.siava.pixelxpert.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.EdgeToEdge
import androidx.activity.SystemBarStyle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sh.siava.pixelxpert.PixelXpert
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.databinding.ActivitySplashScreenBinding
import sh.siava.pixelxpert.utils.AppUtils
import sh.siava.pixelxpert.utils.MiscUtils.getColorFromAttribute

@SuppressLint("CustomSplashScreen")
class SplashScreenActivity : BaseActivity() {

    private lateinit var mBinding: ActivitySplashScreenBinding
    private val mRootCheckPassed = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        val receivedIntent = intent

        val color = getColorFromAttribute(this, R.attr.colorSurfaceContainer)
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val statusBarStyle = if (isNightMode) SystemBarStyle.dark(color) else SystemBarStyle.light(color, color)
        EdgeToEdge.enable(this, statusBarStyle, SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))

        // Root permission check
        Thread {
            if (PixelXpert.get().hasRootAccess()) {
                mRootCheckPassed.countDown()
            } else {
                if (intent?.hasExtra("FromKSU") != true) {
                    AppUtils.runKSURootActivity(this, true)
                }

                runOnUiThread {
                    MaterialAlertDialogBuilder(this@SplashScreenActivity, R.style.MaterialComponents_MaterialAlertDialog)
                        .setCancelable(false)
                        .setMessage(getText(R.string.root_access_denied))
                        .setPositiveButton(getText(R.string.exit)) { _, _ -> kotlin.system.exitProcess(0) }
                        .show()
                }
            }

            setCheckUIDone(mBinding.circularRoot.id, mBinding.doneRoot.id, mRootCheckPassed.count == 0L)
        }.start()

        // End splash screen and go to the main activity
        Thread {
            try {
                mRootCheckPassed.await()

                val app = PixelXpert.get()
                if (!app.isCoreRootServiceBound) {
                    app.tryConnectRootService()
                }

                app.mRootServiceConnected.await()
                app.awaitXposedService(3, TimeUnit.SECONDS)

                setCheckUIDone(mBinding.circularRootService.id, mBinding.doneRootService.id, app.mRootServiceConnected.count == 0L)

                Thread.sleep(800)

                if (app.mRootServiceConnected.count == 0L) {
                    val intent = Intent(this@SplashScreenActivity, SettingsActivity::class.java)
                    receivedIntent?.extras?.let { intent.putExtras(it) }
                    startActivity(intent)
                    finish()
                } else {
                    showErrorAndExit(getText(R.string.root_service_failed))
                }
            } catch (_: InterruptedException) {
            }
        }.start()
    }

    private fun showErrorAndExit(message: CharSequence) {
        runOnUiThread {
            MaterialAlertDialogBuilder(this@SplashScreenActivity, R.style.MaterialComponents_MaterialAlertDialog)
                .setCancelable(false)
                .setMessage(message)
                .setPositiveButton(getText(R.string.exit)) { _, _ -> kotlin.system.exitProcess(0) }
                .show()
        }
    }

    private fun setCheckUIDone(circularID: Int, doneImageID: Int, success: Boolean) {
        val circular = findViewById<View>(circularID)
        val doneImage = findViewById<ImageView>(doneImageID)
        runOnUiThread {
            circular?.visibility = View.GONE
            doneImage?.setImageResource(if (success) R.drawable.ic_success else R.drawable.ic_fail)
            doneImage?.visibility = View.VISIBLE
        }
    }
}
