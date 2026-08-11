# PRD — Fix GPS/network provider noise in TrackingService

## Scope note
This covers only the provider-mixing bug verified in `TrackingService.java`
(distance ping-pong + false idle detection), found by reading a real
diagnostic log pasted by the user and confirming the code path against the
current source. It does **not** cover the "MONITOR"/auto-start-on-movement
crashes also present in that same pasted log - that code does not exist
anywhere in this repo (checked current source and full git history), so
there is nothing here to fix for it. If that turns out to live in a
different repo (e.g. a separate `traffic-gravity` project), it needs its
own PRD there.

## Problem
`TrackingService` registers updates on both `GPS_PROVIDER` and
`NETWORK_PROVIDER` simultaneously (`requestLocationUpdates`,
`TrackingService.java:145`), and `onLocationChanged` (`:169-201`) acts on
whichever fix arrives with no accuracy filtering at all. Two consequences,
both visible in the pasted log:

1. A low-accuracy network fix (40-150m accuracy) followed by the next GPS
   fix (1-2m accuracy) each compute a distance delta relative to
   whichever fix came immediately before - so a single network/GPS
   "ping-pong" adds roughly 0.1-0.2km of phantom distance, and this
   repeats every 20-30 seconds throughout a trip.
2. Network fixes routinely carry no speed value at all -
   `location.hasSpeed()` is false, so `kmh` silently defaults to `0.0`
   (`TrackingService.java:170`) - which falsely triggers the idle clock
   (`IDLE_SPEED_THRESHOLD_KMH = 2.5`) even while genuinely doing 70-90
   km/h on a highway, per the real GPS fixes on either side of it. Flow
   Rating is derived from idle time, so this quietly corrupts the score
   on every trip that gets any network fixes at all, not just this one.

## Goals
- Distance accumulation and idle detection only ever act on fixes
  trustworthy enough to use.
- No regression to GPS-only behavior (unchanged when network fixes never
  fire, e.g. a device with no network location backend).
- Rejected fixes stay visible in the diagnostic log rather than silently
  vanishing - that log's whole purpose is debugging exactly this kind of
  thing.

## Non-goals
- No new location backend - this repo deliberately avoids a Play
  Services dependency (`FusedLocationProviderClient` etc.), per the
  existing README; stays on `LocationManager`.
- Not touching the MONITOR/auto-start feature (not in this repo - see
  Scope note above).

## Functional requirements
1. An accuracy threshold constant (`MAX_ACCEPTABLE_ACCURACY_METERS`).
   Fixes without a reported accuracy, or worse than the threshold, are
   rejected for distance/speed/idle purposes.
2. Rejected fixes get their own distinct diagnostic log line
   (`GPS: onLocationChanged REJECTED accuracy=…m provider=… (needs
   <=Xm accuracy)`) instead of disappearing silently.
3. `lastLocation` (the delta baseline for the *next* fix) only advances
   on an accepted fix - so a rejected noisy fix can't become the
   reference point for the next real delta either.
4. Speed used for idle-clock evaluation prefers the fix's own reported
   speed when present; when absent, it's derived from
   distance-since-last-accepted-fix / time-since-last-accepted-fix
   rather than defaulting to 0 - a fix genuinely lacking speed data
   shouldn't be able to assert "stopped."

## Acceptance criteria
- Tracing the pasted log's fix sequence by hand through the new logic
  produces no idle start/stop cycles during the highway-speed segment
  (23:05:xx-23:06:xx in the original log).
- Total distance over that same segment stays close to the GPS-fix-only
  sum, without the ping-pong artifacts.
- GPS-only behavior (network fixes never firing, or always rejected) is
  unchanged from before this fix.

## Implementation checklist
- [x] Add accuracy threshold constant + fix-rejection logging in
      `TrackingService.onLocationChanged`
- [x] Gate distance accumulation and `lastLocation` advancement on
      accepted fixes only
- [x] Gate idle-clock start/stop on accepted fixes with a real or
      derived (not defaulted-to-zero) speed reading
- [x] Manual verification: trace the pasted log's fix sequence through
      the new logic by hand, confirm no false idle cycles / no phantom
      distance (this repo has no unit test harness set up for
      TrackingService, so this is a manual trace, not an automated test)
- [x] Update README if behavior description needs it, commit

## Manual verification trace

Traced the 23:05:52-23:05:54 segment from the pasted log (chosen because
it contains one full ping-pong: two clean GPS fixes, a bad network fix,
then a recovery GPS fix):

| time | provider | accuracy | old behavior | new behavior |
|---|---|---|---|---|
| 23:05:52.079 | gps | 2m | accepted, deltaKm=0.0218 (real) | accepted, same |
| 23:05:53.075 | gps | 2m | accepted, deltaKm=0.0220 (real) | accepted, same |
| 23:05:53.355 | network | 43m | **accepted** - speedKmh defaults to 0.0 (no speed on this fix) → idle clock starts; deltaKm=0.0841 added as real distance | **REJECTED** (43m > 30m threshold) - logged, method returns immediately; `lastLocation`, `TrackingState`, idle clock all untouched |
| 23:05:54.104 | gps | 2m | accepted, but deltaKm=0.0978 measured *from the bad network point* - inflated; idle clock stops 0.73s later (`totalIdleSeconds=25.0` - actually the fixed 25s poll interval, not a real idle period) | accepted, deltaKm measured from the last **accepted** fix (23:05:53.075), so it's back to a normal ~1s-at-80kmh increment; no idle cycle ever started, so nothing to stop |

Net effect on this one ping-pong: old logic added ~0.106km of phantom
distance (0.0841 + the inflated portion of 0.0978) and ran one full
spurious 25-second "idle" cycle while the vehicle was doing 80+ km/h.
New logic adds ~0.022km of real distance and never touches the idle
clock. This pattern repeats roughly every 20-30 seconds throughout the
pasted log's driving segment, so the cumulative effect over a full trip
is substantial for both distance total and Flow Rating.

Also notable: the very first fix of the trip (23:04:37.921,
`provider=network accuracy=100m`) was previously accepted as the
initial `lastLocation` baseline purely because `lastLocation` was still
null - a coarse 100m fix became the reference point the first real
distance delta was measured against. Under the new logic it's rejected
too, so the first accepted fix (23:04:38.128, `gps accuracy=2m`)
becomes the baseline instead - a strict accuracy improvement at the
very start of every trip, not just during the ping-pong pattern.
