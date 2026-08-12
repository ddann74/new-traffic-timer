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

/** Owns both idle-watching and active trip tracking as two modes of ONE
  * foreground service, rather than two separate Service classes handing off
  * to each other. That handoff used to mean a background LocationListener
  * callback calling startForegroundService() to start a brand-new second
  * service purely from a passive movement-detection trigger - which
  * Android 12+ disallows (an already-running foreground service does not
  * grant the exemption needed to start a DIFFERENT new one). See
  * docs/PRD_unify_tracking_service.md for the crash this caused on every
  * real auto-detected movement event, and why this design fixes it.
  *
  * There is only ever one Service instance. Switching between watching and
  * tracking (switchToWatching()/switchToTracking()) is a plain in-process
  * method call that changes notification content, LocationManager
  * registrations, and internal bookkeeping - it never calls
  * startForeground()/startForegroundService() again after the service's
  * one-time initial start, so the restriction this design exists to avoid
  * never applies to a mode switch. */
public class TrackingService extends Service implements LocationListener {

    public static final String ACTION_START_WATCHING = "com.gravityintel.app.action.START_WATCHING";
    public static final String ACTION_START_TRIP = "com.gravityintel.app.action.START_TRIP";
    public static final String ACTION_FINISH_TRIP = "com.gravityintel.app.action.FINISH_TRIP";
    public static final String ACTION_STATE_UPDATED = "com.gravityintel.app.STATE_UPDATED";

    private enum Mode { WATCHING, TRACKING }

    private static final String TRACKING_CHANNEL_ID = "gravity_tracking";
    private static final String WATCHING_CHANNEL_ID = "gravity_motion_watch";
    // One notification ID for the service's entire lifetime, regardless of mode -
    // startForeground() is only ever called once (see onStartCommand); every later
    // mode switch just re-notifies this same ID with different content, so there's
    // never more than one ongoing notification and never a second startForeground()
    // call to trigger the background-FGS-start restriction.
    private static final int NOTIFICATION_ID = 5151;

    private static final double IDLE_SPEED_THRESHOLD_KMH = 2.5;
    private static final double RESUME_SPEED_THRESHOLD_KMH = 3.5;
    // NETWORK_PROVIDER fixes are routinely 40-150m off (cell/wifi triangulation);
    // GPS fixes on a moving vehicle are typically well under this even in moderate
    // conditions. See docs/PRD_gps_accuracy_fix.md. Unconfirmed against real-world
    // degraded-GPS conditions (tree cover, urban canyon); tune from the diagnostic
    // log's REJECTED lines if genuine GPS fixes start getting rejected too often.
    private static final float MAX_ACCEPTABLE_ACCURACY_METERS = 30f;
    // The original web page ticked its idle-timer display every 100ms, which made
    // sense for a page you're actively looking at. A background service has no
    // audience most of the time, so this ticks once a second instead - still feels
    // live when the app is open, and doesn't wake the CPU 10x as often for no reason.
    private static final long TICK_INTERVAL_MS = 1000;
    private static final long WAKE_LOCK_SAFETY_TIMEOUT_MS = 6 * 60 * 60 * 1000L;
    // Low-power idle-watching cadence - deliberately much coarser than tracking's
    // 1s/0m updates, since this can sit running for hours between drives.
    private static final long WATCH_CHECK_INTERVAL_MS = 25_000L;
    private static final double MOVEMENT_TRIGGER_KMH = 8.0;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private Handler tickHandler;
    private Runnable tickRunnable;

    private Mode mode = Mode.WATCHING;
    private boolean serviceStarted;

    // Watching-mode state
    private Location lastWatchLocation;

