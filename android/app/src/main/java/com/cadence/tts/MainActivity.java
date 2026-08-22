package com.cadence.tts;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

/**
 * Hosts the web app and wires the native TTS bridge into it.
 *
 * The page is loaded from assets rather than the network, so the app works
 * offline and ships the exact HTML in this repository.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TtsBridge bridge;

    /*
     * Insets usually arrive before the page has finished loading, so the
     * script is held here and replayed in onPageFinished. Without that the
     * first layout renders under the status bar until something else
     * triggers a fresh inset pass.
     */
    private String insetScript;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);

        /*
         * Debug builds only: exposes the WebView to Chrome DevTools over adb,
         * which is the only practical way to see console errors or inspect
         * the rendered DOM on a device. Gated on the debuggable flag so a
         * release APK never opens the inspector.
         */
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // The page never reads local files itself; only the asset loader does.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        /*
         * A JavaScript interface is exposed on this WebView, so any page
         * loaded into it can reach the bridge. Navigation is therefore pinned
         * to the bundled asset — anything else is refused rather than opened
         * in place.
         */
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (insetScript != null) view.evaluateJavascript(insetScript, null);
            }
        });

        /*
         * The page is laid out edge to edge so its backgrounds reach the
         * screen edges, and the system bar sizes are handed to CSS instead
         * of being applied as view padding. That keeps the paper and the
         * player painting behind the status and gesture bars while their
         * content stays clear of them.
         */
        ViewCompat.setOnApplyWindowInsetsListener(web, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            float density = getResources().getDisplayMetrics().density;

            insetScript = String.format(Locale.US,
                    "(function(s){" +
                    "s.setProperty('--safe-top','%.2fpx');" +
                    "s.setProperty('--safe-bottom','%.2fpx');" +
                    "s.setProperty('--safe-left','%.2fpx');" +
                    "s.setProperty('--safe-right','%.2fpx');" +
                    "})(document.documentElement.style)",
                    bars.top / density, bars.bottom / density,
                    bars.left / density, bars.right / density);

            ((WebView) view).evaluateJavascript(insetScript, null);
            return windowInsets;
        });

        bridge = new TtsBridge(web, this);
        web.addJavascriptInterface(bridge, "AndroidTTS");

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Speech should not keep running once the app leaves the foreground.
        if (bridge != null) bridge.stop();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
