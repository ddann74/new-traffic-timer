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
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.Locale;

/** Idle-time companion to TrackingService: while no trip is active, this watches
  * for real movement using low-power network-based location (never GPS - this can
  * sit running for hours between drives, so it deliberately trades precision and
  * latency for battery) and auto-starts a trip once speed crosses
  * MOVEMENT_TRIGGER_KMH. TrackingService stops this the moment a trip is armed,
  * by any trigger, and restarts it the moment a trip finishes (see
  * TrackingService.startTrip()/finishTrip()) - so exactly one of the two services
  * is ever running at a time, with exactly one ongoing notification, never both.
  *
  * Runs while the app has been opened at least once and is backgrounded or
  * minimized - matching how TrackingService already survives backgrounding. It
  * does NOT survive a phone reboot or a force-close on its own; there's no
  * boot-completed receiver here, so reopening the app once is what resumes it in
  * either case, same as tapping Start Engine already requires today. */
public class MotionMonitorService extends Service implements LocationListener {

    private static final String CHANNEL_ID = "gravity_motion_watch";
    private static final int NOTIFICATION_ID = 5152;
    private static final long CHECK_INTERVAL_MS = 25_000L;
    private static final double MOVEMENT_TRIGGER_KMH = 8.0;

    private LocationManager locationManager;
    private Location lastLocation;
    private boolean isWatching;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        ensureChannel();
        DiagnosticLog.log(this, "MONITOR", "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        startWatching();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startWatching() {
        if (isWatching) {
            DiagnosticLog.log(this, "MONITOR", "startWatching ignored - already watching");
            return;
        }
        if (TrackingState.armed) {
            // Lost a race with TrackingService.startTrip() stopping this service -
            // a trip is already active, nothing to watch for right now.
            DiagnosticLog.log(this, "MONITOR", "startWatching skipped - trip already armed");
            return;
        }
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

        lastLocation = null;
        try {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, CHECK_INTERVAL_MS, 0f, this);
            isWatching = true;
            DiagnosticLog.log(this, "MONITOR", "requestLocationUpdates registered on NETWORK_PROVIDER, interval="
                    + (CHECK_INTERVAL_MS / 1000) + "s, trigger=" + MOVEMENT_TRIGGER_KMH + "km/h");
        } catch (Exception e) {
            DiagnosticLog.log(this, "MONITOR", "requestLocationUpdates failed: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (lastLocation != null) {
            double elapsedSeconds = (location.getTime() - lastLocation.getTime()) / 1000.0;
            if (elapsedSeconds > 0) {
                double kmh = location.hasSpeed() && location.getSpeed() > 0
                        ? location.getSpeed() * 3.6
                        : lastLocation.distanceTo(location) / elapsedSeconds * 3.6;
                DiagnosticLog.log(this, "MONITOR", String.format(Locale.US,
                        "onLocationChanged speedKmh=%.1f elapsed=%.0fs", kmh, elapsedSeconds));
                if (kmh >= MOVEMENT_TRIGGER_KMH) {
                    DiagnosticLog.log(this, "MONITOR", "movement detected - auto-starting trip");
                    Intent startIntent = new Intent(this, TrackingService.class);
                    startIntent.setAction(TrackingService.ACTION_START_TRIP);
                    ContextCompat.startForegroundService(this, startIntent);
                    return; // TrackingService.startTrip() stops this service momentarily
                }
            }
        }
        lastLocation = location;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) {
        DiagnosticLog.log(this, "MONITOR", "onProviderEnabled " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        DiagnosticLog.log(this, "MONITOR", "onProviderDisabled " + provider);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Movement Watch", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Low-priority: shows while idle, watching for movement to auto-start a trip");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Watching for movement")
                .setContentText("Trip will start automatically once you're moving")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DiagnosticLog.log(this, "MONITOR", "onDestroy");
        stopWatching();
    }

    private void stopWatching() {
        if (!isWatching) return;
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            DiagnosticLog.log(this, "MONITOR", "removeUpdates failed: " + e.getMessage());
        }
        isWatching = false;
    }
}
