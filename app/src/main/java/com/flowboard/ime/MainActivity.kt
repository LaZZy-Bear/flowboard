package com.flowboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.webViewClient = object : WebViewClient() {
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

        webView.addJavascriptInterface(KeyboardSettingsInterface(this), "Android")
        val page = intent.getStringExtra("OPEN_PAGE") ?: "settings.html"
        webView.loadUrl("file:///android_asset/web/$page")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

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
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            return prefs.getBoolean(key, defaultVal)
        }

        @JavascriptInterface
        fun saveSetting(key: String, value: Boolean) {
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(key, value).apply()
            
            // Broadcast changes to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun getActiveTheme(): String {
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            return prefs.getString("active_theme", "Clean Minimal") ?: "Clean Minimal"
        }

        @JavascriptInterface
        fun setTheme(themeName: String) {
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            prefs.edit().putString("active_theme", themeName).apply()
            
            // Broadcast theme change to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }

        @JavascriptInterface
        fun isPremium(): Boolean {
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            return prefs.getBoolean("is_premium", false)
        }

        @JavascriptInterface
        fun setPremium(premium: Boolean) {
            val prefs = activity.getSharedPreferences("flowboard_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_premium", premium).apply()
            
            // Broadcast premium status to active keyboard service
            val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED")
            activity.sendBroadcast(intent)
        }
    }
}
