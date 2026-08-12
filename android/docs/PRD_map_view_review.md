# PRD — Confirm and fix the recorded-trip map view

## Investigation (before writing this PRD)

Read `viewTrip()`/`renderLogs()`/`initMap()` in
`assets/index.html` and traced how a saved trip's path gets from
`TrackingService.saveTrip()` into what's drawn on the Leaflet map.
Three findings, in order of how likely each is to be "what looks off":

1. **The live position marker isn't hidden or reset when reviewing a past
   trip.** `viewTrip()` clears old `L.Polyline`/`L.Circle` layers before
   drawing the reviewed trip, but never touches `userMarker` (the cyan dot
   showing TrackingService's *current* live position). If you review a
   trip while not actively tracking, that dot is still sitting wherever it
   last was - at `[0,0]` if GPS hasn't produced a fix since app launch, or
   at your actual current location, which has nothing to do with the trip
   being reviewed. Either way it can visually read as part of the
   reviewed route when it isn't. This is a real bug, independent of
   whether it's the specific thing that looked wrong.

2. **Trips recorded before the GPS-accuracy fix have bad data baked in,
   permanently.** Before `cd6896c` (`docs/PRD_gps_accuracy_fix.md`),
   `addPathPoint()` ran on *every* location fix, GPS or network,
   unfiltered - the same ping-pong that inflated distance and falsely
   triggered idle detection also went straight into each trip's stored
   `path` array. That's not a live bug to fix; it's already-saved JSON in
   `trips.json`. Any trip recorded before that fix will keep showing the
   same zigzag on its map forever, because nothing reprocesses stored
   trips. A trip recorded *after* that fix should draw a clean path,
   since only accuracy-accepted fixes get added to `pathPoints` now. This
   PRD doesn't retroactively clean old trips - there's no reliable way to
   distinguish "genuinely noisy path" from "phantom ping-pong" after the
   fact from the stored data alone, and guessing at that would risk
   silently altering a real recorded trip.

3. **The WebView side has zero diagnostic visibility - this is also the
   direct answer to "does the log capture everything."** It doesn't:
   every native (Java) code path is well covered (service lifecycle,
   every location fix accepted/rejected, idle transitions, permission
   results, trip save outcomes, wake lock, crashes, bridge calls), but
   `MainActivity` never installs a `WebViewClient`/`WebChromeClient`, so
   nothing from `assets/index.html`'s JavaScript - console errors, page
   load failures, an exception thrown inside `viewTrip()` - reaches the
   diagnostic log, Logcat, or anywhere else visible. If there *is* a
   genuine rendering bug in the map code (as opposed to bad historical
   data per #2), it is currently invisible. This is exactly the tool
   needed to actually confirm #1/#2 versus find something new, and it's a
   real, previously-identified gap (the README's "closed gaps" section
   explicitly called the WebView/JS side out of scope at the time).

## Goals
- Fix the leftover-live-marker bug in `viewTrip()` - a definite, fixable
  issue regardless of what else is going on.
- Close the WebView diagnostic-visibility gap: JS console errors and page
  load failures get logged under a new tag, so a real rendering bug
  (rather than old recorded data) would actually be visible in the log
  next time.
- Document the pre-fix-trips caveat clearly (README + in-app, if cheap)
  rather than silently leaving it a mystery why an old trip's map still
  looks off after this lands.

## Non-goals
- Not retroactively rewriting/cleaning old trips' stored `path` data -
  no reliable way to do that without risking corrupting genuine data,
  and it's out of scope for what was asked.
- Not adding a full crash-reporting/analytics pipeline for the WebView -
  just console-error and page-error visibility in the existing
  diagnostic log, matching how everything else there already works.

## Functional requirements
1. `viewTrip()` hides or repositions `userMarker` while a past trip is
   being reviewed (e.g. remove it from the map, or move it back onto the
   route only if live-tracking is actually active), so it can never be
   mistaken for part of the historical route.
2. `MainActivity`'s `WebView` gets a `WebChromeClient` overriding
   `onConsoleMessage()` (JS `console.error`/`console.warn`/uncaught
   exceptions surface here in a WebView) and a `WebViewClient` overriding
   `onReceivedError()` (page/resource load failures, relevant since the
   map tiles and Leaflet/Tailwind are loaded from CDNs over the network).
   Both log through the existing `DiagnosticLog` under a new `"WEBVIEW"`
   tag.
3. README documents the pre-fix-trips caveat from finding #2 above, so
   it's an explained, expected behavior rather than a live bug report
   waiting to happen again.

## Acceptance criteria
- Reviewing a past trip never shows the live-position marker anywhere on
  the map.
- A deliberately-thrown JS exception (manual test: run
  `throw new Error('test')` from the WebView console, or trigger via
  Chrome remote debugging) appears in the diagnostic log under
  `"WEBVIEW"`.
- The distinction between "old trip, expected pre-fix zigzag" and "new
  trip, should be clean" is written down somewhere a future debugging
  session (yours or mine) will actually find it.

## What this PRD can't verify from this sandbox
There's no Android device or emulator available here, and no real
`trips.json` to inspect - everything above comes from reading the actual
source, not from seeing the map render. After this lands, the real
confirmation is on-device: open a trip recorded *before* today (expect
lingering zigzag, and now no stray live-marker dot) and one recorded
*after* (expect a clean path) and compare.

## Implementation checklist
- [x] Fix `viewTrip()` to hide/reset `userMarker` when reviewing a past
      trip
- [x] Add `WebChromeClient.onConsoleMessage()` override, logged as
      `"WEBVIEW"`
- [x] Add `WebViewClient.onReceivedError()` override, logged as
      `"WEBVIEW"`
- [x] Document the pre-fix-trips caveat in the README
- [x] Manual verification: trace `viewTrip()`'s new logic by hand
      confirming the live marker can't appear during trip review; commit

## Manual verification trace

1. **Review a trip**: `viewTrip(id)` → `tab('map')` → (100ms later)
   `clearMapOverlays()` removes any previously-drawn route, then
   `map.removeLayer(userMarker)` unconditionally (guarded by
   `hasLayer`, safe to call whether or not it was already on the map) -
   the live marker cannot be present once this runs.
2. **Return to live view**: tapping the MAP nav tab now calls
   `showLiveMap()`, not `tab('map')` directly - it clears the reviewed
   trip's layers and re-adds `userMarker` before switching tabs. A
   clean transition back either direction.
3. **Review a second trip without returning to live view first**:
   `viewTrip()` runs again - `clearMapOverlays()` clears the first
   trip's layers, `userMarker` is already off the map (the `hasLayer`
   guard makes the redundant removal a no-op), second trip draws clean.
   No leftover state between consecutive reviews.

No automated test harness exists for this WebView code (same limitation
noted in the other two PRDs in this directory) - confirmed by tracing
the actual logic, not by running it.
