package com.gravityintel.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns GPS tracking end to end: location updates, distance/idle/flow-rating
  * calculation, and trip persistence - all independent of whether the WebView is
  * visible, attached, or even created. This is what makes tracking survive
  * backgrounding; the WebView is purely a display for whatever this service reports. */
public class TrackingService extends Service implements LocationListener {

    public static final String ACTION_START_TRIP = "com.gravityintel.app.action.START_TRIP";
    public static final String ACTION_FINISH_TRIP = "com.gravityintel.app.action.FINISH_TRIP";
    public static final String ACTION_STATE_UPDATED = "com.gravityintel.app.STATE_UPDATED";

    private static final String CHANNEL_ID = "gravity_tracking";
    private static final int NOTIFICATION_ID = 5151;
    private static final double IDLE_SPEED_THRESHOLD_KMH = 2.5;
    private static final double RESUME_SPEED_THRESHOLD_KMH = 3.5;
    // The original web page ticked its idle-timer display every 100ms, which made
    // sense for a page you're actively looking at. A background service has no
    // audience most of the time, so this ticks once a second instead - still feels
    // live when the app is open, and doesn't wake the CPU 10x as often for no reason.
    private static final long TICK_INTERVAL_MS = 1000;
    private static final long WAKE_LOCK_SAFETY_TIMEOUT_MS = 6 * 60 * 60 * 1000L;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private Handler tickHandler;
    private Runnable tickRunnable;

    private long tripId;
    private long tripStartMillis;
    private Location lastLocation;
    private boolean isClocking;
    private long clockStartMillis;
    private double totalIdleSeconds;
    private org.json.JSONArray pathPoints;
    private org.json.JSONArray stopPoints;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_TRIP.equals(action)) {
            startTrip();
        } else if (ACTION_FINISH_TRIP.equals(action)) {
            finishTrip();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTrip() {
        if (TrackingState.armed) return;
        TrackingState.reset();
        TrackingState.armed = true;

        tripId = System.currentTimeMillis();
        tripStartMillis = tripId;
        lastLocation = null;
        isClocking = false;
        totalIdleSeconds = 0;
        pathPoints = new org.json.JSONArray();
        stopPoints = new org.json.JSONArray();

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        requestLocationUpdates();
        startTicking();
        broadcastState();
    }

    private void finishTrip() {
        if (!TrackingState.armed) {
            stopSelf();
            return;
        }
        stopClock();
        saveTrip();
        TrackingState.armed = false;
        stopTicking();
        stopLocationUpdates();
        releaseWakeLock();
        broadcastState();
        stopForeground(true);
        stopSelf();
    }

    private void requestLocationUpdates() {
        boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasFine) return;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, this);
                }
            } catch (Exception e) {
                // this provider isn't usable on this device - the other one may still work
            }
        }
    }

    private void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            // already removed or never registered
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double kmh = location.hasSpeed() ? location.getSpeed() * 3.6 : 0.0;
        TrackingState.speedKmh = kmh;
        TrackingState.lat = location.getLatitude();
        TrackingState.lon = location.getLongitude();

        if (lastLocation != null) {
            TrackingState.distanceKm += distanceKm(lastLocation, location);

            if (kmh < IDLE_SPEED_THRESHOLD_KMH && !isClocking) {
                startClock();
            }
            if (kmh > RESUME_SPEED_THRESHOLD_KMH && isClocking) {
                double dur = (System.currentTimeMillis() - clockStartMillis) / 1000.0;
                if (dur > 2) {
                    totalIdleSeconds += dur;
                    addStopPoint(location, dur);
                }
                stopClock();
            }
        }
        addPathPoint(location);
        lastLocation = location;
        updateFlowRating();
        broadcastState();
        updateNotification();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    private void startClock() {
        isClocking = true;
        clockStartMillis = System.currentTimeMillis();
    }

    private void stopClock() {
        isClocking = false;
        TrackingState.idleSeconds = 0;
    }

    private void startTicking() {
        tickHandler = new Handler(Looper.getMainLooper());
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!TrackingState.armed) return;
                if (isClocking) {
                    TrackingState.idleSeconds = (System.currentTimeMillis() - clockStartMillis) / 1000.0;
                    updateFlowRating();
                    broadcastState();
                }
                tickHandler.postDelayed(this, TICK_INTERVAL_MS);
            }
        };
        tickHandler.post(tickRunnable);
    }

    private void stopTicking() {
        if (tickHandler != null && tickRunnable != null) {
            tickHandler.removeCallbacks(tickRunnable);
        }
    }

    private void updateFlowRating() {
        double elapsed = (System.currentTimeMillis() - tripStartMillis) / 1000.0;
        double idle = totalIdleSeconds + (isClocking ? (System.currentTimeMillis() - clockStartMillis) / 1000.0 : 0);
        double score = elapsed > 2 ? Math.max(0, ((elapsed - idle) / elapsed) * 10) : 10.0;
        TrackingState.flowRating = Math.round(score * 10.0) / 10.0;
    }

    private void addPathPoint(Location location) {
        try {
            JSONObject point = new JSONObject();
            point.put("lat", location.getLatitude());
            point.put("lon", location.getLongitude());
            point.put("ts", System.currentTimeMillis());
            pathPoints.put(point);
        } catch (JSONException e) {
            // skip this point rather than crash tracking over it
        }
    }

    private void addStopPoint(Location location, double durationSeconds) {
        try {
            JSONObject stop = new JSONObject();
            stop.put("lat", location.getLatitude());
            stop.put("lon", location.getLongitude());
            stop.put("dur", durationSeconds);
            stopPoints.put(stop);
        } catch (JSONException e) {
            // skip this stop rather than crash tracking over it
        }
    }

    private void saveTrip() {
        if (pathPoints == null || pathPoints.length() < 2) return;
        try {
            JSONObject trip = new JSONObject();
            trip.put("id", tripId);
            trip.put("start", tripStartMillis);
            trip.put("end", System.currentTimeMillis());
            trip.put("dist", TrackingState.distanceKm);
            trip.put("totalIdle", totalIdleSeconds);
            trip.put("rating", String.format(Locale.US, "%.1f", TrackingState.flowRating));
            trip.put("path", pathPoints);
            trip.put("stops", stopPoints);
            TripStore.appendTrip(getApplicationContext(), trip);
        } catch (JSONException e) {
            // trip couldn't be assembled - nothing to persist
        }
    }

    private double distanceKm(Location a, Location b) {
        final double earthRadiusKm = 6371;
        double dLat = Math.toRadians(b.getLatitude() - a.getLatitude());
        double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
        double sinHalf = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.getLatitude())) * Math.cos(Math.toRadians(b.getLatitude()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * (2 * Math.atan2(Math.sqrt(sinHalf), Math.sqrt(1 - sinHalf)));
    }

    private void broadcastState() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_STATE_UPDATED));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Trip Tracking", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while a trip is being tracked");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent finishIntent = new Intent(this, TrackingService.class);
        finishIntent.setAction(ACTION_FINISH_TRIP);
        PendingIntent finishPendingIntent = PendingIntent.getService(
                this, 0, finishIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String content = String.format(Locale.US, "%.2f km - Flow %.1f",
                TrackingState.distanceKm, TrackingState.flowRating);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Tracking trip")
                .setContentText(content)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(0, "Finish Trip", finishPendingIntent)
                .build();
    }

    private void updateNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GravityIntel:TrackingWakeLock");
        wakeLock.acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTicking();
        stopLocationUpdates();
        releaseWakeLock();
    }
}
