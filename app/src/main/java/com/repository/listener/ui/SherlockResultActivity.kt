package com.repository.listener.ui

import android.os.Bundle
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.repository.listener.R
import org.json.JSONObject

class SherlockResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESULT_JSON = "result_json"
        const val EXTRA_PHONE_NUMBER = "phone_number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sherlock_result)

        val resultJson = intent.getStringExtra(EXTRA_RESULT_JSON)
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""

        if (resultJson == null) {
            finish()
            return
        }

        val textPhoneNumber = findViewById<TextView>(R.id.textPhoneNumber)
        val webView = findViewById<WebView>(R.id.webResultContent)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        textPhoneNumber.text = phoneNumber
        btnBack.setOnClickListener { finish() }

        val json = JSONObject(resultJson)
        val data = json.optJSONObject("data") ?: json
        val html = buildResultHtml(data, phoneNumber)
        webView.setBackgroundColor(0xFF1D2021.toInt())
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun buildResultHtml(json: JSONObject, phone: String): String {
        val content = StringBuilder()

        content.append("<h1>$phone</h1>")

        // Phone info section
        val phoneInfo = json.optJSONObject("phone_info")
        if (phoneInfo != null && phoneInfo.length() > 0) {
            content.append("<h2>Phone Info</h2>")
            content.append("<table>")
            appendRow(content, "Operator", phoneInfo.optString("operator", ""))
            appendRow(content, "Region", phoneInfo.optString("region", ""))
            appendRow(content, "Country", phoneInfo.optString("country", ""))
            appendRow(content, "Type", phoneInfo.optString("type", ""))
            content.append("</table>")
        }

        // Contacts / phonebook names
        val contacts = json.optJSONArray("contacts")
        if (contacts != null && contacts.length() > 0) {
            content.append("<h2>Contacts</h2>")
            content.append("<ul>")
            for (i in 0 until contacts.length()) {
                val c = contacts.optString(i, "")
                if (c.isNotEmpty()) content.append("<li>$c</li>")
            }
            content.append("</ul>")
        }

        // Social profiles
        val socials = json.optJSONArray("social_profiles")
        if (socials != null && socials.length() > 0) {
            content.append("<h2>Social Profiles</h2>")
            content.append("<table>")
            for (i in 0 until socials.length()) {
                val profile = socials.optJSONObject(i) ?: continue
                val platform = profile.optString("platform", "")
                val username = profile.optString("username", "")
                val url = profile.optString("url", "")
                val displayValue = if (url.isNotEmpty()) "<a href=\"$url\">$username</a>" else username
                appendRow(content, platform, displayValue)
            }
            content.append("</table>")
        }

        // Emails
        val emails = json.optJSONArray("emails")
        if (emails != null && emails.length() > 0) {
            content.append("<h2>Emails</h2>")
            content.append("<ul>")
            for (i in 0 until emails.length()) {
                val email = emails.optString(i, "")
                if (email.isNotEmpty()) content.append("<li>$email</li>")
            }
            content.append("</ul>")
        }

        // Bank info
        val banks = json.optJSONArray("banks")
        if (banks != null && banks.length() > 0) {
            content.append("<h2>Bank Info</h2>")
            content.append("<ul>")
            for (i in 0 until banks.length()) {
                val bank = banks.optString(i, "")
                if (bank.isNotEmpty()) content.append("<li>$bank</li>")
            }
            content.append("</ul>")
        }

        // Raw data fallback: show any other keys
        val knownKeys = setOf("phone_info", "contacts", "social_profiles", "emails", "banks")
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in knownKeys) continue
            val value = json.opt(key)
            if (value != null && value.toString().isNotEmpty() && value.toString() != "null") {
                content.append("<h2>${escapeHtml(key)}</h2>")
                content.append("<p>${escapeHtml(value.toString())}</p>")
            }
        }

        if (content.toString() == "<h1>$phone</h1>") {
            content.append("<p>No data found for this number.</p>")
        }

        return """
        <!DOCTYPE html>
        <html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
            body { background:#1D2021; color:#EBDBB2; font-family:sans-serif; font-size:13px; padding:12px; line-height:1.5; margin:0; }
            h1 { color:#FE8019; font-size:18px; border-bottom:1px solid #3C3836; padding-bottom:4px; }
            h2 { color:#D79921; font-size:15px; margin-top:16px; }
            h3 { color:#B8BB26; font-size:14px; }
            table { border-collapse:collapse; width:100%; margin:8px 0; }
            td { border:1px solid #504945; padding:4px 8px; font-size:12px; }
            tr:nth-child(odd) { background:#282828; }
            tr:nth-child(even) { background:#32302F; }
            pre { background:#282828; padding:8px; overflow-x:auto; border-radius:4px; font-size:12px; color:#B8BB26; }
            code { background:#3C3836; padding:1px 4px; border-radius:2px; font-size:12px; color:#B8BB26; }
            ul { padding-left:20px; }
            li { margin:2px 0; }
            hr { border:none; border-top:1px solid #504945; margin:12px 0; }
            strong { color:#FE8019; }
            a { color:#83A598; }
            p { margin:6px 0; }
        </style></head><body>$content</body></html>
        """.trimIndent()
    }

    private fun appendRow(sb: StringBuilder, label: String, value: String) {
        if (value.isEmpty()) return
        sb.append("<tr><td><strong>${escapeHtml(label)}</strong></td><td>$value</td></tr>")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
