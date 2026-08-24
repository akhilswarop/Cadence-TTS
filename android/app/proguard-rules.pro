# The TTS bridge is reached from JavaScript by reflection, so its
# @JavascriptInterface methods must survive shrinking even though nothing
# in the Java sources calls them.
-keepclassmembers class com.wordbeat.tts.TtsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
