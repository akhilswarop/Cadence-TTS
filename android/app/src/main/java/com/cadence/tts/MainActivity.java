package com.cadence.tts;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Hosts the web app and wires the native TTS bridge into it.
 *
 * The page is loaded from assets rather than the network, so the app works
 * offline and ships the exact HTML in this repository.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TtsBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
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
