# PRD — Unify MotionMonitorService into TrackingService

## Problem
`MotionMonitorService` (idle-watcher, foreground service type=location) and
`TrackingService` (active trip tracker, foreground service type=location)
are two separate `Service` classes. When `MotionMonitorService` detects
movement, it calls `ContextCompat.startForegroundService()` to start
`TrackingService` as a **second, new** foreground service - triggered
purely from a passive `LocationListener` callback, with no user
interaction. `TrackingService.finishTrip()` does the mirror-image call to
restart `MotionMonitorService` when a trip ends.

Android 12+ does not allow starting a *new* foreground service from a
background trigger without a specific exemption (a visible Activity, a
notification action tap, an exact alarm, etc.) - an already-running
foreground service does not itself grant that exemption to a sibling
service it tries to start. This was confirmed against a real device's
diagnostic log: every auto-detected movement event crashes, first with
`SecurityException` (Android's generic type-`location` FGS denial
wording), then on retry with the more specific
`ForegroundServiceStartNotAllowedException: mAllowStartForeground false`.
This is not intermittent - it will fail this way on every movement
detection while the app is backgrounded, on any Android 12+ device.
Manual "Start Engine" works because a foreground Activity tap *is* a
valid exemption.

## Goals
- Auto-start-on-movement transitions the **same already-running**
  foreground service from watching to tracking mode via a direct
  in-process method call - never starts a second, separate foreground
  service, so the background-FGS-start restriction never applies.
- No visible behavior change to manual Start Engine / Finish Trip / the
  notification's Finish Trip action / the WebView bridge - same external
  behavior, different internal wiring.
- Notification content/channel still distinguishes "Watching for
  movement" (MIN importance) from "X km - Flow Y" (LOW importance,
  ongoing tracking) exactly as before - delivered by one Service class
  under one notification ID instead of two Service classes with two IDs.
- Exactly one foreground service, one notification, active at any given
  time (already a stated goal of the original design - stays true).
- The already-shipped GPS-accuracy fix (accuracy filtering, derived
  speed - see `docs/PRD_gps_accuracy_fix.md`) is preserved unchanged
  inside tracking-mode's location handling.

## Non-goals
- Not changing the movement-detection trigger itself (8 km/h threshold,
  25s check interval, network-only, no GPS fallback for watching).
- Not adding a manual tap-to-confirm step (that was the rejected
  alternative - the whole point here is auto-start stays automatic).
- Not changing minSdk/targetSdk or adding a Play Services dependency.

## Functional requirements
1. A single `Service` class (`TrackingService` survives; `MotionMonitorService`
   is deleted, not left dead) with an internal `Mode { WATCHING, TRACKING }`
   field.
2. `startForeground()` is called exactly once per service lifetime - at
   the point `MainActivity` first starts the service (unchanged exemption:
   a foregrounded Activity starting a service). Every later mode switch
   only changes notification content/channel (via `NotificationManagerCompat
   .notify()` on the same ID) and location-listener registration - it never
   calls `startForeground()`/`startForegroundService()` again.
3. `AndroidManifest.xml` declares exactly one `<service>` for this class;
   `MotionMonitorService`'s entry is removed.
4. WATCHING mode registers `NETWORK_PROVIDER` only, 25s interval - matches
   today's `MotionMonitorService` behavior exactly.
5. TRACKING mode registers `GPS_PROVIDER` + `NETWORK_PROVIDER`, 1s
   interval, with the existing accuracy-filtered `onLocationChanged`
   logic - matches today's `TrackingService` behavior exactly.
6. Movement detected while WATCHING calls an internal
   `switchToTracking()` method directly (no `Intent`, no
   `startService`/`startForegroundService` round-trip at all) - this is
   the actual fix.
7. Finishing a trip (manual tap or notification action - both unchanged
   trigger paths) calls an internal `switchToWatching()` method the same
   way - re-registers network-only updates, restores the watching
   notification, in place.
8. Manually starting a trip (`NativeBridge.startTrip()`, the WebView "Start
   Engine" tap) goes through the **same** `switchToTracking()` path movement
   detection uses - one way this transition happens, not two that could
   drift apart. `NativeBridge.startTrip()` changes from
   `startForegroundService()` to plain `startService()`, since the unified
   service is already running in WATCHING mode by the time a user can tap
   anything.
9. Wake lock and the 1-second tick handler stay scoped to TRACKING mode
   only, exactly as today - WATCHING mode acquires neither.
10. Diagnostic log tags stay distinct by mode ("MONITOR" for
    watching-mode events, "TRIP"/"GPS"/"WAKE_LOCK" for tracking-mode
    events) even though it's one class now, so the log the user already
    reads to debug this stays filterable the same way.
11. `MainActivity`'s two call sites that start the idle-watcher (`onCreate`,
    `onRequestPermissionsResult`) target the unified service class.

