package com.gravityintel.app;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Trips used to live in the web page's localStorage; they're now stored natively as
  * a single JSON array file in app-private storage, so a finished trip survives
  * even if the WebView was never visible while it happened. */
public class TripStore {

    private static final String FILE_NAME = "trips.json";

    public static synchronized JSONArray loadTrips(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new JSONArray();
        try {
            String text = readFile(file);
            if (text.trim().isEmpty()) return new JSONArray();
            JSONArray trips = new JSONArray(text);
            DiagnosticLog.log(context, "TRIPSTORE", "loadTrips succeeded, " + trips.length() + " trip(s)");
            return trips;
        } catch (IOException | JSONException e) {
            DiagnosticLog.log(context, "TRIPSTORE", "loadTrips FAILED - " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + " - trips.json may be corrupted, returning empty list");
            return new JSONArray();
        }
    }

    /** Returns whether the trip was actually persisted to disk - previously this
      * was void, so a write failure here was invisible to every caller, including
      * TrackingService.saveTrip() logging "succeeded" based only on the JSON object
      * having been built, not on whether it was ever written. */
    public static synchronized boolean appendTrip(Context context, JSONObject trip) {
        JSONArray trips = loadTrips(context);
        trips.put(trip);
        boolean persisted = saveTrips(context, trips);
        DiagnosticLog.log(context, "TRIPSTORE", "appendTrip " + (persisted ? "succeeded" : "FAILED")
                + ", trips.json now has " + trips.length() + " entries"
                + (persisted ? "" : " (this write was NOT persisted to disk)"));
        return persisted;
    }

    public static synchronized boolean wipeAll(Context context) {
        boolean persisted = saveTrips(context, new JSONArray());
        DiagnosticLog.log(context, "TRIPSTORE", "wipeAll " + (persisted ? "succeeded" : "FAILED"));
        return persisted;
    }

    public static synchronized JSONObject findTrip(Context context, long id) {
        JSONArray trips = loadTrips(context);
        for (int i = 0; i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            if (t != null && t.optLong("id") == id) return t;
        }
        DiagnosticLog.log(context, "TRIPSTORE", "findTrip - no trip found for id=" + id);
        return null;
    }

    private static boolean saveTrips(Context context, JSONArray trips) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(trips.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            DiagnosticLog.log(context, "TRIPSTORE", "saveTrips FAILED - " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + " - " + trips.length() + " trip(s) NOT written to disk");
            return false;
        }
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }
}
