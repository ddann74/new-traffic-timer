package com.gravityintel.app;

/** Live tracking state, written by TrackingService and read by NativeBridge/MainActivity.
  * Both run in the same process, so a plain volatile-field holder is enough - no IPC
  * needed. This is the single source of truth the WebView UI is synced from, whether
  * via a push (broadcast while visible) or a pull (getStateJson() on (re)load). */
public final class TrackingState {

    private TrackingState() {}

    public static volatile boolean armed = false;
    public static volatile double speedKmh = 0.0;
    public static volatile double distanceKm = 0.0;
    public static volatile double idleSeconds = 0.0;
    public static volatile double flowRating = 10.0;
    public static volatile double lat = 0.0;
    public static volatile double lon = 0.0;

    public static void reset() {
        speedKmh = 0.0;
        distanceKm = 0.0;
        idleSeconds = 0.0;
        flowRating = 10.0;
    }
}
