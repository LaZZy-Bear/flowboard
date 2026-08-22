package com.flowboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.flowboard.ime.ui.settings.SettingsFragment
import com.flowboard.ime.ui.settings.ThemesFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNavigationView: BottomNavigationView

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted. You can now use voice typing.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is needed for Voice typing", Toast.LENGTH_LONG).show()
        }
        if (intent?.getBooleanExtra("REQUEST_AUDIO_PERMISSION", false) == true) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.topToolbar)
        bottomNavigationView = findViewById(R.id.bottomNavigationView)

        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    replaceRootFragment(SettingsFragment())
                    true
                }
                R.id.nav_themes -> {
                    supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    replaceRootFragment(ThemesFragment())
                    true
                }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("REQUEST_AUDIO_PERMISSION", false) == true) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission already granted", Toast.LENGTH_SHORT).show()
                finish()
                return
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }

        val openPage = intent?.getStringExtra("OPEN_PAGE")
        if (openPage == "themes.html" || openPage == "themes") {
            bottomNavigationView.selectedItemId = R.id.nav_themes
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            replaceRootFragment(ThemesFragment())
        } else {
            bottomNavigationView.selectedItemId = R.id.nav_settings
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            replaceRootFragment(SettingsFragment())
        }
    }

    private fun handleBackNavigation() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFragment is ThemesFragment) {
                setToolbarTitle("Keyboard Themes", false)
            } else {
                setToolbarTitle("Keyboard Settings", false)
            }
        } else {
            finish()
        }
    }

    fun replaceRootFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun navigateToFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun setToolbarTitle(title: String, showBackButton: Boolean) {
        toolbar.title = title
        if (showBackButton) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        } else {
            toolbar.navigationIcon = null
        }
    }

    fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    fun isKeyboardSelected(): Boolean {
        val currentIME = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) { null }
        return currentIME != null && currentIME.contains(packageName)
    }

    fun enableKeyboard() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        startActivity(intent)
    }

    fun selectKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    fun notifyImeSettingsChanged(key: String? = null, value: Any? = null) {
        val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED").apply {
            setPackage(packageName)
            if (key != null) {
                putExtra("setting_key", key)
                when (value) {
                    is Boolean -> putExtra("setting_val_bool", value)
                    is String -> putExtra("setting_val_str", value)
                    is Int -> putExtra("setting_val_int", value)
                    is Float -> putExtra("setting_val_float", value)
                }
            }
        }
        sendBroadcast(intent)
    }

    fun notifyShortcutChanged(keyNum: Int, label: String, text: String) {
        val intent = Intent("com.flowboard.ime.ACTION_SETTINGS_CHANGED").apply {
            setPackage(packageName)
            putExtra("setting_key", "shortcut_$keyNum")
            putExtra("shortcut_key_num", keyNum)
            putExtra("shortcut_label", label)
            putExtra("shortcut_text", text)
        }
        sendBroadcast(intent)
    }
}
