# PRD — Auto-finish a trip after sustained no-movement

## Problem
Ending a trip is entirely manual today: `TrackingService` only calls
`finishTrip()` from the "Finish Trip" notification action / WebView button
(`ACTION_FINISH_TRIP`). If the driver parks and walks away without tapping
it, the service stays armed in `Mode.TRACKING` indefinitely - burning the
wake lock, still registering 1-second GPS updates, and never writing the
trip to `TripStore`.

Requested behavior: if there's no GPS movement for a while, assume the
trip is over and finish it automatically - but without letting the
detection window itself get logged as part of the trip's duration or idle
time.

## Design

### Detecting "no movement"
Reuses the existing idle clock rather than adding a second, parallel
timer: `isClocking`/`clockStartMillis` (`TrackingService.java:441-452`)
already tracks a continuous stretch of speed under
`IDLE_SPEED_THRESHOLD_KMH`, driven by the 1-second tick loop in
`startTicking()`. A new constant, `NO_MOVEMENT_AUTO_FINISH_MS = 10 minutes`,
is checked on every tick while `isClocking` is true; once the idle stretch
reaches it, the tick loop calls `finishTrip(true)` instead of rescheduling
itself.

This only fires while the idle clock is actively running - i.e. GPS
fixes are still arriving and reporting near-zero speed. It does **not**
cover GPS fixes stopping altogether while previously moving (phone loses
signal, airplane mode, etc.) - that's a different failure mode (missing
data, not idle data) and out of scope here.

### Not counting the detection window itself
The naive version of this - just call the existing `finishTrip()` after
10 minutes - would log the trip as ending 10 minutes later than it
actually did, and would fold that entire 10-minute wait into
`totalIdleSeconds`, tanking the Flow Rating for a trip that was actually
fine. That confirmation window exists purely to detect the trip is over;
it was never part of the drive.

`finishTrip` now takes a `trimTrailingIdle` boolean (manual finishes pass
`false`, unchanged from before; the watchdog passes `true`):
- `end` is backdated to `clockStartMillis` (when the idle clock started)
  instead of `System.currentTimeMillis()`.
- `totalIdleSeconds` does **not** get the in-progress idle stretch added
  to it (unlike a manual finish, which always folds it in).
- Flow Rating is recomputed as of the backdated end time
  (`updateFlowRating(endMillis, false)`), so the saved `rating` field is
  consistent with the trimmed `end`/`totalIdle`, not the untrimmed ones.
- `pathPoints` gets filtered (`trimPathPointsAfter`) to drop every point
  timestamped after the backdated end - every accepted GPS fix adds a
  path point regardless of speed, so without this the saved route would
  keep plotting ~10 minutes of near-identical stationary points past the
  trip's own recorded end.
- `stopPoints` needs no filtering: an entry is only added when idle
  *resumes* past `RESUME_SPEED_THRESHOLD_KMH` (`handleTrackingLocation`),
  which by definition never happens during the trailing stretch that
  triggers the watchdog in the first place.

## Non-goals
- No new notification/toast announcing the auto-finish. The existing
  notification already flips from "Tracking trip" back to "Watching for
  movement" as part of `switchToWatching()` at the end of `finishTrip` -
  that's the passive signal for now. A more explicit "trip auto-finished"
  notice can be added later if it turns out to be needed.
- No handling for GPS fixes stopping entirely (as opposed to reporting
  near-zero speed) - see Detecting "no movement" above.
- No user-facing setting for the 10-minute threshold - it's a constant
  (`NO_MOVEMENT_AUTO_FINISH_MS`), same as the existing idle/resume speed
  thresholds.

## Acceptance criteria
- A trip where speed drops below `IDLE_SPEED_THRESHOLD_KMH` and never
  resumes gets auto-finished ~10 minutes after speed dropped, not 10
  minutes after the app happened to notice.
- The saved trip's `end` timestamp, `totalIdle`, `rating`, and `path`
  are all consistent with each other and with "the trip ended when
  movement stopped," not "the trip ended when the watchdog fired."
- Manual "Finish Trip" behavior is byte-for-byte unchanged (still folds
  the in-progress idle stretch into `totalIdleSeconds`, still uses "now"
  as `end`).
- A trip that finishes manually while never idle, or that has fewer than
  2 path points after trimming, behaves the same as it always has
  (`saveTrip`'s existing `< 2 points` skip already covers the latter).

## Implementation checklist
- [x] `NO_MOVEMENT_AUTO_FINISH_MS` constant
- [x] Tick-loop watchdog: auto-calls `finishTrip(true)` once the idle
      clock reaches the threshold, without rescheduling itself
- [x] `finishTrip(trimTrailingIdle)`: backdate `end`, skip the
      `totalIdleSeconds` fold-in, recompute Flow Rating as of the
      backdated end, trim trailing path points
- [x] `trimPathPointsAfter` helper
- [x] `updateFlowRating(asOfMillis, includeOngoingIdle)` overload
- [x] `saveTrip(endMillis)` - end time is now a parameter, not always
      "now"
- [x] Manual verification: brace/paren balance check (this repo has no
      unit test harness for `TrackingService`, and the sandbox this was
      built in cannot reach `dl.google.com` to run a real Gradle build -
      same constraint noted in `PRD_gps_accuracy_fix.md`'s sibling repos
      this session). **Not yet verified against a real device or a real
      10-minute stationary period** - the logic has been traced by hand
      against the existing (already real-device-verified) idle-clock
      mechanics it reuses, but the auto-finish path itself is new and
      unconfirmed in practice.
