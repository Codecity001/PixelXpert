package sh.siava.pixelxpert.ui.activities

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class FakeSplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { true }
        val receivedIntent = intent
        val splashIntent = Intent(this, SplashScreenActivity::class.java)
        if (receivedIntent != null) {
            val extras = receivedIntent.extras
            if (extras != null) {
                splashIntent.putExtras(extras)
            }
            val cn = receivedIntent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
            if (cn != null) {
                splashIntent.putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            }
        }
        startActivity(splashIntent)
        finish()
    }
}
