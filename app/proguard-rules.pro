# WebView JS bridges and callbacks are reached reflectively from the page side.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class dev.prowsee.permanent.engine.** { *; }
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Room generated code
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin metadata for Compose
-keep class kotlin.Metadata { *; }
