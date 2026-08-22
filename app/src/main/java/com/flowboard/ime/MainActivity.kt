package com.flowboard.ime

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.webViewClient = object : WebViewClient() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith("file:///android_asset/")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }
        }
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Flowboard")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        webView.addJavascriptInterface(KeyboardSettingsInterface(this), "Android")
        val page = intent.getStringExtra("OPEN_PAGE") ?: "settings.html"
        webView.loadUrl("file:///android_asset/web/$page")

        if (intent.getBooleanExtra("REQUEST_AUDIO_PERMISSION", false)) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    fun isKeyboardSelected(): Boolean {
        val currentIME = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return currentIME?.startsWith(packageName) == true
    }

    fun enableKeyboard() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        startActivity(intent)
    }

    fun selectKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    @Suppress("unused")
    class KeyboardSettingsInterface(private val activity: MainActivity) {

        @JavascriptInterface
        fun isKeyboardEnabled(): Boolean = activity.isKeyboardEnabled()

        @JavascriptInterface
        fun isKeyboardSelected(): Boolean = activity.isKeyboardSelected()

        @JavascriptInterface
        fun enableKeyboard() = activity.enableKeyboard()

        @JavascriptInterface
        fun selectKeyboard() = activity.selectKeyboard()

        @JavascriptInterface
        fun getSetting(key: String, defaultVal: Boolean): Boolean {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            return prefs.getBoolean(key, defaultVal)
        }

        @JavascriptInterface
        fun saveSetting(key: String, value: Boolean) {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            prefs.edit { putBoolean(key, value) }
            
            // Broadcast changes to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun getStringSetting(key: String, defaultVal: String): String {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            return prefs.getString(key, defaultVal) ?: defaultVal
        }

        @JavascriptInterface
        fun saveStringSetting(key: String, value: String) {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            prefs.edit { putString(key, value) }
            
            // Broadcast changes to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun getActiveTheme(): String {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            return prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        }

        @JavascriptInterface
        fun setTheme(themeName: String) {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            prefs.edit { putString("active_theme", themeName) }
            
            // Broadcast theme change to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun isPremium(): Boolean {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            return prefs.getBoolean("is_premium", false)
        }

        @JavascriptInterface
        fun setPremium(premium: Boolean) {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            prefs.edit { putBoolean("is_premium", premium) }
            
            // Broadcast premium status to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun getPersonalizationStats(): String {
            return try {
                val liveMgr = com.flowboard.ime.engine.LiveLearningManager(activity)
                liveMgr.loadProfile()
                val stats = liveMgr.getStats()
                org.json.JSONObject(stats as Map<*, *>).toString()
            } catch (e: Exception) {
                "{}"
            }
        }

        @JavascriptInterface
        fun clearPersonalizationData() {
            try {
                val liveMgr = com.flowboard.ime.engine.LiveLearningManager(activity)
                liveMgr.clearProfile()

                val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
                activity.sendBroadcast(intent)
            } catch (e: Exception) {
                // ignore
            }
        }

        @JavascriptInterface
        fun getShortcutsJson(): String {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            val json = org.json.JSONObject()
            for (i in 1..9) {
                val item = org.json.JSONObject()
                item.put("label", prefs.getString("shortcut_label_$i", "") ?: "")
                item.put("text", prefs.getString("shortcut_text_$i", "") ?: "")
                json.put(i.toString(), item)
            }
            return json.toString()
        }

        @JavascriptInterface
        fun saveShortcut(keyNum: Int, label: String, text: String) {
            val prefs = activity.getSharedPreferences("flowboard_settings", MODE_PRIVATE)
            prefs.edit {
                putString("shortcut_label_$keyNum", label.trim())
                putString("shortcut_text_$keyNum", text.trim())
            }
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }
    }
}
