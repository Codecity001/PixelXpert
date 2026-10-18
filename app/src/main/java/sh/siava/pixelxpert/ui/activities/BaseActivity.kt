package sh.siava.pixelxpert.ui.activities

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.EdgeToEdge
import androidx.activity.SystemBarStyle
import java.util.Locale
import sh.siava.pixelxpert.PixelXpert

open class BaseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdge.enable(
            this,
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        runCatching {
            val prefs = PixelXpert.get().defaultPreferences
            val localeCode = prefs.getString("appLanguage", "") ?: ""
            val locale = if (localeCode.isNotEmpty()) Locale.forLanguageTag(localeCode) else Locale.getDefault()
            val res = newBase.resources
            val configuration = res.configuration
            configuration.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            configuration.setLocales(localeList)
            applyOverrideConfiguration(configuration)
        }
    }
}
