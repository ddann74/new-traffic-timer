# Gravity Intel — Android

Native Android wrapper of the `new-traffic-timer` web app (`../index.html`), built
so trip tracking keeps running when the app isn't in the foreground.

## Why this isn't just the web page in a WebView

The original page tracks GPS, distance, idle time, and the "Flow Rating" score
entirely in JavaScript, via `navigator.geolocation.watchPosition()` and a
`setInterval` timer. A plain WebView wrapper with a foreground service would keep
the *process* alive, but Chrome/WebView independently throttles or suspends JS
timers for a non-visible page - a Chromium-level policy, not an Android OS one,
so a foreground service alone doesn't reliably guarantee tracking continues.

Instead, tracking itself is native:

- **`TrackingService`** - a foreground service that owns GPS updates
  (`android.location.LocationManager`, no Play Services dependency), computes
  distance/idle-time/flow-rating itself, and saves finished trips to a JSON file
  in app-private storage. None of this depends on the WebView being visible,
  attached, or even created.
- **`assets/index.html`** - the same page, visually unchanged, but its tracking
  logic is replaced with a thin bridge: button taps call `window.Native.startTrip()`
  / `.finishTrip()`, and the page renders whatever state
  `window.onNativeUpdate(state)` is handed - pushed from `MainActivity` whenever
  `TrackingService` reports a change (`evaluateJavascript`), and pulled once via
  `Native.getStateJson()` whenever the page (re)loads, so it's never stale.
- **`NativeBridge`** - the `@JavascriptInterface` object exposed as `window.Native`.
- **`TripStore`** - trips used to live in `localStorage`; they're now a single JSON
  array file (`trips.json`) in `getFilesDir()`, read by both `NativeBridge` and
  `TrackingService`.

## Permissions - and why not "Allow all the time"

This only requests `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (the
"while using the app" grant), not `ACCESS_BACKGROUND_LOCATION`. That's
deliberate: Android treats a foreground service declared with
`android:foregroundServiceType="location"` as a sanctioned foreground use of
location, so the lighter permission is sufficient for exactly this trip-tracking
pattern - no need for the heavier "Allow all the time" dialog.

`FOREGROUND_SERVICE_LOCATION` is required alongside `FOREGROUND_SERVICE` on
Android 14+ for a location-type foreground service. `POST_NOTIFICATIONS` is
needed (Android 13+) since the service's whole reliability model depends on
that persistent notification actually showing. `WAKE_LOCK` is held for the
duration of an active trip only, released the moment it finishes, with a
6-hour safety timeout regardless.

## Deliberate behavior change from the original page

The web version auto-armed tracking 500ms after load. This version does
**not** auto-start a trip on open - you have to tap "Start Engine" yourself.
Auto-starting a background service with a wake lock and a persistent
notification just because the app was opened (even to check something else)
felt like the wrong default; the original ephemeral webpage didn't carry that
same battery/notification cost, so the tradeoff that made auto-start harmless
there doesn't carry over here.

## Also different from the web version

The idle-timer/flow-rating tick runs once a second in the background
(`TrackingService`), not every 100ms like the original page's `setInterval`.
100ms made sense for a page you're actively watching; it doesn't serve any
purpose - and does cost battery - when nothing is rendering it.

## Build

1. Open this `android/` folder in Android Studio (not the repo root)
2. Sync Gradle
3. Run on a device (minSdk 21)
4. Grant location + notification permissions when prompted
5. Tap "Start Engine" - the persistent notification confirms tracking is
   active; backgrounding the app, locking the screen, or switching apps
   doesn't stop it. Tap "Finish Trip" in the app or the notification's
   action button to end the trip and save it.

## Ending a trip from the notification

The tracking notification has a "Finish Trip" action, so you can end and
save a trip without reopening the app.
