# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI looks up these bridge classes and constructors by their JVM names.
-keep class de.manhhao.hoshi.** { *; }

# Keep JavaScript interfaces exposed to WebView content.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# UniFFI's generated Kotlin/JNA bindings are part of the native ABI boundary.
-keep class uniffi.hoshiepub.** { *; }

# JNA's own native dispatcher looks up internal classes and fields such as
# com.sun.jna.Pointer.peer by their original JVM names.
-keep class com.sun.jna.** { *; }

# JNA also ships desktop AWT integration classes that are unused on Android.
-dontwarn java.awt.**

# Media3 (androidx.media3) isolates API 31+ classes such as
# android.media.metrics.LogSessionId behind *$Api31 inner classes that are
# loaded lazily. A known R8 bug (horizontal class merging) can hoist those
# signatures into their outer classes, forcing eager resolution of API 31
# types at class verification time, which crashes (NoClassDefFoundError) on
# API < 31 devices. See https://github.com/androidx/media/issues/2535.
# Keep the inner-class boundaries and member signatures intact so the
# lazy-loading guard behaves as Media3 designed it.
-keep class androidx.media3.** {
    public protected *;
}
-keepclassmembers class androidx.media3.** {
    *;
}
-keep class android.media.metrics.** { *; }
-dontwarn androidx.media3.**
-dontwarn android.media.metrics.**
-dontwarn com.google.common.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
