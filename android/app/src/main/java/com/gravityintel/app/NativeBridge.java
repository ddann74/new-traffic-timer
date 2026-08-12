package com.gravityintel.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Exposed to the WebView as window.Native. Every method here is called from the
  * WebView's JS thread (not the UI thread), which is fine - none of these touch views
  * directly, only the service, TripStore, and TrackingState. */
public class NativeBridge {

    private final Context context;

    public NativeBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public void startTrip() {
        DiagnosticLog.log(context, "BRIDGE", "startTrip() requested from WebView");
        // Plain startService, not startForegroundService: TrackingService is
        // already running in the foreground (watching for movement) by the time
        // a user can tap this - it was already started once from
        // MainActivity.onCreate(). This only delivers a mode-switch action to
        // it, not promotes a not-yet-running service.
        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_START_TRIP);
        context.startService(intent);
    }

    @JavascriptInterface
    public void finishTrip() {
        DiagnosticLog.log(context, "BRIDGE", "finishTrip() requested from WebView");
        // Plain startService, not startForegroundService: the service is already
        // running in the foreground, so this is just delivering a new command to
        // it, not promoting it - no 5-second startForeground() obligation applies
        // to this call.
        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_FINISH_TRIP);
        context.startService(intent);
    }

    @JavascriptInterface
    public String getStateJson() {
        try {
            JSONObject state = new JSONObject();
            state.put("armed", TrackingState.armed);
            state.put("speedKmh", TrackingState.speedKmh);
            state.put("distanceKm", TrackingState.distanceKm);
            state.put("idleSeconds", TrackingState.idleSeconds);
            state.put("flowRating", TrackingState.flowRating);
            state.put("lat", TrackingState.lat);
            state.put("lon", TrackingState.lon);
            return state.toString();
        } catch (JSONException e) {
            DiagnosticLog.log(context, "BRIDGE", "getStateJson() failed: " + e.getMessage());
            return "{}";
        }
    }

    @JavascriptInterface
    public String getTripsJson() {
        return TripStore.loadTrips(context).toString();
    }

    @JavascriptInterface
    public String getTripJson(long id) {
        JSONObject trip = TripStore.findTrip(context, id);
        return trip != null ? trip.toString() : "null";
    }

    @JavascriptInterface
    public void wipeAll() {
        DiagnosticLog.log(context, "BRIDGE", "wipeAll() requested from WebView");
        // The outcome (succeeded/FAILED) is logged by TripStore.wipeAll() itself,
        // not asserted here - this method used to say "trips cleared" unconditionally
        // even if the underlying write failed.
        TripStore.wipeAll(context);
    }

    @JavascriptInterface
    public String getDiagnosticLogText() {
        return DiagnosticLog.getAllText(context);
    }

    @JavascriptInterface
    public void clearDiagnosticLog() {
        DiagnosticLog.clear(context);
        DiagnosticLog.log(context, "BRIDGE", "diagnostic log cleared from WebView");
    }

    /** Snapshots the current diagnostic log to its own file (not the live
      * diagnostic.log TrackingService is still appending to) and hands it to
      * Android's share sheet via a FileProvider content:// Uri - lets the log
      * go straight into email/Drive/Slack/whatever without the user manually
      * copying text out of the SYS tab. */
    @JavascriptInterface
    public void shareDiagnosticLog() {
        DiagnosticLog.log(context, "BRIDGE", "shareDiagnosticLog() requested from WebView");
        try {
            String text = DiagnosticLog.getAllText(context);
            File shareDir = new File(context.getCacheDir(), "shared_logs");
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                DiagnosticLog.log(context, "BRIDGE", "shareDiagnosticLog failed - could not create shared_logs dir");
                return;
            }
            String fileName = "gravity-intel-diagnostic-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
            File shareFile = new File(shareDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }

            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", shareFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Gravity Intel diagnostic log");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, "Share diagnostic log");
            // NativeBridge only holds an application Context (not an Activity) -
            // startActivity() from here requires this flag or it throws.
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            DiagnosticLog.log(context, "BRIDGE", "shareDiagnosticLog succeeded, " + text.length() + " chars");
        } catch (IOException e) {
            DiagnosticLog.log(context, "BRIDGE", "shareDiagnosticLog failed writing snapshot: " + e.getMessage());
        } catch (Exception e) {
            DiagnosticLog.log(context, "BRIDGE", "shareDiagnosticLog failed: " + e.getMessage());
        }
    }
}