    // Tracking-mode state
    private long tripId;
    private long tripStartMillis;
    private Location lastLocation;
    private long lastAcceptedFixMillis;
    private boolean isClocking;
    private long clockStartMillis;
    private double totalIdleSeconds;
    private org.json.JSONArray pathPoints;
    private org.json.JSONArray stopPoints;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ensureChannels();
        DiagnosticLog.log(this, "SERVICE", "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        DiagnosticLog.log(this, "SERVICE", "onStartCommand action=" + action);

        if (!serviceStarted) {
            serviceStarted = true;
            // The one and only startForeground() call this service instance ever
            // makes - see the class doc for why every later mode switch
            // deliberately avoids calling it again.
            startForeground(NOTIFICATION_ID, buildWatchingNotification());
            registerWatchingUpdates();
        }

        if (ACTION_START_TRIP.equals(action)) {
            switchToTracking();
        } else if (ACTION_FINISH_TRIP.equals(action)) {
            finishTrip();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Mode switching ----

    /** The actual fix: called directly - never via an Intent/startService round
      * trip - both when movement detection fires while watching and when the
      * user taps Start Engine. Neither path calls
      * startForeground()/startForegroundService(); the service is already in
      * the foreground from its one-time initial start, so this only changes
      * what it's doing, not whether it's running. */
    private void switchToTracking() {
        if (mode == Mode.TRACKING) {
            DiagnosticLog.log(this, "TRIP", "switchToTracking ignored - already tracking");
            return;
        }
        mode = Mode.TRACKING;

        TrackingState.reset();
        TrackingState.armed = true;

        tripId = System.currentTimeMillis();
        tripStartMillis = tripId;
        lastLocation = null;
        lastAcceptedFixMillis = 0;
        isClocking = false;
        totalIdleSeconds = 0;
        pathPoints = new org.json.JSONArray();
        stopPoints = new org.json.JSONArray();
        DiagnosticLog.log(this, "TRIP", "startTrip id=" + tripId);

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildTrackingNotification());
        acquireWakeLock();
        registerTrackingUpdates();
        startTicking();
        broadcastState();
    }

    private void switchToWatching() {
        mode = Mode.WATCHING;
        lastWatchLocation = null;
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildWatchingNotification());
        registerWatchingUpdates();
        DiagnosticLog.log(this, "MONITOR", "switchToWatching - resumed watching for movement");
    }

    private void finishTrip() {
        if (mode != Mode.TRACKING) {
            DiagnosticLog.log(this, "TRIP", "finishTrip ignored - no trip was armed");
            return;
        }
        if (isClocking) {
            // Fold in whatever idle stretch was still in progress when the trip
            // ended - otherwise the final idle period never gets counted, since
            // normally that only happens when speed resumes past the threshold.
            totalIdleSeconds += (System.currentTimeMillis() - clockStartMillis) / 1000.0;
        }
        stopClock();
        saveTrip();
        DiagnosticLog.log(this, "TRIP", String.format(Locale.US,
                "finishTrip id=%d distanceKm=%.2f totalIdleSeconds=%.1f pathPoints=%d flowRating=%.1f",
                tripId, TrackingState.distanceKm, totalIdleSeconds,
                pathPoints != null ? pathPoints.length() : 0, TrackingState.flowRating));
        TrackingState.armed = false;
        stopTicking();
        releaseWakeLock();
        broadcastState();
        // Resumes watching in place - the service itself never stops between
        // trips now, so there's no gap where neither mode's notification is
        // showing, and no second startForeground() call involved.
        switchToWatching();
    }

    // ---- Location handling ----

    @Override
    public void onLocationChanged(Location location) {
        if (mode == Mode.WATCHING) {
            handleWatchingLocation(location);
        } else {
            handleTrackingLocation(location);
        }
    }

    /** Movement detection while idle-watching - unchanged from the original
      * design, except the transition on detected movement is now a direct
      * switchToTracking() call instead of starting a second service. */
    private void handleWatchingLocation(Location location) {
        if (lastWatchLocation != null) {
            double elapsedSeconds = (location.getTime() - lastWatchLocation.getTime()) / 1000.0;
            if (elapsedSeconds > 0) {
                double kmh = location.hasSpeed() && location.getSpeed() > 0
                        ? location.getSpeed() * 3.6
                        : lastWatchLocation.distanceTo(location) / elapsedSeconds * 3.6;
                DiagnosticLog.log(this, "MONITOR", String.format(Locale.US,
                        "onLocationChanged speedKmh=%.1f elapsed=%.0fs", kmh, elapsedSeconds));
                if (kmh >= MOVEMENT_TRIGGER_KMH) {
                    DiagnosticLog.log(this, "MONITOR", "movement detected - switching to tracking");
                    switchToTracking();
                    return;
                }
            }
        }
        lastWatchLocation = location;
    }

