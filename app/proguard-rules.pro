# Flowboard ProGuard Rules
-keepattributes *Annotation*

# Keep Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.flowboard.ime.**$$serializer { *; }
-keepclassmembers class com.flowboard.ime.** {
    *** Companion;
}
-keepclasseswithmembers class com.flowboard.ime.** {
    kotlinx.serialization.KSerializer serializer(...);
}
