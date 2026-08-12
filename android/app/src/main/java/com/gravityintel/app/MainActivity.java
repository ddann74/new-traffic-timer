package com.gravityintel.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.log(this, "ACTIVITY", "onCreate");

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // Previously nothing from the WebView side reached the diagnostic log at
        // all - a JS exception inside index.html (e.g. a bug in the trip-review
        // map code) or a failed CDN load (Leaflet/Tailwind/tiles, all fetched
        // over the network) failed completely silently. See
        // docs/PRD_map_view_review.md.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                DiagnosticLog.log(MainActivity.this, "WEBVIEW", String.format(Locale.US,
                        "console %s: %s (%s:%d)", message.messageLevel(), message.message(),
                        message.sourceId(), message.lineNumber()));
                return false; // still let it reach Logcat too, same as the default behavior
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                DiagnosticLog.log(MainActivity.this, "WEBVIEW", "page load error: "
                        + error.getDescription() + " url=" + request.getUrl());
            }
        });

        nativeBridge = new NativeBridge(this);
        webView.addJavascriptInterface(nativeBridge, "Native");
        webView.loadUrl("file:///android_asset/index.html");

        requestPermissions();
        startMotionMonitor();
    }

    /** Starts TrackingService (which begins in watching mode, auto-starting a
      * trip on movement) if it isn't already running. Safe to call if a trip is
      * already active - the service no-ops on that. Called again once
      * permission is granted below, in case it wasn't yet at onCreate time.
      *
      * Deliberately does NOT call startForegroundService() before location
      * permission is actually granted - a location-type foreground service's
      * own startForeground() call requires at least one location permission
      * already granted, not just requested. A real device log caught this:
      * on a completely fresh install, this used to fire from onCreate() before
      * the permission dialog had been answered, crashing with SecurityException
      * every time. Skipping the call here and relying on the
      * onRequestPermissionsResult() retry below means it only actually starts
      * once permission is confirmed - never before. */
    private void startMotionMonitor() {
        boolean hasLocationPermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasLocationPermission) {
            DiagnosticLog.log(this, "SERVICE", "startMotionMonitor skipped - no location permission yet");
            return;
        }
        Intent intent = new Intent(this, TrackingService.class);
        intent.setAction(TrackingService.ACTION_START_WATCHING);
        ContextCompat.startForegroundService(this, intent);
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
            boolean granted = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
            DiagnosticLog.log(this, "PERMISSION", p + " alreadyGranted=" + granted);
            if (!granted) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            DiagnosticLog.log(this, "PERMISSION", permissions[i] + " result granted=" + granted);
        }
        // Retry: the first startMotionMonitor() call in onCreate ran before the
        // user answered this prompt, so it had nothing to check permission against
        // yet - TrackingService itself no-ops if it's already watching.
        startMotionMonitor();
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
        DiagnosticLog.log(this, "ACTIVITY", "onResume");
        LocalBroadcastManager.getInstance(this).registerReceiver(
                stateReceiver, new IntentFilter(TrackingService.ACTION_STATE_UPDATED));
        pushStateToWebView();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DiagnosticLog.log(this, "ACTIVITY", "onPause");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DiagnosticLog.log(this, "ACTIVITY", "onDestroy");
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