    /** Active trip tracking, including the GPS-accuracy filtering from
      * docs/PRD_gps_accuracy_fix.md (see isAcceptableFix/resolveSpeedKmh below) -
      * unchanged by this refactor. */
    private void handleTrackingLocation(Location location) {
        if (!isAcceptableFix(location)) {
            DiagnosticLog.log(this, "GPS", String.format(Locale.US,
                    "onLocationChanged REJECTED accuracy=%s provider=%s (needs <=%.0fm accuracy)",
                    location.hasAccuracy() ? String.format(Locale.US, "%.0fm", location.getAccuracy()) : "unknown",
                    location.getProvider(), MAX_ACCEPTABLE_ACCURACY_METERS));
            return;
        }

        long now = System.currentTimeMillis();
        double deltaKm = lastLocation != null ? distanceKm(lastLocation, location) : 0;

        double kmh = resolveSpeedKmh(location, deltaKm, now);
        TrackingState.speedKmh = kmh;
        TrackingState.lat = location.getLatitude();
        TrackingState.lon = location.getLongitude();

        if (lastLocation != null) {
            TrackingState.distanceKm += deltaKm;

            if (kmh < IDLE_SPEED_THRESHOLD_KMH && !isClocking) {
                startClock();
            }
            if (kmh > RESUME_SPEED_THRESHOLD_KMH && isClocking) {
                double dur = (now - clockStartMillis) / 1000.0;
                if (dur > 2) {
                    totalIdleSeconds += dur;
                    addStopPoint(location, dur);
                }
                stopClock();
            }
        }
        addPathPoint(location);
        lastLocation = location;
        lastAcceptedFixMillis = now;
        updateFlowRating();
        broadcastState();
        updateTrackingNotification();

        DiagnosticLog.log(this, "GPS", String.format(Locale.US,
                "onLocationChanged lat=%.5f lon=%.5f speedKmh=%.1f deltaKm=%.4f totalKm=%.2f accuracy=%.0fm provider=%s",
                location.getLatitude(), location.getLongitude(), kmh, deltaKm,
                TrackingState.distanceKm, location.getAccuracy(), location.getProvider()));
    }

    /** A fix has to both report an accuracy figure and be within
      * MAX_ACCEPTABLE_ACCURACY_METERS to be trusted for distance/speed/idle
      * purposes. Fixes that fail this are still logged (see the REJECTED
      * line above) but never touch TrackingState, lastLocation, or the idle
      * clock - so one bad fix can't become the baseline the *next* fix's
      * delta is measured against either. */
    private boolean isAcceptableFix(Location location) {
        return location.hasAccuracy() && location.getAccuracy() <= MAX_ACCEPTABLE_ACCURACY_METERS;
    }

