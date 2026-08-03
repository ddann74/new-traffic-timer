package com.gravityintel.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Hosts the WebView UI (assets/index.html, unchanged Tailwind/Leaflet page). All
  * actual GPS tracking happens in TrackingService, independent of this Activity's
  * lifecycle - this class only displays whatever state that service reports. */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private NativeBridge nativeBridge;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pushStateToWebView();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        nativeBridge = new NativeBridge(this);
        webView.addJavascriptInterface(nativeBridge, "Native");
        webView.loadUrl("file:///android_asset/index.html");

        requestPermissions();
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        }
    }

    /** Pushes TrackingService's current state into the page via evaluateJavascript.
      * The JSON is wrapped with JSONObject.quote() so it's safely embedded as a JS
      * string literal and parsed on the other side, rather than concatenated raw. */
    private void pushStateToWebView() {
        String stateJson = nativeBridge.getStateJson();
        String quoted = JSONObject.quote(stateJson);
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onNativeUpdate && window.onNativeUpdate(JSON.parse(" + quoted + "))", null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                stateReceiver, new IntentFilter(TrackingService.ACTION_STATE_UPDATED));
        pushStateToWebView();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
