package sh.siava.pixelxpert.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.util.Log
import android.view.View

object TextUtils {

    @JvmStatic
    fun getClickableText(activity: Activity, message: String, link: String): SpannableString {
        val spannableMessage = SpannableString(message)

        val start = message.indexOf(link)
        if (start < 0) return spannableMessage
        val end = start + link.length

        spannableMessage.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    activity.startActivity(intent)
                } catch (exception: Exception) {
                    Log.e("IconPackRepo", "Browser not found")
                }
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        Linkify.addLinks(spannableMessage, Linkify.WEB_URLS)

        return spannableMessage
    }
}
