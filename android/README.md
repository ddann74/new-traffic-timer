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

- **`TrackingService`** - a single foreground service with two internal
  modes: **watching** (idle, low-power, waiting for movement) and
  **tracking** (an active trip). It owns GPS updates
  (`android.location.LocationManager`, no Play Services dependency), computes
  distance/idle-time/flow-rating itself, and saves finished trips to a JSON file
  in app-private storage. None of this depends on the WebView being visible,
  attached, or even created. Auto-starts a trip on real movement while
  watching - see "Auto-start on movement" below. This used to be two
  separate `Service` classes handing off to each other; see
  `docs/PRD_unify_tracking_service.md` for why that broke and why it's one
  service now.
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

## Auto-start on movement

While `TrackingService` is in its **watching** mode (the default - no trip
active), it checks `NETWORK_PROVIDER` location (never GPS) roughly every 25
seconds and switches itself into **tracking** mode once computed speed
reaches 8 km/h. It shows a low-priority "Watching for movement" notification
while watching, switching to the tracking notification the instant a trip
starts (by this trigger or a manual "Start Engine" tap) - one running
service, one notification, its content just changes with the mode.

Movement detection switches modes with a direct in-process method call, not
by starting a second service - an earlier version tried the latter (a
background location callback calling `startForegroundService()` to spin up
a second, separate foreground service) and it crashed on every real
auto-detected movement event on Android 12+, which blocks starting a *new*
foreground service from a background trigger. See
`docs/PRD_unify_tracking_service.md` for the full diagnosis.

This only fires on devices with a working network-based location provider;
there's no fallback to GPS for idle-watching, since that would defeat the
point of keeping idle battery cost low. "Start Engine" still works manually
regardless.

