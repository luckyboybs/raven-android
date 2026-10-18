# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified in
# $ANDROID_HOME/tools/proguard/proguard-android.txt

# Keep WebView JavaScript bridge entry points (none declared for now, kept for future use).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
