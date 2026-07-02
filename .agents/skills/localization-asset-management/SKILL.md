---
name: localization-asset-management
description: Framework and guidelines for managing multi-language resources, dynamic layout loading based on locales, caching layouts, loading asset files, and managing localized dictionaries.
---

# Localization & Asset Management Skill

This skill explains how to build a dynamic localizer system for Flowboard that allows on-the-fly language switching independent of system language settings.

## 1. Dynamic Localized Context (Locale-shifting)

Because an IME `InputMethodService` is a long-running service, changing the system locale might not update its context resources immediately. We should force locale overrides dynamically when the user selects a different keyboard layout language (e.g., switching between English and Thai).

### Context Wrapper Pattern
```kotlin
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun updateLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }
}
```

### Usage in InputMethodService
Store the dynamically wrapped context:
```kotlin
class FlowboardService : InputMethodService() {
    private lateinit var localizedContext: Context
    private var currentLanguage = "th" // default Thai

    override fun onCreate() {
        super.onCreate()
        updateLanguageContext()
    }

    private fun updateLanguageContext() {
        localizedContext = LocaleHelper.updateLocale(this, currentLanguage)
    }

    // Access resources via the localizedContext instead of "this"
    fun getLocalizedText(resId: Int): String {
        return localizedContext.getString(resId)
    }
}
```

## 2. Dynamic Layout Loading (XML Keyboard Assets)

Keyboards frequently map keys using XML profiles inside `res/xml/`. We can load different keyboard layouts depending on the active locale.

### Structure of Asset Directory
Keep localized dictionaries and mappings in `assets/`:
```
assets/
└── locales/
    ├── th_TH/
    │   ├── words.json          <-- Thai word database
    │   └── keymap.json         <-- Keyboard layout coordinates mapping
    └── en_US/
        ├── words.json          <-- English word database
        └── keymap.json
```

### Safely Loading Assets
```kotlin
fun loadAssetAsString(context: Context, path: String): String? {
    return runCatching {
        context.assets.open(path).use { stream ->
            stream.bufferedReader().use { it.readText() }
        }
    }.getOrNull()
}
```

## 3. Localization Best Practices
1. **Thread-safe Language Switches**: Use flow/live-data or shared state to notify keyboard components of a locale change.
2. **Fallback Logic**: Always fallback to `"en"` if a localization file or specific key cannot be resolved for the target locale.
3. **Optimized Dictionary Caching**: Keep only the *current* active language's dictionary parsed in RAM. Release the previous dictionary's memory before loading a new one.
