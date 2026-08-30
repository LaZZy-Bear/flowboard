# ==============================================================================
# 🛡️ Flowboard Android — Production ProGuard & R8 Optimization Rules
# ==============================================================================

# General Optimization & Attributes
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-dontnote kotlinx.serialization.SerializationKt

# ------------------------------------------------------------------------------
# 1. Kotlinx Serialization Protection
# ------------------------------------------------------------------------------
-keep,includedescriptorclasses class com.flowboard.ime.**$$serializer { *; }

-keepclassmembers class com.flowboard.ime.** {
    *** Companion;
}

-keepclasseswithmembers class com.flowboard.ime.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable model fields intact for JSON reflection/deserialization
-keepclassmembers class com.flowboard.ime.data.models.** {
    private <fields>;
    public <fields>;
}

-keepclassmembers class com.flowboard.ime.engine.LiveProfileData {
    private <fields>;
    public <fields>;
}

# ------------------------------------------------------------------------------
# 2. App-Scoped AES Crypto & Java Crypto
# ------------------------------------------------------------------------------
-dontwarn javax.crypto.**
-dontwarn javax.annotation.**

# ------------------------------------------------------------------------------
# 3. Android Components & Custom Views
# ------------------------------------------------------------------------------
-keep class com.flowboard.ime.FlowboardApplication { *; }
-keep class com.flowboard.ime.MainActivity { *; }
-keep class com.flowboard.ime.service.FlowboardIMEService { *; }
-keep class com.flowboard.ime.ui.** extends android.view.View { *; }

-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ------------------------------------------------------------------------------
# 4. Kotlin Coroutines
# ------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ------------------------------------------------------------------------------
# 5. Strip Debug & Verbose Logging in Release Builds (Performance & Security)
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
