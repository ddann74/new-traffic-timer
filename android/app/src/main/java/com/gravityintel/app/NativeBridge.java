package com.gravityintel.app;

import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import androidx.core.content.ContextCompat;
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
        Intent intent = new Intent(context, TrackingService.class);
        intent.setAction(TrackingService.ACTION_START_TRIP);
        ContextCompat.startForegroundService(context, intent);
    }

    @JavascriptInterface
    public void finishTrip() {
        // Plain startService, not startForegroundService: the service is already
        // running in the foreground from startTrip(), so this is just delivering a
        // new command to it, not promoting it - no 5-second startForeground()
        // obligation applies to this call.
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
        TripStore.wipeAll(context);
    }
}