Auto-start only ends a trip the way it always did - you (or the
notification's action) still have to tap Finish Trip; nothing currently
auto-finishes a trip after a period of stillness.

This only runs once the app has been opened at least once and stays running
while backgrounded or minimized - it does not survive a phone reboot or a
force-close on its own (no boot-completed receiver). Reopening the app once
resumes it, same as everything else here already required.

## Earlier design note (superseded above)

The original web version auto-armed tracking 500ms after every page load,
with no way to opt out short of not opening the page. The first native build
deliberately dropped that - auto-starting a background service with a wake
lock and a persistent notification just because the app was opened felt like
the wrong default. Auto-start is back now, but scoped to an explicit signal
(real movement) rather than merely opening the app, which is a meaningfully
different tradeoff than what was rejected here originally.

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
5. Once granted, a "Watching for movement" notification appears - that's
   `TrackingService` idling in watching mode. Start moving at 8 km/h or
   faster (or just tap "Start Engine" yourself) and it switches to "Tracking
   trip" mode; backgrounding the app, locking the screen, or switching apps
   doesn't stop either. Tap "Finish Trip" in the app or the notification's
   action button to end the trip, save it, and go back to watching for the
   next one.

## Ending a trip from the notification

The tracking notification has a "Finish Trip" action, so you can end and
save a trip without reopening the app.

## GPS accuracy filtering

`TrackingService` listens to both `GPS_PROVIDER` and `NETWORK_PROVIDER` -
the network provider is a useful fallback when GPS hasn't locked yet, but
its fixes are typically 40-150m off (cell/wifi triangulation), against
1-2m for a real GPS fix. A fix is only used for distance/speed/idle
purposes if it reports an accuracy of 30m or better
(`MAX_ACCEPTABLE_ACCURACY_METERS` in `TrackingService`); worse fixes are
logged as `REJECTED` in the diagnostic log rather than silently corrupting
the trip - mixing them in unfiltered previously caused both phantom
distance (a network fix and the next GPS fix each measuring a delta
against the other) and false idle-clock triggers (network fixes routinely
carry no speed value at all, which read as "stopped"). See
`docs/PRD_gps_accuracy_fix.md` for the full writeup and a traced example
from a real trip log. This threshold is unconfirmed against genuinely
degraded outdoor GPS (tree cover, urban canyon) - if real GPS fixes start
getting rejected too often, that's visible in the diagnostic log and the
constant is the one place to tune it.

## Diagnostic log (SYS tab)

Below "Factory Wipe" on the SYS tab is a verbose, persistent event log:
service lifecycle, every GPS provider check and location update, idle
clock start/stop transitions, permission grant/denial results, trip
save success/failure, and wake lock acquire/release. `TrackingService`
logs under different tags depending on its current mode - "TRIP"/"GPS"/
"WAKE_LOCK" while tracking, "MONITOR" while watching (including every
idle speed check and what triggered a switch into tracking) - plus full
`Activity` lifecycle ("ACTIVITY") and every `window.Native` bridge call
from the WebView ("BRIDGE"). It's written to a
file (`diagnostic.log` in app-private storage), not kept only in memory
- deliberately, since the scenario most worth debugging (the process
getting killed unexpectedly) is exactly the one an in-memory-only log
would lose right when it's needed most.

It's pulled fresh each time you open the SYS tab, not continuously
pushed - the log is written regardless of whether the app is even open.
Writes are cheap appends rather than a full rewrite each time (this
logs on every location update, so that matters for a long trip), with
the file trimmed back down to the last 5000 lines periodically rather
than left fully unbounded. Tap "Clear" to wipe it.

### Gaps this used to have, now closed

A prior version of this log had real blind spots for troubleshooting -
these are all closed now (native-side only; nothing changed on the
WebView/JS side):

- **`TripStore` used to fail silently.** `loadTrips`/`saveTrips` swallowed
  `IOException`/`JSONException` with no log entry at all, and
  `TrackingService.saveTrip()` logged `"saveTrip succeeded"` right after
  building the trip's JSON object - regardless of whether the write to
  `trips.json` actually succeeded. Every read and write in `TripStore` now
  logs its real outcome (`"TRIPSTORE"` tag), and `saveTrip()`'s own log line
  reflects what `TripStore` actually reported, not just JSON construction.
- **Two silent catches in `TrackingService`** (`addPathPoint`/`addStopPoint`
  dropping a point on a `JSONException`) now log when it happens, so a
  trip that came out shorter than expected leaves a trace of why.
- **The diagnostic log could itself fail with zero trace.** `DiagnosticLog`'s
  own file read/write failures were silently swallowed. They now fall back
  to `Logcat` (`android.util.Log`) - the one deliberate use of Logcat in
  this app, and only as a last resort when the primary file-backed log
  itself can't be written to or read.
- **No crash handler existed at all.** `GravityApplication` (declared as
  the app's `android:name` in the manifest) installs a process-wide
  `Thread.UncaughtExceptionHandler` before any `Activity` or `Service` runs
  - deliberately an `Application` subclass rather than wired from
  `MainActivity.onCreate()`, since `TrackingService` is `START_STICKY`
  and the OS can restart it directly in a fresh process without
  `MainActivity` ever running first. It logs the thread name and full stack
  trace under a `"CRASH"` tag, then always chains to whatever handler was
  already installed (or force-kills the process itself if none was) - it
  never swallows a crash Android or Play Console would otherwise see.
- **`MainActivity` lifecycle was missing `onDestroy`** (onCreate/onResume/
  onPause were already logged) - added for symmetry.
- **Read-side bridge calls weren't logged.** `NativeBridge.getStateJson()`'s
  `JSONException` catch (essentially unreachable in practice, but was
  silent) now logs on failure. `wipeAll()` no longer asserts "trips
  cleared" unconditionally - the real success/failure now comes from
  `TripStore.wipeAll()` itself.

What's still deliberately *not* logged, not because it was missed but
because it isn't a native-app gap: JavaScript-side errors inside
`assets/index.html` (a WebView console-error bridge would be new
web-app-facing work, out of scope here), and routine successful reads that
carry no diagnostic value on their own (e.g. `getDiagnosticLogText()`
itself isn't logged - doing so would mean opening the SYS tab writes a new
entry into the log you're currently reading).