## Acceptance criteria
- Tracing the auto-detected-movement path by hand shows zero
  `startForeground()`/`startForegroundService()` calls after the
  service's initial start - only `NotificationManagerCompat.notify()`
  and `LocationManager.requestLocationUpdates()`/`removeUpdates()`,
  neither of which the background-FGS-start restriction applies to.
- Manual Start Engine, Finish Trip (both the WebView button and the
  notification action), and the WebView state-push bridge all still
  work exactly as before from the user's perspective.
- Exactly one `<service>` remains in the manifest; `MotionMonitorService.java`
  no longer exists in the tree.
- GPS-accuracy filtering (30m threshold, derived speed, REJECTED logging)
  is unchanged inside TRACKING mode.

## Implementation checklist
- [x] Design the unified service's mode field + notification/channel/
      provider-registration switching methods
- [x] Merge MotionMonitorService's watching-mode logic (movement
      detection, watch notification/channel, network-only registration)
      into TrackingService as WATCHING-mode behavior
- [x] Replace the movement-detected `startForegroundService()` call (the
      actual crash site) with a direct in-process `switchToTracking()`
      call
- [x] Replace `startTrip()`/`finishTrip()`'s cross-service
      `stopService`/`startForegroundService` calls with in-process mode
      switches (`switchToTracking()`/`switchToWatching()`)
- [x] Update `AndroidManifest.xml`: remove `MotionMonitorService`'s
      `<service>` entry
- [x] Delete `MotionMonitorService.java`; update `MainActivity.java`'s
      service-start call sites and `NativeBridge.startTrip()`'s
      `startForegroundService()` → `startService()`
- [x] Manual verification: trace the auto-detect-movement path and the
      manual Start Engine/Finish Trip paths end to end, confirm no
      `startForeground()`/`startForegroundService()` call happens after
      the service's initial start in any of them
- [x] Update README (architecture section no longer describes two
      services), commit

## Manual verification trace

**Path A - auto-detected movement (the actual crash site, now fixed):**
Service already foreground (started once by `MainActivity.onCreate`).
`onLocationChanged` → `handleWatchingLocation` computes kmh >= 8.0 →
calls `switchToTracking()` as a **plain Java method call** - no `Intent`,
no `Context.startService`/`startForegroundService` anywhere in the chain.
Inside `switchToTracking()`: `NotificationManagerCompat.notify()` (updates
the existing foreground notification's content, not a new
`startForeground()` call), `acquireWakeLock()` (PowerManager, unrelated
to FGS-start), `registerTrackingUpdates()` (`LocationManager.removeUpdates`
+ `requestLocationUpdates`, unrelated), `startTicking()` (`Handler`,
unrelated). **Zero** `startForeground`/`startForegroundService` calls.

**Path B - manual Start Engine (WebView tap):**
`NativeBridge.startTrip()` → `context.startService()` (plain, not
`startForegroundService` - the service is already running/foreground) →
delivers `ACTION_START_TRIP` → `onStartCommand`'s one-time bootstrap
block is skipped (`serviceStarted` already true) → `switchToTracking()`,
same as Path A. A plain `startService()` call delivering a command to an
already-started service was never subject to the background-FGS-start
restriction in the first place (that restriction gates promoting a
service *to* foreground, i.e. the `startForeground()` call itself, not
plain command delivery) - same reasoning the pre-existing `finishTrip()`
bridge call already relied on.

**Path C - Finish Trip (notification action or WebView tap):**
Either the notification's own `PendingIntent.getService()` action fires
(a direct user tap on system UI) or `NativeBridge.finishTrip()`'s plain
`startService()` call delivers `ACTION_FINISH_TRIP` → `finishTrip()` →
`switchToWatching()` → `NotificationManagerCompat.notify()` +
`registerWatchingUpdates()` only. Same zero-`startForeground` result.

All three paths confirmed clean by inspection of the actual call chains
in the merged `TrackingService.java` - no automated test harness exists
for this service (same limitation noted in the GPS-accuracy-fix PRD).
