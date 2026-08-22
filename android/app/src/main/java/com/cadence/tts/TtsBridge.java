package com.cadence.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * Bridges Android's native TextToSpeech to the web app's engine interface.
 *
 * The whole reason this class exists: Android WebView does not implement the
 * Web Speech API, so the browser engine the app normally uses is simply
 * absent here. What Android offers instead is better for our purposes —
 * {@link UtteranceProgressListener#onRangeStart} reports the character range
 * of each word as it is spoken, which is exactly the timing track the
 * renderer consumes. The JS side treats this class as just another producer.
 *
 * Offsets reported by onRangeStart are relative to the utterance text, the
 * same contract as the Web Speech API's charIndex, so the JS engine adds the
 * chunk's absolute offset the same way in both cases.
 */
public class TtsBridge {

    private final WebView web;
    // Not final: the init callback below reads this field from inside the
    // same expression that assigns it. The callback only ever fires
    // asynchronously after the constructor returns, so tts is always set by
    // then, but javac's definite-assignment check for blank finals can't see
    // that and refuses to compile it as final.
    private TextToSpeech tts;
    private volatile boolean ready = false;

    public TtsBridge(WebView web, Context context) {
        this.web = web;
        this.tts = new TextToSpeech(context, status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setLanguage(Locale.getDefault());
                tts.setOnUtteranceProgressListener(listener);
            }
            emit("ready", "{\"ok\":" + ready + "}");
        });
    }

    private final UtteranceProgressListener listener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            emit("start", idPayload(utteranceId));
        }

        @Override
        public void onDone(String utteranceId) {
            emit("done", idPayload(utteranceId));
        }

        @Override
        public void onError(String utteranceId) {
            emit("error", idPayload(utteranceId));
        }

        /** API 26+. start and end are character offsets within this utterance. */
        @Override
        public void onRangeStart(String utteranceId, int start, int end, int frame) {
            emit("range", "{\"id\":\"" + escape(utteranceId) + "\",\"start\":" + start
                    + ",\"end\":" + end + "}");
        }
    };

    /**
     * Utterance ids are numeric strings this class generates, and payload
     * values are ints, so nothing user-supplied is ever interpolated into JS.
     */
    private void emit(String type, String payloadJson) {
        final String js = "window.__androidTts && window.__androidTts.on('"
                + type + "', " + payloadJson + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String idPayload(String id) {
        return "{\"id\":\"" + escape(id) + "\"}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @JavascriptInterface
    public boolean isReady() {
        return ready;
    }

    /**
     * Queues one chunk. The JS side queues every chunk up front and lets the
     * platform run the queue, using the utterance id to map a range callback
     * back to the chunk it came from.
     */
    @JavascriptInterface
    public void speak(String text, String utteranceId, float rate) {
        if (!ready) return;
        tts.setSpeechRate(rate);
        tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), utteranceId);
    }

    @JavascriptInterface
    public void stop() {
        if (ready) tts.stop();
    }

    /**
     * Android's TextToSpeech has no pause. The JS engine implements pause by
     * stopping here and re-speaking from the last reported word on resume,
     * which is why the engine tracks its own position.
     */
    @JavascriptInterface
    public String voices() {
        JSONArray out = new JSONArray();
        if (!ready) return out.toString();
        try {
            Set<Voice> available = tts.getVoices();
            if (available == null) return out.toString();
            for (Voice v : available) {
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("locale", v.getLocale().toString());
                o.put("network", v.isNetworkConnectionRequired());
                out.put(o);
            }
        } catch (Exception ignored) {
            // Some OEM engines throw when enumerating; an empty list is the
            // honest answer and the app falls back to the default voice.
        }
        return out.toString();
    }

    @JavascriptInterface
    public boolean setVoice(String name) {
        if (!ready) return false;
        try {
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(name)) {
                    return tts.setVoice(v) == TextToSpeech.SUCCESS;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    void shutdown() {
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
    }
}
