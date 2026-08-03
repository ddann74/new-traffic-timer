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
            return new JSONArray(text);
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    public static synchronized void appendTrip(Context context, JSONObject trip) {
        JSONArray trips = loadTrips(context);
        trips.put(trip);
        saveTrips(context, trips);
    }

    public static synchronized void wipeAll(Context context) {
        saveTrips(context, new JSONArray());
    }

    public static synchronized JSONObject findTrip(Context context, long id) {
        JSONArray trips = loadTrips(context);
        for (int i = 0; i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            if (t != null && t.optLong("id") == id) return t;
        }
        return null;
    }

    private static void saveTrips(Context context, JSONArray trips) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(trips.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // best-effort persistence - nothing further to do if the write fails
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