    /** Prefers the fix's own reported speed when present. Falls back to
      * distance-since-last-accepted-fix / time-since-last-accepted-fix when
      * it's not (some fixes don't carry a speed value) rather than
      * defaulting to 0 - a defaulted-to-zero speed is exactly what let
      * stationary-looking fixes falsely trigger the idle clock at highway
      * speed before this fix. Holds the last known speed rather than
      * assuming 0 when there's nothing to derive from yet (the very first
      * accepted fix of a trip, or two fixes landing in the same
      * millisecond). */
    private double resolveSpeedKmh(Location location, double deltaKm, long now) {
        if (location.hasSpeed()) {
            return location.getSpeed() * 3.6;
        }
        if (lastLocation != null && lastAcceptedFixMillis > 0 && now > lastAcceptedFixMillis) {
            double hours = (now - lastAcceptedFixMillis) / 3600000.0;
            return deltaKm / hours;
        }
        return TrackingState.speedKmh;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        DiagnosticLog.log(this, "GPS", "onStatusChanged provider=" + provider + " status=" + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        DiagnosticLog.log(this, "GPS", "onProviderEnabled " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        DiagnosticLog.log(this, "GPS", "onProviderDisabled " + provider);
    }

    // ---- Location-provider registration ----

    private void registerWatchingUpdates() {
        locationManager.removeUpdates(this);
        boolean hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        DiagnosticLog.log(this, "MONITOR", "startWatching, hasPermission=" + hasPermission);
        if (!hasPermission) return;

        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        DiagnosticLog.log(this, "MONITOR", "NETWORK_PROVIDER enabled=" + networkEnabled);
        if (!networkEnabled) {
            // No fallback to GPS here on purpose - that would defeat the whole
            // point of a low-power idle watcher. If a device has no network
            // location provider at all, auto-start just won't fire on it; Start
            // Engine still works manually.
            return;
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, WATCH_CHECK_INTERVAL_MS, 0f, this);
            DiagnosticLog.log(this, "MONITOR", "requestLocationUpdates registered on NETWORK_PROVIDER, interval="
                    + (WATCH_CHECK_INTERVAL_MS / 1000) + "s, trigger=" + MOVEMENT_TRIGGER_KMH + "km/h");
        } catch (Exception e) {
            DiagnosticLog.log(this, "MONITOR", "requestLocationUpdates failed: " + e.getMessage());
        }
    }

    private void registerTrackingUpdates() {
        locationManager.removeUpdates(this);
        boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        DiagnosticLog.log(this, "GPS", "ACCESS_FINE_LOCATION granted=" + hasFine);
        if (!hasFine) return;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            boolean enabled = locationManager.isProviderEnabled(provider);
            DiagnosticLog.log(this, "GPS", provider + " enabled=" + enabled);
            if (!enabled) continue;
            try {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this);
                DiagnosticLog.log(this, "GPS", "requestLocationUpdates registered for " + provider);
            } catch (Exception e) {
                DiagnosticLog.log(this, "GPS", "requestLocationUpdates failed for " + provider
                        + ": " + e.getMessage());
            }
        }
    }

    // ---- Tracking-mode bookkeeping (unchanged from before this refactor) ----

    private void startClock() {
        isClocking = true;
        clockStartMillis = System.currentTimeMillis();
        DiagnosticLog.log(this, "IDLE", "idle clock started");
    }

    private void stopClock() {
        isClocking = false;
        TrackingState.idleSeconds = 0;
        DiagnosticLog.log(this, "IDLE", "idle clock stopped, totalIdleSeconds="
                + String.format(Locale.US, "%.1f", totalIdleSeconds));
    }

    private void startTicking() {
        tickHandler = new Handler(Looper.getMainLooper());
        tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (mode != Mode.TRACKING) return;
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
        if (pathPoints == null || pathPoints.length() < 2) {
            DiagnosticLog.log(this, "TRIP", "saveTrip skipped - fewer than 2 path points recorded");
            return;
        }
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
            DiagnosticLog.log(this, "TRIP", "saveTrip succeeded, " + pathPoints.length() + " path points");
        } catch (JSONException e) {
            DiagnosticLog.log(this, "TRIP", "saveTrip failed: " + e.getMessage());
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

    // ---- Notifications ----

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel trackingChannel = new NotificationChannel(
                    TRACKING_CHANNEL_ID, "Trip Tracking", NotificationManager.IMPORTANCE_LOW);
            trackingChannel.setDescription("Shows while a trip is being tracked");
            nm.createNotificationChannel(trackingChannel);

            NotificationChannel watchingChannel = new NotificationChannel(
                    WATCHING_CHANNEL_ID, "Movement Watch", NotificationManager.IMPORTANCE_MIN);
            watchingChannel.setDescription("Low-priority: shows while idle, watching for movement to auto-start a trip");
            nm.createNotificationChannel(watchingChannel);
        }
    }

    private Notification buildWatchingNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, WATCHING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Watching for movement")
                .setContentText("Trip will start automatically once you're moving")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .build();
    }

    private Notification buildTrackingNotification() {
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

        return new NotificationCompat.Builder(this, TRACKING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Tracking trip")
                .setContentText(content)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(0, "Finish Trip", finishPendingIntent)
                .build();
    }

    private void updateTrackingNotification() {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildTrackingNotification());
    }

    // ---- Wake lock ----

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            DiagnosticLog.log(this, "WAKE_LOCK", "acquire skipped - PowerManager unavailable");
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GravityIntel:TrackingWakeLock");
        wakeLock.acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS);
        DiagnosticLog.log(this, "WAKE_LOCK", "acquired, safety timeout="
                + (WAKE_LOCK_SAFETY_TIMEOUT_MS / 60000) + "min");
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            DiagnosticLog.log(this, "WAKE_LOCK", "released");
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DiagnosticLog.log(this, "SERVICE", "onDestroy");
        stopTicking();
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            DiagnosticLog.log(this, "GPS", "removeUpdates failed: " + e.getMessage());
        }
        releaseWakeLock();
    }
}
